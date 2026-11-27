package fr.ajefr.matou.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LogAndScpTest {
    @Test fun logFilterRemovesEscapesAndCr() {
        val f = LogFilter()
        val raw = "\u001b[1;32muser@host\u001b[0m:~$ ls\r\n\u001b]0;title\u0007a  b\u001b(B\r\n\u001bP1;2q\u001b\\end\u0007\r\n"
        assertEquals("user@host:~$ ls\na  b\nend\n", f.filter(raw.toByteArray()))
    }

    @Test fun logFilterKeepsSequencesSplitAcrossCalls() {
        val f = LogFilter()
        val bytes = "é\u001b[31mrouge\u001b[0m".toByteArray()
        // Split inside the "é" (2 bytes) and inside the escape sequence.
        val a = f.filter(bytes, 0, 1)
        val b = f.filter(bytes, 1, 4)
        val c = f.filter(bytes, 5, bytes.size - 5)
        assertEquals("érouge", a + b + c)
    }

    @Test fun scpReceive() {
        val content = "hello scp\n".toByteArray()
        val server = ByteArrayOutputStream().apply {
            write("C0644 ${content.size} notes.txt\n".toByteArray()); write(content); write(0)
        }.toByteArray()
        val toServer = ByteArrayOutputStream()
        val file = ByteArrayOutputStream()
        var gotSize = -1L
        val name = Scp.receive(ByteArrayInputStream(server), toServer, { _, size -> gotSize = size; file })
        assertEquals("notes.txt", name)
        assertEquals(content.size.toLong(), gotSize)
        assertArrayEquals(content, file.toByteArray())
        assertArrayEquals(byteArrayOf(0, 0, 0), toServer.toByteArray())
    }

    @Test fun scpReceiveError() {
        val server = "\u0001scp: /nope: No such file or directory\n".toByteArray()
        val e = runCatching { Scp.receive(ByteArrayInputStream(server), ByteArrayOutputStream(), { _, _ -> ByteArrayOutputStream() }) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("No such file"))
    }

    @Test fun scpReceiveRejectsPathInName() {
        val server = "C0644 3 ../evil\nabc\u0000".toByteArray()
        val e = runCatching { Scp.receive(ByteArrayInputStream(server), ByteArrayOutputStream(), { _, _ -> ByteArrayOutputStream() }) }.exceptionOrNull()
        assertTrue(e is IOException)
    }

    @Test fun scpSend() {
        val data = "abc".toByteArray()
        val toServer = ByteArrayOutputStream()
        Scp.send(ByteArrayInputStream(byteArrayOf(0, 0, 0)), toServer, "a.txt", 3, ByteArrayInputStream(data))
        assertEquals("C0644 3 a.txt\nabc\u0000", toServer.toString("UTF-8"))
    }

    @Test fun shellQuote() {
        assertEquals("'it'\\''s here'", Scp.quote("it's here"))
        assertEquals("scp -f '/tmp/a b'", Scp.downloadCommand("/tmp/a b"))
    }
}
