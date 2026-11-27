package fr.ajefr.matou.core

import java.io.ByteArrayOutputStream

/** Événements que l'émulateur remonte à l'interface. */
interface TerminalListener {
    fun onTitleChanged(title: String) {}
    fun onBell() {}
    fun onClipboardSet(text: String) {}
}

/**
 * Émulateur de terminal compatible xterm (sous-ensemble large : ce qu'utilisent bash, vim,
 * tmux, htop, less, mc…). Reçoit les octets de l'hôte via [feed] et renvoie les réponses
 * (rapports de position, attributs du terminal…) via [output].
 *
 * Toutes les méthodes publiques doivent être appelées sous `synchronized(terminal)` quand le
 * rendu et la réception tournent sur des threads différents.
 */
class Terminal(
    cols: Int,
    rows: Int,
    private val output: (ByteArray) -> Unit,
    scrollback: Int = 5000,
) {
    var cols = cols; private set
    var rows = rows; private set

    val mainScreen = Screen(cols, rows, scrollback)
    private val altScreen = Screen(cols, rows, 0)
    var screen = mainScreen; private set
    val isAltScreen get() = screen === altScreen

    var listener: TerminalListener? = null

    // Curseur
    var cursorX = 0; private set
    var cursorY = 0; private set
    private var pendingWrap = false

    // Style courant
    private var curFg = 0
    private var curBg = 0
    private var curAttr = 0

    // Curseur sauvegardé (DECSC)
    private class Saved(val x: Int, val y: Int, val fg: Int, val bg: Int, val attr: Int,
                        val origin: Boolean, val g0: Boolean, val g1: Boolean, val useG1: Boolean)
    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    // Région de défilement
    private var top = 0
    private var bottom = rows - 1

    // Modes
    var appCursorKeys = false; private set
    var appKeypad = false; private set
    var bracketedPaste = false; private set
    var cursorVisible = true; private set
    var reverseVideo = false; private set
    /** 0 = aucun, 1000 = clics, 1002 = clics + glisser, 1003 = tous mouvements. */
    var mouseTracking = 0; private set
    var mouseSgr = false; private set
    var focusReporting = false; private set
    /** DECSCUSR : 0/1/2 bloc, 3/4 souligné, 5/6 barre. */
    var cursorStyle = 1; private set
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var newLineMode = false

    private var tabStops = BooleanArray(cols) { it % 8 == 0 }

    // Jeux de caractères (seul le jeu « graphique DEC » est géré, pour les cadres)
    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false

    var title = ""; private set
    private var lastPrinted = ' '.code

    // Couleurs (ARGB) définies par le thème, modifiables par OSC 4/10/11
    var palette = Colors.defaultPalette()
    var defaultFg = 0xFFE0E0E0.toInt()
    var defaultBg = 0xFF101014.toInt()
    var cursorColor = 0xFFE0E0E0.toInt()
    private var themePalette = palette.copyOf()

    /** Incrémenté à chaque changement, pour que la vue sache quand redessiner. */
    var generation = 0L; private set

    // ---------------------------------------------------------------- Parseur
    private enum class State { GROUND, ESCAPE, ESCAPE_INT, CSI, CSI_IGNORE, OSC, DCS, IGNORE_STRING }

    private var state = State.GROUND
    private val params = IntArray(32)
    private val subParam = BooleanArray(32)
    private var nParams = 0
    private var curParam = -1
    private var nextIsSub = false
    private var privateMarker = 0
    private var intermediate = 0
    private val strBuf = ByteArrayOutputStream()
    private var strEsc = false
    private var utf8Need = 0
    private var utf8Cp = 0

    fun applyTheme(fg: Int, bg: Int, cursor: Int, ansi16: IntArray) {
        defaultFg = fg; defaultBg = bg; cursorColor = cursor
        palette = Colors.defaultPalette()
        for (i in 0 until minOf(16, ansi16.size)) palette[i] = ansi16[i]
        themePalette = palette.copyOf()
        generation++
    }

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        for (i in off until off + len) process(data[i].toInt() and 0xFF)
        generation++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun process(b: Int) {
        when (state) {
            State.OSC, State.DCS, State.IGNORE_STRING -> { processString(b); return }
            else -> {}
        }
        if (b == 0x1B) {
            utf8Need = 0
            state = State.ESCAPE
            intermediate = 0
            return
        }
        if (b == 0x18 || b == 0x1A) { state = State.GROUND; return }
        if (b < 0x20 || b == 0x7F) {
            if (b != 0x7F) control(b)
            return
        }
        when (state) {
            State.GROUND -> ground(b)
            State.ESCAPE -> escape(b)
            State.ESCAPE_INT -> {
                if (b in 0x20..0x2F) intermediate = b
                else { escDispatch(b); state = State.GROUND }
            }
            State.CSI -> csi(b)
            State.CSI_IGNORE -> if (b in 0x40..0x7E) state = State.GROUND
            else -> {}
        }
    }

    private fun ground(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                if (--utf8Need == 0) print(utf8Cp)
                return
            }
            utf8Need = 0
            print(0xFFFD)
        }
        when {
            b < 0x80 -> print(b)
            b and 0xE0 == 0xC0 -> { utf8Cp = b and 0x1F; utf8Need = 1 }
            b and 0xF0 == 0xE0 -> { utf8Cp = b and 0x0F; utf8Need = 2 }
            b and 0xF8 == 0xF0 -> { utf8Cp = b and 0x07; utf8Need = 3 }
            else -> print(0xFFFD)
        }
    }

    private fun escape(b: Int) {
        when (b.toChar()) {
            '[' -> {
                state = State.CSI
                nParams = 0; curParam = -1; nextIsSub = false; privateMarker = 0; intermediate = 0
                return
            }
            ']' -> { startString(State.OSC); return }
            'P' -> { startString(State.DCS); return }
            '_', '^', 'X' -> { startString(State.IGNORE_STRING); return }
        }
        if (b in 0x20..0x2F) { intermediate = b; state = State.ESCAPE_INT; return }
        escDispatch(b)
        if (state == State.ESCAPE) state = State.GROUND
    }

    private fun startString(s: State) {
        state = s
        strBuf.reset()
        strEsc = false
    }

    private fun processString(b: Int) {
        if (strEsc) {
            strEsc = false
            finishString()
            if (b == '\\'.code) { state = State.GROUND; return }
            state = State.ESCAPE
            intermediate = 0
            process(b)
            return
        }
        when (b) {
            0x1B -> strEsc = true
            0x07 -> { finishString(); state = State.GROUND }
            0x18, 0x1A -> state = State.GROUND
            else -> if (strBuf.size() < 1 shl 20) strBuf.write(b)
        }
    }

    private fun finishString() {
        if (state == State.OSC) osc(strBuf.toString(Charsets.UTF_8.name()))
        strBuf.reset()
    }

    private fun csi(b: Int) {
        when (b) {
            in 0x30..0x39 -> curParam = (if (curParam < 0) 0 else curParam).let { minOf(it * 10 + (b - 0x30), 65535) }
            0x3B, 0x3A -> {
                pushParam()
                nextIsSub = b == 0x3A
            }
            in 0x3C..0x3F -> if (nParams == 0 && curParam < 0) privateMarker = b else state = State.CSI_IGNORE
            in 0x20..0x2F -> intermediate = b
            in 0x40..0x7E -> {
                pushParam()
                state = State.GROUND
                csiDispatch(b)
            }
        }
    }

    private fun pushParam() {
        if (nParams < params.size) {
            params[nParams] = curParam
            subParam[nParams] = nextIsSub
            nParams++
        }
        curParam = -1
        nextIsSub = false
    }

    /** Paramètre i ; les valeurs absentes ou nulles prennent la valeur par défaut. */
    private fun p(i: Int, def: Int): Int {
        if (i >= nParams) return def
        val v = params[i]
        return if (v <= 0) def else v
    }

    private fun raw(i: Int): Int = if (i < nParams && params[i] >= 0) params[i] else 0

    // ---------------------------------------------------------------- Contrôles C0
    private fun control(b: Int) {
        when (b) {
            0x07 -> listener?.onBell()
            0x08 -> { if (cursorX > 0) cursorX--; pendingWrap = false }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> { lineFeed(); if (newLineMode) cursorX = 0 }
            0x0D -> { cursorX = 0; pendingWrap = false }
            0x0E -> useG1 = true
            0x0F -> useG1 = false
        }
    }

    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabStops[x]) x++
            cursorX = minOf(x, cols - 1)
        }
        pendingWrap = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabStops[x]) x--
            cursorX = maxOf(x, 0)
        }
        pendingWrap = false
    }

    fun lineFeed() {
        pendingWrap = false
        if (cursorY == bottom) screen.scrollUp(top, bottom, 1, curBg)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == top) screen.scrollDown(top, bottom, 1, curBg)
        else if (cursorY > 0) cursorY--
    }

    // ---------------------------------------------------------------- Impression
    private fun print(c0: Int) {
        var c = c0
        val graphics = if (useG1) g1Graphics else g0Graphics
        if (graphics && c in 0x5F..0x7E) c = DEC_GRAPHICS[c - 0x5F].code
        val w = WcWidth.of(c)
        if (w == 0) return
        if (pendingWrap) {
            if (autoWrap) {
                screen.line(cursorY).wrapped = true
                cursorX = 0
                lineFeed()
            }
            pendingWrap = false
        }
        if (w == 2 && cursorX == cols - 1) {
            if (!autoWrap) return
            screen.line(cursorY).clear(cursorX, cols, curBg)
            screen.line(cursorY).wrapped = true
            cursorX = 0
            lineFeed()
        }
        val line = screen.line(cursorY)
        if (insertMode) insertCells(line, cursorX, w)
        breakWide(line, cursorX)
        if (w == 2) breakWide(line, cursorX + 1)
        line.cp[cursorX] = c; line.fg[cursorX] = curFg; line.bg[cursorX] = curBg; line.attr[cursorX] = curAttr
        if (w == 2) {
            line.cp[cursorX + 1] = Line.WIDE_TAIL
            line.fg[cursorX + 1] = curFg; line.bg[cursorX + 1] = curBg; line.attr[cursorX + 1] = curAttr
        }
        lastPrinted = c
        cursorX += w
        if (cursorX >= cols) {
            cursorX = cols - 1
            pendingWrap = true
        }
    }

    /** Si on écrase la moitié d'un caractère large, on efface l'autre moitié. */
    private fun breakWide(line: Line, x: Int) {
        if (x >= cols) return
        if (line.cp[x] == Line.WIDE_TAIL && x > 0) line.cp[x - 1] = 0
        if (x + 1 < cols && line.cp[x + 1] == Line.WIDE_TAIL) line.cp[x + 1] = 0
    }

    private fun insertCells(line: Line, x: Int, n: Int) {
        for (i in cols - 1 downTo x + n) line.copyCell(i - n, i)
        line.clear(x, minOf(x + n, cols), curBg)
    }

    // ---------------------------------------------------------------- ESC
    private fun escDispatch(b: Int) {
        val ch = b.toChar()
        when (intermediate) {
            0 -> when (ch) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> lineFeed()
                'E' -> { cursorX = 0; lineFeed() }
                'H' -> tabStops[cursorX] = true
                'M' -> reverseIndex()
                'c' -> reset()
                '=' -> appKeypad = true
                '>' -> appKeypad = false
            }
            '('.code -> g0Graphics = ch == '0'
            ')'.code -> g1Graphics = ch == '0'
            '#'.code -> if (ch == '8') { // DECALN : remplit l'écran de E
                for (y in 0 until rows) { val l = screen.line(y); for (x in 0 until cols) { l.clear(x, x + 1, 0); l.cp[x] = 'E'.code } }
            }
        }
        state = State.GROUND
    }

    // ---------------------------------------------------------------- CSI
    private fun csiDispatch(f: Int) {
        val ch = f.toChar()
        if (privateMarker == '?'.code) { decPrivate(ch); return }
        if (privateMarker == '>'.code) {
            when (ch) {
                'c' -> send("\u001b[>1;10;0c")
                'q' -> send("\u001bP>|Matou 0.1\u001b\\")
            }
            return
        }
        if (privateMarker != 0) return
        if (intermediate == ' '.code) {
            if (ch == 'q') cursorStyle = p(0, 1)
            return
        }
        if (intermediate == '!'.code) {
            if (ch == 'p') softReset()
            return
        }
        if (intermediate != 0) return
        when (ch) {
            '@' -> { val l = screen.line(cursorY); insertCells(l, cursorX, minOf(p(0, 1), cols - cursorX)); pendingWrap = false }
            'A' -> moveTo(cursorX, maxOf(cursorY - p(0, 1), if (cursorY >= top) top else 0))
            'B', 'e' -> moveTo(cursorX, minOf(cursorY + p(0, 1), if (cursorY <= bottom) bottom else rows - 1))
            'C', 'a' -> moveTo(minOf(cursorX + p(0, 1), cols - 1), cursorY)
            'D' -> moveTo(maxOf(cursorX - p(0, 1), 0), cursorY)
            'E' -> moveTo(0, minOf(cursorY + p(0, 1), bottom))
            'F' -> moveTo(0, maxOf(cursorY - p(0, 1), top))
            'G', '`' -> moveTo(p(0, 1) - 1, cursorY)
            'H', 'f' -> {
                val y = p(0, 1) - 1 + (if (originMode) top else 0)
                moveTo(p(1, 1) - 1, if (originMode) minOf(y, bottom) else y)
            }
            'I' -> tab(p(0, 1))
            'J' -> eraseDisplay(raw(0))
            'K' -> eraseLine(raw(0))
            'L' -> if (cursorY in top..bottom) { screen.scrollDown(cursorY, bottom, p(0, 1), curBg); cursorX = 0; pendingWrap = false }
            'M' -> if (cursorY in top..bottom) { screen.scrollUp(cursorY, bottom, p(0, 1), curBg); cursorX = 0; pendingWrap = false }
            'P' -> deleteChars(p(0, 1))
            'S' -> screen.scrollUp(top, bottom, p(0, 1), curBg)
            'T' -> if (nParams <= 1) screen.scrollDown(top, bottom, p(0, 1), curBg)
            'X' -> { screen.line(cursorY).clear(cursorX, cursorX + p(0, 1), curBg); pendingWrap = false }
            'Z' -> backTab(p(0, 1))
            'b' -> repeat(minOf(p(0, 1), 65535)) { print(lastPrinted) }
            'c' -> if (raw(0) == 0) send("\u001b[?62;22c")
            'd' -> moveTo(cursorX, p(0, 1) - 1 + (if (originMode) top else 0))
            'g' -> when (raw(0)) { 0 -> tabStops[cursorX] = false; 3 -> tabStops.fill(false) }
            'h' -> setAnsiMode(true)
            'l' -> setAnsiMode(false)
            'm' -> sgr()
            'n' -> when (raw(0)) {
                5 -> send("\u001b[0n")
                6 -> {
                    val y = if (originMode) cursorY - top else cursorY
                    send("\u001b[${y + 1};${cursorX + 1}R")
                }
            }
            'r' -> {
                val t = p(0, 1) - 1
                val bt = p(1, rows) - 1
                if (t < bt && bt < rows) {
                    top = t; bottom = bt
                    moveTo(0, if (originMode) top else 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> when (raw(0)) {
                18 -> send("\u001b[8;$rows;${cols}t")
                22, 23 -> {} // pile de titres : ignorée
            }
        }
    }

    private fun setAnsiMode(on: Boolean) {
        for (i in 0 until nParams) when (params[i]) {
            4 -> insertMode = on
            20 -> newLineMode = on
        }
    }

    private fun decPrivate(ch: Char) {
        when (ch) {
            'h', 'l' -> {
                val on = ch == 'h'
                for (i in 0 until nParams) setDecMode(params[i], on)
            }
            'p' -> if (intermediate == '$'.code) { // DECRQM
                val m = raw(0)
                val v = when (m) {
                    1 -> appCursorKeys; 7 -> autoWrap; 25 -> cursorVisible; 1049, 47, 1047 -> isAltScreen
                    2004 -> bracketedPaste; 1000, 1002, 1003 -> mouseTracking == m; 1006 -> mouseSgr
                    else -> null
                }
                send("\u001b[?$m;${if (v == null) 0 else if (v) 1 else 2}\$y")
            }
            'n' -> if (raw(0) == 6) send("\u001b[?${cursorY + 1};${cursorX + 1}R")
        }
    }

    private fun setDecMode(m: Int, on: Boolean) {
        when (m) {
            1 -> appCursorKeys = on
            5 -> reverseVideo = on
            6 -> { originMode = on; moveTo(0, if (on) top else 0) }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, clear = m == 1047)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) { saveCursor(); switchScreen(true, clear = true) }
                else { switchScreen(false, clear = false); restoreCursor() }
            }
            9, 1000, 1002, 1003 -> mouseTracking = if (on) m else 0
            1006 -> mouseSgr = on
            1004 -> focusReporting = on
            2004 -> bracketedPaste = on
        }
    }

    private fun switchScreen(alt: Boolean, clear: Boolean) {
        val target = if (alt) altScreen else mainScreen
        if (target === screen) return
        screen = target
        if (alt && clear) for (y in 0 until rows) altScreen.line(y).clear(0, cols, curBg)
        pendingWrap = false
    }

    private fun moveTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in cursorY + 1 until rows) screen.line(y).apply { clear(0, cols, curBg); wrapped = false }
            }
            1 -> {
                for (y in 0 until cursorY) screen.line(y).apply { clear(0, cols, curBg); wrapped = false }
                eraseLine(1)
            }
            2 -> for (y in 0 until rows) screen.line(y).apply { clear(0, cols, curBg); wrapped = false }
            3 -> screen.clearHistory()
        }
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val l = screen.line(cursorY)
        when (mode) {
            0 -> { l.clear(cursorX, cols, curBg); l.wrapped = false }
            1 -> l.clear(0, cursorX + 1, curBg)
            2 -> { l.clear(0, cols, curBg); l.wrapped = false }
        }
        pendingWrap = false
    }

    private fun deleteChars(n0: Int) {
        val l = screen.line(cursorY)
        val n = minOf(n0, cols - cursorX)
        for (x in cursorX until cols - n) l.copyCell(x + n, x)
        l.clear(cols - n, cols, curBg)
        pendingWrap = false
    }

    private fun sgr() {
        if (nParams == 0) { curFg = 0; curBg = 0; curAttr = 0; return }
        var i = 0
        while (i < nParams) {
            val v = maxOf(params[i], 0)
            when (v) {
                0 -> { curFg = 0; curBg = 0; curAttr = 0 }
                1 -> curAttr = curAttr or Attr.BOLD
                2 -> curAttr = curAttr or Attr.DIM
                3 -> curAttr = curAttr or Attr.ITALIC
                4 -> {
                    // 4:0 = pas de soulignement, 4:x = styles (tous rendus en simple)
                    val off = i + 1 < nParams && subParam[i + 1] && params[i + 1] == 0
                    curAttr = if (off) curAttr and Attr.UNDERLINE.inv() else curAttr or Attr.UNDERLINE
                }
                5, 6 -> curAttr = curAttr or Attr.BLINK
                7 -> curAttr = curAttr or Attr.INVERSE
                8 -> curAttr = curAttr or Attr.HIDDEN
                9 -> curAttr = curAttr or Attr.STRIKE
                21 -> curAttr = curAttr or Attr.UNDERLINE
                22 -> curAttr = curAttr and (Attr.BOLD or Attr.DIM).inv()
                23 -> curAttr = curAttr and Attr.ITALIC.inv()
                24 -> curAttr = curAttr and Attr.UNDERLINE.inv()
                25 -> curAttr = curAttr and Attr.BLINK.inv()
                27 -> curAttr = curAttr and Attr.INVERSE.inv()
                28 -> curAttr = curAttr and Attr.HIDDEN.inv()
                29 -> curAttr = curAttr and Attr.STRIKE.inv()
                in 30..37 -> curFg = Colors.indexed(v - 30)
                39 -> curFg = 0
                in 40..47 -> curBg = Colors.indexed(v - 40)
                49 -> curBg = 0
                in 90..97 -> curFg = Colors.indexed(v - 90 + 8)
                in 100..107 -> curBg = Colors.indexed(v - 100 + 8)
                38, 48, 58 -> {
                    val (color, used) = extendedColor(i)
                    if (color != null) {
                        if (v == 38) curFg = color else if (v == 48) curBg = color
                    }
                    i += used
                }
            }
            // On saute les sous-paramètres non consommés (ex. 4:3)
            i++
            while (i < nParams && subParam[i]) i++
        }
    }

    /** Analyse 38;5;n / 38;2;r;g;b et les formes à deux-points. Retourne (couleur, paramètres consommés). */
    private fun extendedColor(i: Int): Pair<Int?, Int> {
        if (i + 1 >= nParams) return null to 0
        val colon = subParam[i + 1]
        val kind = params[i + 1]
        if (colon) {
            var n = 0
            while (i + 1 + n < nParams && subParam[i + 1 + n]) n++
            return when (kind) {
                5 -> (if (n >= 2) Colors.indexed(raw(i + 2)) else null) to n
                2 -> {
                    // 38:2:<espace couleur>:r:g:b ou 38:2:r:g:b
                    val base = if (n >= 5) i + 3 else i + 2
                    (if (n >= 4) Colors.rgb(raw(base), raw(base + 1), raw(base + 2)) else null) to n
                }
                else -> null to n
            }
        }
        return when (kind) {
            5 -> (if (i + 2 < nParams) Colors.indexed(raw(i + 2)) else null) to 2
            2 -> (if (i + 4 < nParams) Colors.rgb(raw(i + 2), raw(i + 3), raw(i + 4)) else null) to 4
            else -> null to 1
        }
    }

    // ---------------------------------------------------------------- OSC
    private fun osc(s: String) {
        val sep = s.indexOf(';')
        val code = (if (sep < 0) s else s.substring(0, sep)).toIntOrNull() ?: return
        val arg = if (sep < 0) "" else s.substring(sep + 1)
        when (code) {
            0, 2 -> { title = arg; listener?.onTitleChanged(arg) }
            4 -> {
                val parts = arg.split(';')
                var k = 0
                while (k + 1 < parts.size) {
                    val idx = parts[k].toIntOrNull()
                    val spec = parts[k + 1]
                    if (idx != null && idx in 0..255) {
                        if (spec == "?") send("\u001b]4;$idx;${colorSpec(palette[idx])}\u001b\\")
                        else parseColor(spec)?.let { palette[idx] = it }
                    }
                    k += 2
                }
            }
            10, 11, 12 -> {
                if (arg == "?") {
                    val c = when (code) { 10 -> defaultFg; 11 -> defaultBg; else -> cursorColor }
                    send("\u001b]$code;${colorSpec(c)}\u001b\\")
                } else parseColor(arg)?.let {
                    when (code) { 10 -> defaultFg = it; 11 -> defaultBg = it; else -> cursorColor = it }
                }
            }
            52 -> {
                val data = arg.substringAfter(';', "")
                if (data != "?" && data.isNotEmpty()) {
                    runCatching { String(java.util.Base64.getDecoder().decode(data), Charsets.UTF_8) }
                        .getOrNull()?.let { listener?.onClipboardSet(it) }
                }
            }
            104 -> if (arg.isEmpty()) palette = themePalette.copyOf()
                   else arg.split(';').mapNotNull { it.toIntOrNull() }.filter { it in 0..255 }
                       .forEach { palette[it] = themePalette[it] }
        }
    }

    private fun colorSpec(argb: Int): String {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return "rgb:%02x%02x/%02x%02x/%02x%02x".format(r, r, g, g, b, b)
    }

    private fun parseColor(spec: String): Int? {
        if (spec.startsWith("rgb:")) {
            val p = spec.substring(4).split('/')
            if (p.size != 3) return null
            val v = p.map { h ->
                val n = h.toIntOrNull(16) ?: return null
                when (h.length) { 1 -> n * 17; 2 -> n; 3 -> n shr 4; 4 -> n shr 8; else -> return null }
            }
            return Colors.argb((v[0] shl 16) or (v[1] shl 8) or v[2])
        }
        if (spec.startsWith("#") && spec.length == 7) return spec.substring(1).toIntOrNull(16)?.let { Colors.argb(it) }
        return null
    }

    // ---------------------------------------------------------------- Divers
    private fun saveCursor() {
        val s = Saved(cursorX, cursorY, curFg, curBg, curAttr, originMode, g0Graphics, g1Graphics, useG1)
        if (isAltScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (isAltScreen) savedAlt else savedMain) ?: run { moveTo(0, 0); return }
        curFg = s.fg; curBg = s.bg; curAttr = s.attr; originMode = s.origin
        g0Graphics = s.g0; g1Graphics = s.g1; useG1 = s.useG1
        moveTo(s.x, s.y)
    }

    private fun softReset() {
        cursorVisible = true; originMode = false; autoWrap = true; insertMode = false
        appCursorKeys = false; appKeypad = false
        top = 0; bottom = rows - 1
        curFg = 0; curBg = 0; curAttr = 0
        g0Graphics = false; g1Graphics = false; useG1 = false
        savedMain = null; savedAlt = null
    }

    fun reset() {
        softReset()
        screen = mainScreen
        for (y in 0 until rows) { mainScreen.line(y).clear(0, cols, 0); altScreen.line(y).clear(0, cols, 0) }
        mainScreen.clearHistory()
        cursorX = 0; cursorY = 0; pendingWrap = false
        bracketedPaste = false; mouseTracking = 0; mouseSgr = false; focusReporting = false
        reverseVideo = false; newLineMode = false; cursorStyle = 1
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        palette = themePalette.copyOf()
        title = ""
        state = State.GROUND
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows || newCols < 2 || newRows < 2) return
        val altCy = altScreen.resize(newCols, newRows, if (isAltScreen) cursorY else 0)
        val mainCy = mainScreen.resize(newCols, newRows, if (!isAltScreen) cursorY else 0)
        cursorY = if (isAltScreen) altCy else mainCy
        cols = newCols; rows = newRows
        cursorX = cursorX.coerceIn(0, cols - 1)
        top = 0; bottom = rows - 1
        pendingWrap = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        generation++
    }

    private fun send(s: String) = output(s.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- Aides pour la vue
    /** Couleur ARGB de premier plan effective d'une cellule. */
    fun resolveFg(fg: Int, attr: Int): Int {
        var c = resolve(fg, defaultFg)
        if (attr and Attr.BOLD != 0 && Colors.isIndexed(fg) && Colors.index(fg) < 8) c = palette[Colors.index(fg) + 8]
        if (attr and Attr.DIM != 0) c = (c and 0xFF000000.toInt()) or ((c and 0xFEFEFE) shr 1)
        return c
    }

    fun resolveBg(bg: Int): Int = resolve(bg, defaultBg)

    private fun resolve(c: Int, def: Int) = when {
        Colors.isIndexed(c) -> palette[Colors.index(c)]
        Colors.isRgb(c) -> Colors.argb(c)
        else -> def
    }

    /** Encode un événement souris selon le mode demandé par l'application (null si non suivi). */
    fun encodeMouse(button: Int, col: Int, row: Int, press: Boolean, motion: Boolean = false): String? {
        if (mouseTracking == 0) return null
        if (motion && mouseTracking == 1000) return null
        val cb = button + (if (motion) 32 else 0)
        return if (mouseSgr) "\u001b[<$cb;${col + 1};${row + 1}${if (press) 'M' else 'm'}"
        else {
            if (col > 222 || row > 222) return null
            val b = if (press) cb else 3
            "\u001b[M" + (32 + b).toChar() + (33 + col).toChar() + (33 + row).toChar()
        }
    }

    /** Texte de tout l'écran (historique + visible), pour copier. */
    fun transcript(): String {
        val sb = StringBuilder()
        for (l in mainScreen.history) { sb.append(l.text()); if (!l.wrapped) sb.append('\n') }
        for (y in 0 until rows) { val l = screen.line(y); sb.append(l.text()); if (!l.wrapped) sb.append('\n') }
        return sb.toString().trimEnd() + "\n"
    }

    companion object {
        /** Jeu graphique DEC (0x5F..0x7E) : cadres, losange, etc. */
        private const val DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
