package fr.ajefr.matou.core

/**
 * Turns raw terminal output into readable text for a session log, like PuTTY's
 * "printable output" mode: escape sequences (CSI, OSC, DCS…) and control characters are removed,
 * carriage returns are dropped (CR LF becomes LF). Bytes are decoded as UTF-8; a character split across two calls is kept
 * until the next call.
 */
class LogFilter {
    private enum class State { NORMAL, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STR, STR_ESC }

    private var state = State.NORMAL
    private val carry = ByteArray(4)
    private var carryLen = 0

    fun filter(data: ByteArray, off: Int = 0, len: Int = data.size): String {
        val bytes: ByteArray
        val start: Int
        val end: Int
        if (carryLen > 0) {
            bytes = ByteArray(carryLen + len)
            System.arraycopy(carry, 0, bytes, 0, carryLen)
            System.arraycopy(data, off, bytes, carryLen, len)
            start = 0; end = bytes.size
            carryLen = 0
        } else {
            bytes = data; start = off; end = off + len
        }
        // Keep an incomplete UTF-8 sequence at the end for the next call.
        var cut = end
        var i = end - 1
        var back = 0
        while (i >= start && back < 4) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 != 0x80) {
                val need = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (end - i < need) cut = i
                break
            }
            i--; back++
        }
        if (cut < end) {
            carryLen = end - cut
            System.arraycopy(bytes, cut, carry, 0, carryLen)
        }
        return filter(String(bytes, start, cut - start, Charsets.UTF_8))
    }

    fun filter(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (state) {
                State.NORMAL -> when {
                    c == '\u001b' -> state = State.ESC
                    c == '\u009b' -> state = State.CSI
                    c == '\u009d' -> state = State.OSC
                    c == '\n' || c == '\t' -> out.append(c)
                    c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f' -> Unit
                    else -> out.append(c)
                }
                State.ESC -> state = when (c) {
                    '[' -> State.CSI
                    ']' -> State.OSC
                    'P', 'X', '^', '_' -> State.STR
                    in ' '..'/' -> State.ESC_INTER
                    else -> State.NORMAL
                }
                State.ESC_INTER -> if (c !in ' '..'/') state = State.NORMAL
                State.CSI -> if (c in '@'..'~') state = State.NORMAL
                State.OSC -> when (c) {
                    '\u0007', '\u009c' -> state = State.NORMAL
                    '\u001b' -> state = State.OSC_ESC
                }
                State.OSC_ESC -> state = if (c == '\\') State.NORMAL else State.OSC
                State.STR -> when (c) {
                    '\u009c' -> state = State.NORMAL
                    '\u001b' -> state = State.STR_ESC
                }
                State.STR_ESC -> state = if (c == '\\') State.NORMAL else State.STR
            }
        }
        return out.toString()
    }
}
