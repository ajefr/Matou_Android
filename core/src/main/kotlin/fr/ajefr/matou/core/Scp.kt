package fr.ajefr.matou.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The SCP protocol (the one `scp` speaks over an SSH exec channel), for servers without SFTP such as
 * many routers and embedded boards running Dropbear. Only single files are transferred.
 *
 * Download: run [downloadCommand] on the server, then call [receive] with the channel streams.
 * Upload: run [uploadCommand], then call [send].
 */
object Scp {
    /** Quotes [s] for a POSIX shell. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun downloadCommand(remotePath: String) = "scp -f " + quote(remotePath)
    fun uploadCommand(remoteDir: String) = "scp -t " + quote(remoteDir)

    class Cancelled : IOException("cancelled")

    /**
     * Receives one file. [open] is given the file name and size and returns where to write it.
     * [progress] gets the bytes copied so far and returns false to cancel.
     */
    fun receive(
        input: InputStream, output: OutputStream,
        open: (name: String, size: Long) -> OutputStream,
        progress: (Long) -> Boolean = { true },
    ): String {
        ack(output)
        while (true) {
            val header = readLine(input)
            when (header.firstOrNull()) {
                'T' -> { ack(output); continue } // times (scp -p), ignored
                'C' -> {
                    val parts = header.substring(1).split(' ', limit = 3)
                    if (parts.size < 3) throw IOException("scp: bad header: $header")
                    val size = parts[1].toLongOrNull() ?: throw IOException("scp: bad size: $header")
                    val name = parts[2]
                    if (name.isEmpty() || '/' in name || name == "." || name == "..") throw IOException("scp: bad name: $name")
                    ack(output)
                    open(name, size).use { out -> copy(input, out, size, progress) }
                    checkAck(input)
                    ack(output)
                    return name
                }
                'D' -> throw IOException("scp: this is a directory")
                else -> throw IOException("scp: unexpected answer: $header")
            }
        }
    }

    /** Sends one file named [name] of [size] bytes, read from [data]. */
    fun send(
        input: InputStream, output: OutputStream,
        name: String, size: Long, data: InputStream,
        progress: (Long) -> Boolean = { true },
    ) {
        require(name.isNotEmpty() && '/' !in name && '\n' !in name) { "bad file name" }
        checkAck(input)
        output.write("C0644 $size $name\n".toByteArray(Charsets.UTF_8))
        output.flush()
        checkAck(input)
        copy(data, output, size, progress)
        output.write(0)
        output.flush()
        checkAck(input)
    }

    private fun ack(output: OutputStream) { output.write(0); output.flush() }

    /** Reads a status byte: 0 = OK, 1 = warning, 2 = error (followed by a message line). */
    private fun checkAck(input: InputStream) {
        when (val b = input.read()) {
            0 -> return
            -1 -> throw IOException("scp: connection closed")
            1, 2 -> throw IOException(serverMessage(readRest(input)))
            else -> throw IOException("scp: unexpected status $b")
        }
    }

    /** Reads a header line. A leading 1 or 2 byte means the server reports an error. */
    private fun readLine(input: InputStream): String {
        val first = input.read()
        if (first == -1) throw IOException("scp: connection closed")
        if (first == 1 || first == 2) throw IOException(serverMessage(readRest(input)))
        return (first.toChar() + readRest(input))
    }

    private fun serverMessage(m: String) = m.trim().let { if (it.startsWith("scp:")) it else "scp: $it" }

    private fun readRest(input: InputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            buf.write(b)
            if (buf.size() > 8192) throw IOException("scp: header too long")
        }
        return buf.toString("UTF-8")
    }

    private fun copy(from: InputStream, to: OutputStream, size: Long, progress: (Long) -> Boolean) {
        val buf = ByteArray(32 * 1024)
        var done = 0L
        while (done < size) {
            val n = from.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
            if (n < 0) throw IOException("scp: connection closed")
            to.write(buf, 0, n)
            done += n
            if (!progress(done)) throw Cancelled()
        }
        to.flush()
    }
}
