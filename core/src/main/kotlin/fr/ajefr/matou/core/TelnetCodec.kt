package fr.ajefr.matou.core

import java.io.ByteArrayOutputStream

/**
 * Négociation Telnet (RFC 854) minimale : écho, suppression du go-ahead, type de terminal
 * (RFC 1091) et taille de fenêtre NAWS (RFC 1073). Filtre les commandes IAC du flux entrant
 * et échappe le flux sortant.
 */
class TelnetCodec(private val sendRaw: (ByteArray) -> Unit, private val termType: String = "xterm-256color") {
    private var state = 0          // 0 données, 1 IAC, 2 option attendue, 3 sous-négociation, 4 IAC dans SB
    private var verb = 0
    private val sb = ByteArrayOutputStream()
    private var nawsEnabled = false
    private var cols = 80
    private var rows = 24

    fun decode(input: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        for (i in off until off + len) {
            val b = input[i].toInt() and 0xFF
            when (state) {
                0 -> if (b == IAC) state = 1 else out.write(b)
                1 -> when (b) {
                    IAC -> { out.write(IAC); state = 0 }
                    DO, DONT, WILL, WONT -> { verb = b; state = 2 }
                    SB -> { sb.reset(); state = 3 }
                    else -> state = 0
                }
                2 -> { negotiate(verb, b); state = 0 }
                3 -> if (b == IAC) state = 4 else sb.write(b)
                4 -> when (b) {
                    SE -> { subnegotiation(sb.toByteArray()); state = 0 }
                    IAC -> { sb.write(IAC); state = 3 }
                    else -> state = 3
                }
            }
        }
        return out.toByteArray()
    }

    private fun negotiate(verb: Int, opt: Int) {
        when (verb) {
            DO -> when (opt) {
                TTYPE, SGA -> cmd(WILL, opt)
                NAWS -> { cmd(WILL, opt); nawsEnabled = true; sendNaws() }
                else -> cmd(WONT, opt)
            }
            WILL -> when (opt) {
                ECHO, SGA -> cmd(DO, opt)
                else -> cmd(DONT, opt)
            }
            DONT -> if (opt == NAWS) nawsEnabled = false
            WONT -> {}
        }
    }

    private fun subnegotiation(data: ByteArray) {
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == TTYPE && data[1].toInt() == 1) {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(IAC.toByte(), SB.toByte(), TTYPE.toByte(), 0))
            out.write(termType.toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(IAC.toByte(), SE.toByte()))
            sendRaw(out.toByteArray())
        }
    }

    private fun cmd(v: Int, opt: Int) = sendRaw(byteArrayOf(IAC.toByte(), v.toByte(), opt.toByte()))

    fun resize(cols: Int, rows: Int) {
        this.cols = cols; this.rows = rows
        if (nawsEnabled) sendNaws()
    }

    private fun sendNaws() {
        val out = ByteArrayOutputStream()
        out.write(IAC); out.write(SB); out.write(NAWS)
        for (v in intArrayOf(cols shr 8, cols and 0xFF, rows shr 8, rows and 0xFF)) {
            out.write(v); if (v == IAC) out.write(IAC)
        }
        out.write(IAC); out.write(SE)
        sendRaw(out.toByteArray())
    }

    /** Échappe IAC et transforme CR seul en CR NUL, comme l'exige le protocole. */
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            out.write(b)
            if (b == IAC) out.write(IAC)
            if (b == 13 && (i + 1 >= data.size || data[i + 1].toInt() != 10)) out.write(0)
        }
        return out.toByteArray()
    }

    companion object {
        const val IAC = 255; const val DONT = 254; const val DO = 253; const val WONT = 252
        const val WILL = 251; const val SB = 250; const val SE = 240
        const val ECHO = 1; const val SGA = 3; const val TTYPE = 24; const val NAWS = 31
    }
}
