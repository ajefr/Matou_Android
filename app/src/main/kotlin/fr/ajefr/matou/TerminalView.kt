package fr.ajefr.matou

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import fr.ajefr.matou.core.Attr
import fr.ajefr.matou.core.Key
import fr.ajefr.matou.core.KeyEncoder
import fr.ajefr.matou.core.Line
import fr.ajefr.matou.core.Terminal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vue qui dessine l'écran d'une [TerminalSession] et transmet clavier, toucher et sélection.
 * Une seule vue est réutilisée pour tous les onglets.
 */
class TerminalView(context: Context) : View(context) {
    var session: TerminalSession? = null
        set(value) {
            field?.onUpdate = null
            field = value
            value?.onUpdate = { onSessionUpdate() }
            scrollOffset = 0
            clearSelection()
            value?.let { setFontSize(it.profile.fontSize, notify = false) }
            updateSize()
            invalidate()
        }

    /** Modificateurs « collants » de la barre de touches (Ctrl/Alt). */
    var extraModifiers: () -> Int = { 0 }
    var consumeExtraModifiers: () -> Unit = {}
    var onFontSizeChanged: (Float) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bgPaint = Paint()
    private var fontSizeSp = 13f
    private var cellW = 10f
    private var cellH = 20
    private var ascent = 16
    private var scrollOffset = 0
    private var scrollRemainder = 0f
    private val charBuf = CharArray(1024)

