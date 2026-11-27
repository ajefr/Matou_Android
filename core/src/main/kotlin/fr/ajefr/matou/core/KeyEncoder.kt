package fr.ajefr.matou.core

/** Touches spéciales indépendantes d'Android. */
enum class Key { UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    BACKSPACE, TAB, ENTER, ESCAPE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

/** Traduit les touches en séquences xterm. */
object KeyEncoder {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4

    fun encode(key: Key, mods: Int, appCursor: Boolean): String {
        val m = if (mods == 0) "" else ";${mods + 1}"
        fun cursor(c: Char) = when {
            mods != 0 -> "\u001b[1$m$c"
            appCursor -> "\u001bO$c"
            else -> "\u001b[$c"
        }
        fun tilde(n: Int) = "\u001b[$n$m~"
        fun ss3(c: Char) = if (mods != 0) "\u001b[1$m$c" else "\u001bO$c"
        val altPrefix = if (mods and ALT != 0) "\u001b" else ""
        return when (key) {
            Key.UP -> cursor('A')
            Key.DOWN -> cursor('B')
            Key.RIGHT -> cursor('C')
            Key.LEFT -> cursor('D')
            Key.HOME -> cursor('H')
            Key.END -> cursor('F')
            Key.PAGE_UP -> tilde(5)
            Key.PAGE_DOWN -> tilde(6)
            Key.INSERT -> tilde(2)
            Key.DELETE -> tilde(3)
            Key.BACKSPACE -> altPrefix + if (mods and CTRL != 0) "\u0008" else "\u007f"
            Key.TAB -> if (mods and SHIFT != 0) "\u001b[Z" else altPrefix + "\t"
            Key.ENTER -> altPrefix + "\r"
            Key.ESCAPE -> altPrefix + "\u001b"
            Key.F1 -> ss3('P'); Key.F2 -> ss3('Q'); Key.F3 -> ss3('R'); Key.F4 -> ss3('S')
            Key.F5 -> tilde(15); Key.F6 -> tilde(17); Key.F7 -> tilde(18); Key.F8 -> tilde(19)
            Key.F9 -> tilde(20); Key.F10 -> tilde(21); Key.F11 -> tilde(23); Key.F12 -> tilde(24)
        }
    }

    /** Caractère imprimable avec modificateurs Ctrl/Alt. */
    fun encodeChar(codePoint: Int, mods: Int): String {
        var c = codePoint
        if (mods and CTRL != 0) {
            c = when (c) {
                in 'a'.code..'z'.code -> c - 'a'.code + 1
                in '@'.code..'_'.code -> c - '@'.code
                ' '.code, '2'.code -> 0
                in '3'.code..'7'.code -> c - '3'.code + 0x1b
                '8'.code, '?'.code -> 0x7f
                '/'.code -> 0x1f
                else -> c
            }
        }
        val s = String(Character.toChars(c))
        return if (mods and ALT != 0) "\u001b$s" else s
    }

    fun bracketPaste(text: String, bracketed: Boolean): String {
        val clean = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "\u001b[200~" + clean.replace("\u001b[201~", "") + "\u001b[201~" else clean
    }
}
