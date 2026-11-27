package fr.ajefr.matou.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    private val out = StringBuilder()
    private val t = Terminal(20, 5, { out.append(String(it, Charsets.UTF_8)) }, scrollback = 100)

    private fun row(y: Int) = t.screen.line(y).text()

    @Test fun printsAndWraps() {
        t.feed("Bonjour\r\nle monde")
        assertEquals("Bonjour", row(0))
        assertEquals("le monde", row(1))
        t.feed("\r\n" + "x".repeat(25))
        assertEquals("x".repeat(20), row(2))
        assertEquals("xxxxx", row(3))
        assertTrue(t.screen.line(2).wrapped)
    }

    @Test fun utf8AndWideChars() {
        t.feed("é日本")
        val l = t.screen.line(0)
        assertEquals('é'.code, l.cp[0])
        assertEquals('日'.code, l.cp[1])
        assertEquals(Line.WIDE_TAIL, l.cp[2])
        assertEquals(5, t.cursorX)
        assertEquals("é日本", row(0))
    }

    @Test fun scrollsIntoHistory() {
        for (i in 1..7) t.feed("ligne $i\r\n")
        assertEquals("ligne 4", row(0))
        assertEquals(3, t.mainScreen.history.size)
        assertEquals("ligne 3", t.mainScreen.displayLine(-1)!!.text())
    }

    @Test fun cursorMovementAndErase() {
        t.feed("abcdef\u001b[3D\u001b[K")
        assertEquals("abc", row(0))
        t.feed("\u001b[3;5HX")
        assertEquals("    X", row(2))
        t.feed("\u001b[2J")
        assertEquals("", row(2))
    }

    @Test fun sgrColors() {
        t.feed("\u001b[1;31mR\u001b[38;2;10;20;30mT\u001b[48;5;200mB\u001b[38:2::1:2:3mC\u001b[0mN")
        val l = t.screen.line(0)
        assertEquals(Colors.indexed(1), l.fg[0])
        assertTrue(l.attr[0] and Attr.BOLD != 0)
        assertEquals(Colors.rgb(10, 20, 30), l.fg[1])
        assertEquals(Colors.indexed(200), l.bg[2])
        assertEquals(Colors.rgb(1, 2, 3), l.fg[3])
        assertEquals(0, l.fg[4]); assertEquals(0, l.attr[4])
    }

    @Test fun altScreenRestoresMain() {
        t.feed("principal")
        t.feed("\u001b[?1049h")
        assertTrue(t.isAltScreen)
        assertEquals("", row(0))
        t.feed("vim")
        t.feed("\u001b[?1049l")
        assertFalse(t.isAltScreen)
        assertEquals("principal", row(0))
        assertEquals(9, t.cursorX)
    }

    @Test fun scrollRegion() {
        for (i in 0 until 5) t.feed("\u001b[${i + 1};1H$i")
        t.feed("\u001b[2;4r\u001b[4;1H\n")
        assertEquals("0", row(0)); assertEquals("2", row(1)); assertEquals("3", row(2))
        assertEquals("", row(3)); assertEquals("4", row(4))
    }

    @Test fun reportsCursorAndAttributes() {
        t.feed("\u001b[3;7H\u001b[6n\u001b[c")
        assertEquals("\u001b[3;7R\u001b[?62;22c", out.toString())
    }

    @Test fun oscTitle() {
        var title = ""
        t.listener = object : TerminalListener { override fun onTitleChanged(title2: String) { title = title2 } }
        t.feed("\u001b]0;user@hote: ~\u0007")
        assertEquals("user@hote: ~", title)
        t.feed("\u001b]2;autre\u001b\\")
        assertEquals("autre", title)
    }

    @Test fun decLineDrawing() {
        t.feed("\u001b(0lqk\u001b(B")
        assertEquals("┌─┐", row(0))
    }

    @Test fun insertDeleteLinesAndChars() {
        t.feed("abcdef\r\u001b[2P")
        assertEquals("cdef", row(0))
        t.feed("\u001b[2@")
        assertEquals("  cdef", row(0))
        t.feed("\r\nL2\u001b[1;1H\u001b[L")
        assertEquals("", row(0)); assertEquals("  cdef", row(1)); assertEquals("L2", row(2))
    }

    @Test fun resizeKeepsCursorVisible() {
        for (i in 1..5) t.feed("l$i\r\n")
        t.resize(10, 3)
        assertEquals(3, t.rows)
        assertTrue(t.cursorY < 3)
        assertEquals("", row(t.cursorY))
    }

    @Test fun keyEncoding() {
        assertEquals("\u001b[A", KeyEncoder.encode(Key.UP, 0, false))
        assertEquals("\u001bOA", KeyEncoder.encode(Key.UP, 0, true))
        assertEquals("\u001b[1;5C", KeyEncoder.encode(Key.RIGHT, KeyEncoder.CTRL, false))
        assertEquals("\u0003", KeyEncoder.encodeChar('c'.code, KeyEncoder.CTRL))
        assertEquals("\u001bx", KeyEncoder.encodeChar('x'.code, KeyEncoder.ALT))
        assertEquals("\u001b[15~", KeyEncoder.encode(Key.F5, 0, false))
    }

    @Test fun mouseSgr() {
        t.feed("\u001b[?1000h\u001b[?1006h")
        assertEquals("\u001b[<0;3;2M", t.encodeMouse(0, 2, 1, true))
    }
}