    // Sélection, en coordonnées « ligne du tampon » (négatif = historique) et colonne.
    private var selecting = false
    private var selAnchorRow = 0
    private var selAnchorCol = 0
    private var selRow = 0
    private var selCol = 0
    private var hasSelection = false
    private var actionMode: ActionMode? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setFontSize(13f, notify = false)
    }

    // ---------------------------------------------------------------- Police et taille
    fun setFontSize(sp: Float, notify: Boolean = true) {
        fontSizeSp = sp.coerceIn(6f, 40f)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        cellW = paint.measureText("M")
        val fm = paint.fontMetricsInt
        cellH = ceil((fm.descent - fm.ascent).toFloat()).toInt() + 1
        ascent = -fm.ascent
        updateSize()
        invalidate()
        if (notify) onFontSizeChanged(fontSizeSp)
    }

    val fontSize get() = fontSizeSp

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    private fun updateSize() {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = max(4, (width / cellW).toInt())
        val rows = max(2, height / cellH)
        s.resize(cols, rows, width, height)
    }

    private fun onSessionUpdate() {
        // Nouvelle sortie : on revient en bas sauf si l'utilisateur relit l'historique.
        invalidate()
    }

    // ---------------------------------------------------------------- Dessin
    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        val t = s.terminal
        synchronized(t) {
            val defaultBg = if (t.reverseVideo) t.defaultFg else t.defaultBg
            canvas.drawColor(defaultBg)
            val maxOffset = if (t.isAltScreen) 0 else t.mainScreen.history.size
            scrollOffset = scrollOffset.coerceIn(0, maxOffset)
            for (r in 0 until t.rows) {
                val bufRow = r - scrollOffset
                val line = (if (bufRow < 0) t.mainScreen.displayLine(bufRow) else t.screen.displayLine(bufRow)) ?: continue
                val cursorCol = if (bufRow == t.cursorY && t.cursorVisible && scrollOffset == 0) t.cursorX else -1
                drawLine(canvas, t, line, r, bufRow, cursorCol, defaultBg)
            }
        }
    }

    private fun drawLine(canvas: Canvas, t: Terminal, line: Line, r: Int, bufRow: Int, cursorCol: Int, defaultBg: Int) {
        val y = (r * cellH).toFloat()
        val cols = min(line.cols, t.cols)
        var c = 0
        while (c < cols) {
            // Début d'un « run » de cellules au style identique
            val start = c
            val (fg0, bg0) = cellColors(t, line, c, bufRow, cursorCol)
            val attr0 = line.attr[c]
            var n = 0
            var cellsInRun = 0
            val wide = line.cp[c] != 0 && c + 1 < cols && line.cp[c + 1] == Line.WIDE_TAIL
            if (wide) {
                n = appendCp(line.cp[c], 0)
                cellsInRun = 2
                c += 2
            } else {
                while (c < cols && n < charBuf.size - 2) {
                    val cp = line.cp[c]
                    if (cp != 0 && c + 1 < cols && line.cp[c + 1] == Line.WIDE_TAIL) break
                    if (c != start) {
                        val (f, b) = cellColors(t, line, c, bufRow, cursorCol)
                        if (f != fg0 || b != bg0 || line.attr[c] != attr0) break
                    }
                    n = appendCp(if (cp <= 0) ' '.code else cp, n)
                    cellsInRun++
                    c++
                }
            }
            val x = start * cellW
            val w = cellsInRun * cellW
            if (bg0 != defaultBg) {
                bgPaint.color = bg0
                canvas.drawRect(x, y, x + w, y + cellH, bgPaint)
            }
            if (attr0 and Attr.HIDDEN == 0) {
                paint.color = fg0
                paint.isFakeBoldText = attr0 and Attr.BOLD != 0
                paint.textSkewX = if (attr0 and Attr.ITALIC != 0) -0.22f else 0f
                paint.isUnderlineText = attr0 and Attr.UNDERLINE != 0
                paint.isStrikeThruText = attr0 and Attr.STRIKE != 0
                val baseline = y + ascent
                if (wide) {
                    // Caractère large (CJK, emoji) centré sur deux cellules
                    val mw = paint.measureText(charBuf, 0, n)
                    val scale = if (mw > w) w / mw else 1f
                    canvas.save()
                    canvas.scale(scale, 1f, x, baseline)
                    canvas.drawText(charBuf, 0, n, x + max(0f, (w - mw) / 2), baseline, paint)
                    canvas.restore()
                } else {
                    // Le texte du run est dessiné d'un bloc (ligatures possibles) puis recalé sur la grille
                    val mw = paint.measureText(charBuf, 0, n)
                    if (mw > 0 && abs(mw - w) > 0.5f) {
                        canvas.save()
                        canvas.scale(w / mw, 1f, x, baseline)
                        canvas.drawText(charBuf, 0, n, x, baseline, paint)
                        canvas.restore()
                    } else canvas.drawText(charBuf, 0, n, x, baseline, paint)
                }
            }
            if (cursorCol in start until start + cellsInRun && !hasFocus()) {
                // Curseur creux quand la vue n'a pas le focus
                bgPaint.color = t.cursorColor
                bgPaint.style = Paint.Style.STROKE
                bgPaint.strokeWidth = 2f
                val cx = cursorCol * cellW
                canvas.drawRect(cx + 1, y + 1, cx + cellW * (if (wide) 2 else 1) - 1, y + cellH - 1, bgPaint)
                bgPaint.style = Paint.Style.FILL
            }
        }
        // Curseur souligné ou barre (DECSCUSR)
        if (cursorCol >= 0 && hasFocus() && t.cursorStyle >= 3) {
            bgPaint.color = t.cursorColor
            val cx = cursorCol * cellW
            if (t.cursorStyle <= 4) canvas.drawRect(cx, y + cellH - 3, cx + cellW, y + cellH.toFloat(), bgPaint)
            else canvas.drawRect(cx, y, cx + 3, y + cellH.toFloat(), bgPaint)
        }
    }

    private fun appendCp(cp: Int, n: Int): Int = n + Character.toChars(cp, charBuf, n)

    /** Couleurs effectives (inversion, curseur bloc, sélection). */
    private fun cellColors(t: Terminal, line: Line, c: Int, bufRow: Int, cursorCol: Int): Pair<Int, Int> {
        val attr = line.attr[c]
        var fg = t.resolveFg(line.fg[c], attr)
        var bg = t.resolveBg(line.bg[c])
        if (t.reverseVideo) { val x = fg; fg = bg; bg = x }
        if (attr and Attr.INVERSE != 0) { val x = fg; fg = bg; bg = x }
        val block = c == cursorCol && hasFocus() && t.cursorStyle < 3
        val selected = hasSelection && isSelected(bufRow, c)
        if (block || selected) {
            if (block) { fg = bg; bg = t.cursorColor } else { val x = fg; fg = bg; bg = x }
        }
        return fg to bg
    }

    // ---------------------------------------------------------------- Sélection
    private fun selBounds(): IntArray {
        val forward = selAnchorRow < selRow || (selAnchorRow == selRow && selAnchorCol <= selCol)
        return if (forward) intArrayOf(selAnchorRow, selAnchorCol, selRow, selCol)
        else intArrayOf(selRow, selCol, selAnchorRow, selAnchorCol)
    }

    private fun isSelected(row: Int, col: Int): Boolean {
        val (r1, c1, r2, c2) = selBounds()
        if (row < r1 || row > r2) return false
        if (row == r1 && col < c1) return false
        if (row == r2 && col > c2) return false
        return true
    }

    private fun selectedText(): String {
        val s = session ?: return ""
        val t = s.terminal
        val (r1, c1, r2, c2) = selBounds()
        val sb = StringBuilder()
        synchronized(t) {
            for (r in r1..r2) {
                val line = (if (r < 0) t.mainScreen.displayLine(r) else t.screen.displayLine(r)) ?: continue
                val from = if (r == r1) c1 else 0
                val to = if (r == r2) c2 + 1 else line.cols
                sb.append(line.text(from, to))
                if (r != r2 && !line.wrapped) sb.append('\n')
            }
        }
        return sb.toString()
    }

    fun clearSelection() {
        hasSelection = false
        selecting = false
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private fun cellAt(e: MotionEvent): Pair<Int, Int> {
        val t = session?.terminal
        val col = (e.x / cellW).toInt().coerceIn(0, (t?.cols ?: 1) - 1)
        val row = (e.y / cellH).toInt().coerceIn(0, (t?.rows ?: 1) - 1)
        return col to row
    }

    private fun startSelectionMode() {
        if (actionMode != null) { actionMode?.invalidateContentRect(); return }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (hasSelection) menu.add(0, 1, 0, R.string.copy)
                menu.add(0, 2, 1, R.string.paste)
                menu.add(0, 3, 2, R.string.copy_all)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> Clipboard.copy(context, selectedText())
                    2 -> paste()
                    3 -> session?.let { s -> Clipboard.copy(context, synchronized(s.terminal) { s.terminal.transcript() }) }
                }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                hasSelection = false
                invalidate()
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val (_, _, r2, _) = selBounds()
                val y = ((r2 + scrollOffset) * cellH).coerceIn(0, height)
                outRect.set(0, y, width, y + cellH)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    fun paste() {
        val s = session ?: return
        val text = Clipboard.get(context) ?: return
        val bracketed = synchronized(s.terminal) { s.terminal.bracketedPaste }
        s.writeText(KeyEncoder.bracketPaste(text, bracketed))
    }

    // ---------------------------------------------------------------- Toucher
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scrollRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (actionMode != null) { clearSelection(); return true }
            val s = session ?: return true
            val (col, row) = cellAt(e)
            val t = s.terminal
            val seq = synchronized(t) {
                t.encodeMouse(0, col, row, true)?.let { it + (t.encodeMouse(0, col, row, false) ?: "") }
            }
            if (seq != null) s.writeText(seq)
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val s = session ?: return true
            val t = s.terminal
            val arrows = s.profile.swipeSendsArrows
            // En mode historique, un pas plus long évite d'envoyer trop de flèches d'un coup.
            val step = if (arrows) cellH * 2 else cellH
            scrollRemainder += dy
            val lines = (scrollRemainder / step).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * step
            val (col, row) = cellAt(e2)
            synchronized(t) {
                when {
                    t.mouseTracking != 0 -> {
                        val btn = if (lines < 0) 64 else 65
                        val seq = t.encodeMouse(btn, col, row, true) ?: ""
                        repeat(abs(lines)) { s.writeText(seq) }
                    }
                    t.isAltScreen || (arrows && scrollOffset == 0) -> {
                        // less, vim, man… ou mode « historique » : on envoie des flèches.
                        // Doigt vers le bas = ↑ (commande précédente), vers le haut = ↓.
                        val k = KeyEncoder.encode(if (lines > 0) Key.DOWN else Key.UP, 0, t.appCursorKeys)
                        repeat(abs(lines)) { s.writeText(k) }
                    }
                    else -> scrollOffset = (scrollOffset - lines).coerceIn(0, t.mainScreen.history.size)
                }
            }
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (col, row) = cellAt(e)
            selAnchorRow = row - scrollOffset
            selAnchorCol = col
            selRow = selAnchorRow
            selCol = col
            selecting = true
            hasSelection = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setFontSize(fontSizeSp * d.scaleFactor)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (selecting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val (col, row) = cellAt(e)
                    selRow = row - scrollOffset
                    selCol = col
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    startSelectionMode()
                    return true
                }
            }
        }
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------------------------------------------------------- Clavier
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                sendText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) { sendText(composing); composing = "" }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { sendKey(Key.BACKSPACE, 0) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    fun sendText(text: String) {
        val s = session ?: return
        val mods = extraModifiers() and (KeyEncoder.CTRL or KeyEncoder.ALT)
        scrollOffset = 0
        if (mods == 0) {
            s.writeText(text.replace('\n', '\r'))
        } else {
            val sb = StringBuilder()
            text.codePoints().forEach { cp ->
                sb.append(if (cp == '\n'.code) "\r" else KeyEncoder.encodeChar(cp, mods))
            }
            s.writeText(sb.toString())
            consumeExtraModifiers()
        }
        invalidate()
    }

    fun sendKey(key: Key, mods0: Int) {
        val s = session ?: return
        val mods = mods0 or extraModifiers()
        val seq = when {
            key == Key.BACKSPACE && s.profile.backspaceCtrlH -> (if (mods and KeyEncoder.ALT != 0) "\u001b" else "") + "\u0008"
            else -> KeyEncoder.encode(key, mods, synchronized(s.terminal) { s.terminal.appCursorKeys })
        }
        s.writeText(seq)
        if (extraModifiers() != 0) consumeExtraModifiers()
        scrollOffset = 0
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
            KeyEvent.KEYCODE_MOVE_END -> Key.END
            KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> Key.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
            KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
            KeyEvent.KEYCODE_TAB -> Key.TAB
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
            KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Key.entries[Key.F1.ordinal + keyCode - KeyEvent.KEYCODE_F1]
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            else -> null
        }
        var mods = 0
        if (event.isShiftPressed) mods = mods or KeyEncoder.SHIFT
        if (event.isAltPressed) mods = mods or KeyEncoder.ALT
        if (event.isCtrlPressed) mods = mods or KeyEncoder.CTRL
        if (key != null) { sendKey(key, mods); return true }

        // Ctrl+Maj+V : coller (clavier physique)
        if (event.isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) { paste(); return true }

        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp == 0 || cp and KeyCharacterMap.COMBINING_ACCENT != 0) return super.onKeyDown(keyCode, event)
        val all = (mods or extraModifiers()) and (KeyEncoder.CTRL or KeyEncoder.ALT)
        session?.writeText(KeyEncoder.encodeChar(cp, all))
        if (extraModifiers() != 0) consumeExtraModifiers()
        scrollOffset = 0
        invalidate()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}
