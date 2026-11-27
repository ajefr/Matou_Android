package fr.ajefr.matou

import android.content.Context
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import fr.ajefr.matou.core.TelnetCodec
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Questions posées à l'utilisateur pendant la connexion (appelées depuis un thread réseau). */
interface Prompter {
    fun confirm(title: String, message: String): Boolean
    fun askSecret(title: String, message: String, secret: Boolean = true): String?
    fun info(message: String)
}

/** Lien vers l'hôte distant. read() bloque ; -1 = fin de connexion. */
interface Transport {
    fun connect(cols: Int, rows: Int)
    /** Lit des données à afficher (déjà débarrassées du protocole). */
    fun read(buf: ByteArray): Int
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun close()
    /** Lignes d'information affichées après la connexion (redirections actives…). */
    val banner: List<String> get() = emptyList()
}

class SshTransport(
    private val context: Context,
    private val profile: Profile,
    private val prompter: Prompter,
) : Transport {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    override val banner = mutableListOf<String>()

    override fun connect(cols: Int, rows: Int) {
        JschSetup.init()
        val jsch = JSch()
        val knownHosts = File(context.filesDir, "known_hosts").apply { if (!exists()) createNewFile() }
        jsch.setKnownHosts(knownHosts.absolutePath)

        val savedPassword = SecretBox.decrypt(profile.passwordEnc)
        val savedPassphrase = SecretBox.decrypt(profile.keyPassphraseEnc)
        if (profile.keyPath.isNotEmpty() && File(profile.keyPath).exists()) {
            jsch.addIdentity(profile.keyPath, savedPassphrase.ifEmpty { null }?.toByteArray())
        }

        val user = profile.username.ifEmpty {
            prompter.askSecret(context.getString(R.string.prompt_user_title), context.getString(R.string.prompt_user_message, profile.host), secret = false)
                ?: throw Exception(context.getString(R.string.prompt_cancelled))
        }
        val s = jsch.getSession(user, profile.host, profile.port)
        session = s
        if (savedPassword.isNotEmpty()) s.setPassword(savedPassword)
        s.userInfo = Ui(savedPassword, "$user@${profile.host}")
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        if (profile.legacyAlgorithms) {
            // Pour les vieux équipements (routeurs, NAS, switches) qui ne parlent que SHA-1 / CBC.
            fun add(k: String, v: String) = s.setConfig(k, (s.getConfig(k) ?: "") + "," + v)
            add("server_host_key", "ssh-rsa,ssh-dss")
            add("PubkeyAcceptedAlgorithms", "ssh-rsa")
            add("kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1")
            add("cipher.s2c", "aes128-cbc,aes256-cbc,3des-cbc")
            add("cipher.c2s", "aes128-cbc,aes256-cbc,3des-cbc")
            add("mac.s2c", "hmac-sha1")
            add("mac.c2s", "hmac-sha1")
        }
        if (profile.keepAliveSec > 0) {
            s.setServerAliveInterval(profile.keepAliveSec * 1000)
            s.setServerAliveCountMax(4)
        }
        s.connect(20_000)

        for (f in Forward.parseAll(profile.forwards)) {
            runCatching {
                if (f.remote) s.setPortForwardingR(f.bindPort, f.host, f.hostPort)
                else s.setPortForwardingL(f.bindAddress, f.bindPort, f.host, f.hostPort)
                banner += context.getString(R.string.forward_active, f.toString())
            }.onFailure { banner += context.getString(R.string.forward_failed, f.toString(), it.message) }
        }

        val ch = s.openChannel("shell") as ChannelShell
        channel = ch
        ch.setPtyType(profile.termType.ifBlank { "xterm-256color" }, cols, rows, 0, 0)
        input = ch.inputStream
        output = ch.outputStream
        ch.connect(15_000)
    }

    override fun read(buf: ByteArray) = input.read(buf)

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        channel?.setPtySize(cols, rows, widthPx, heightPx)
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }

    private inner class Ui(private var password: String, private val target: String) : UserInfo, UIKeyboardInteractive {
        private var passphrase: String? = null
        private var triedSavedPassword = false

        override fun getPassphrase() = passphrase
        override fun getPassword() = password

        override fun promptPassword(message: String): Boolean {
            if (password.isNotEmpty() && !triedSavedPassword) { triedSavedPassword = true; return true }
            password = prompter.askSecret(context.getString(R.string.prompt_password_title), context.getString(R.string.prompt_password_message, target)) ?: return false
            return true
        }

        override fun promptPassphrase(message: String): Boolean {
            passphrase = prompter.askSecret(context.getString(R.string.prompt_passphrase_title), context.getString(R.string.prompt_passphrase_message)) ?: return false
            return true
        }

        override fun promptYesNo(message: String) = prompter.confirm(context.getString(R.string.prompt_hostkey_title), message)

        override fun showMessage(message: String) = prompter.info(message)

        override fun promptKeyboardInteractive(
            destination: String, name: String, instruction: String, prompt: Array<String>, echo: BooleanArray,
        ): Array<String>? {
            // Cas courant : une seule invite « Password: » -> mot de passe enregistré d'abord.
            if (prompt.size == 1 && !echo[0] && password.isNotEmpty() && !triedSavedPassword) {
                triedSavedPassword = true
                return arrayOf(password)
            }
            return Array(prompt.size) { i ->
                val title = name.ifBlank { context.getString(R.string.prompt_auth_title) }
                val msg = listOf(instruction, prompt[i]).filter { it.isNotBlank() }.joinToString("\n")
                prompter.askSecret(title, msg, secret = !echo[i]) ?: return null
            }
        }
    }
}

/** Telnet ou TCP brut sur une socket. */
class SocketTransport(private val profile: Profile, private val telnet: Boolean) : Transport {
    private val socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val codec = TelnetCodec({ raw -> synchronized(this) { output.write(raw); output.flush() } },
        profile.termType.ifBlank { "xterm-256color" })
    private val readBuf = ByteArray(8192)

    override fun connect(cols: Int, rows: Int) {
        socket.connect(InetSocketAddress(profile.host, profile.port), 20_000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        input = socket.getInputStream()
        output = socket.getOutputStream()
        codec.resize(cols, rows)
    }

    override fun read(buf: ByteArray): Int {
        if (!telnet) return input.read(buf)
        while (true) {
            val n = input.read(readBuf, 0, minOf(readBuf.size, buf.size))
            if (n < 0) return -1
            val data = codec.decode(readBuf, 0, n)
            if (data.isNotEmpty()) {
                data.copyInto(buf)
                return data.size
            }
        }
    }

    override fun write(data: ByteArray) = synchronized(this) {
        output.write(if (telnet) codec.encode(data) else data)
        output.flush()
    }

    override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (telnet) codec.resize(cols, rows)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Android ne lit pas les classes « multi-release » de JSch (Java 11/15+) : on force les
 * implémentations Bouncy Castle pour Ed25519/Ed448 et X25519.
 */
object JschSetup {
    private var done = false

    @Synchronized
    fun init() {
        if (done) return
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        done = true
    }
}
