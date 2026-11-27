package fr.ajefr.matou.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TelnetCodecTest {
    private val sent = mutableListOf<Int>()
    private val codec = TelnetCodec({ b -> b.forEach { sent.add(it.toInt() and 0xFF) } })

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun filtersNegotiationAndAnswers() {
        val input = bytes('h'.code, 255, 253, 31, 'i'.code, 255, 251, 1, 255, 255)
        val data = codec.decode(input, 0, input.size)
        assertArrayEquals(bytes('h'.code, 'i'.code, 255), data)
        // WILL NAWS + NAWS 80x24, puis DO ECHO
        assertEquals(listOf(255, 251, 31, 255, 250, 31, 0, 80, 0, 24, 255, 240, 255, 253, 1), sent)
    }

    @Test fun answersTerminalType() {
        val input = bytes(255, 250, 24, 1, 255, 240)
        codec.decode(input, 0, input.size)
        val expected = listOf(255, 250, 24, 0) + "xterm-256color".map { it.code } + listOf(255, 240)
        assertEquals(expected, sent)
    }

    @Test fun encodesIacAndCr() {
        assertArrayEquals(bytes('a'.code, 255, 255, 13, 0), codec.encode(bytes('a'.code, 255, 13)))
        assertArrayEquals(bytes(13, 10), codec.encode(bytes(13, 10)))
    }

    @Test fun autoResponder() {
        val sentText = StringBuilder()
        val r = AutoResponder(listOf("login:" to "alex\r", "password:" to "secret\r")) { sentText.append(it) }
        r.onText("Welcome\r\nLog")
        r.onText("in: ")
        assertEquals("alex\r", sentText.toString())
        r.onText("\u001b[1mPassword:\u001b[0m ")
        assertEquals("alex\rsecret\r", sentText.toString())
        assertEquals(true, r.done)
    }
}
