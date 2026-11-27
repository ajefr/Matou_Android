package fr.ajefr.matou

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import fr.ajefr.matou.core.AutoResponder
import fr.ajefr.matou.core.Terminal
import fr.ajefr.matou.core.TerminalListener
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Une connexion ouverte dans un onglet : transport + émulateur. */
class TerminalSession(private val context: Context, val profile: Profile) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    val id = nextId.incrementAndGet()
    val terminal = Terminal(80, 24, { sendResponse(it) })
    @Volatile var state = State.CONNECTING; private set
    var title = profile.displayName(); private set

    /** Appelé (sur le thread principal) quand l'écran ou l'état change. */
    var onUpdate: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var transport: Transport? = null
    @Volatile private var closed = false
    private var cols = 80
    private var rows = 24
    private var widthPx = 0
    private var heightPx = 0
    private val updatePosted = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        applyTheme(Themes.byName(profile.theme))
        terminal.listener = object : TerminalListener {
            override fun onTitleChanged(title: String) {
                this@TerminalSession.title = title.ifBlank { profile.displayName() }
            }
            override fun onClipboardSet(text: String) {
                main.post { Clipboard.copy(context, text) }
            }
        }
    }

    fun applyTheme(t: Theme) = synchronized(terminal) { terminal.applyTheme(t.fg, t.bg, t.cursor, t.ansi) }

    @Volatile private var generation = 0

    fun start() {
        closed = false
        state = State.CONNECTING
        val g = ++generation
        thread(name = "matou-session-$id") { run(g) }
    }

    private fun live(g: Int) = g == generation && !closed

    private fun show(text: String) {
        synchronized(terminal) { terminal.feed(text) }
        postUpdate()
    }

    private fun postUpdate() {
        if (updatePosted.compareAndSet(false, true)) {
            main.post { updatePosted.set(false); onUpdate?.invoke() }
        }
    }

    private fun run(g: Int) {
        val p = profile
        show("\u001b[2m${p.protocol.label} : connexion à ${p.host}:${p.port}…\u001b[0m\r\n")
        val t: Transport = when (p.protocol) {
            Protocol.SSH -> SshTransport(context, p, SessionManager.prompter)
            Protocol.TELNET -> SocketTransport(p, telnet = true)
            Protocol.RAW -> SocketTransport(p, telnet = false)
        }
        try {
            t.connect(cols, rows)
            if (!live(g)) return
            transport = t
            state = State.CONNECTED
            postUpdate()
            if (widthPx > 0) writer.execute { runCatching { t.resize(cols, rows, widthPx, heightPx) } }
            t.banner.forEach { show("\u001b[36m$it\u001b[0m\r\n") }
            setupAutomation()
            readLoop(t, g)
            if (live(g)) disconnected("connexion fermée par l'hôte")
        } catch (e: Throwable) {
            if (live(g)) disconnected(e.message ?: e.javaClass.simpleName)
        } finally {
            t.close()
            if (transport === t) transport = null
        }
    }

    private var responder: AutoResponder? = null

    private fun setupAutomation() {
        val p = profile
        val commands = p.startupCommands.lines().filter { it.isNotBlank() }
        fun sendCommands() {
            if (commands.isEmpty()) return
            main.postDelayed({
                commands.forEachIndexed { i, c -> main.postDelayed({ writeText(c + "\r") }, i * 150L) }
            }, 400)
        }
        if (p.protocol == Protocol.SSH) { sendCommands(); return }
        // Telnet : auto-login sur les invites, puis commandes de démarrage.
        val rules = mutableListOf<Pair<String, String>>()
        if (p.username.isNotEmpty() && p.loginPrompt.isNotBlank()) rules += p.loginPrompt to p.username + "\r"
        val pw = SecretBox.decrypt(p.passwordEnc)
        if (pw.isNotEmpty() && p.passwordPrompt.isNotBlank()) rules += p.passwordPrompt to pw + "\r"
        if (rules.isEmpty()) { sendCommands(); return }
        var sent = false
        responder = AutoResponder(rules) { answer ->
            writeText(answer)
            if (responder?.done == true && !sent) { sent = true; sendCommands() }
        }
    }

    private fun readLoop(t: Transport, g: Int) {
        val buf = ByteArray(16384)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.allocate(buf.size)
        while (live(g)) {
            val n = t.read(buf)
            if (n < 0) break
            if (n == 0) continue
            responder?.takeIf { !it.done }?.let { r ->
                chars.clear()
                decoder.decode(ByteBuffer.wrap(buf, 0, n), chars, false)
                chars.flip()
                r.onText(chars.toString())
            }
            synchronized(terminal) { terminal.feed(buf, 0, n) }
            postUpdate()
        }
    }

    private fun disconnected(reason: String) {
        state = State.DISCONNECTED
        show("\r\n\u001b[0;1;31m[Déconnecté : $reason]\u001b[0m\r\n\u001b[2mEntrée pour se reconnecter.\u001b[0m\r\n")
    }

    /** Saisie de l'utilisateur. En état déconnecté, Entrée relance la connexion. */
    fun write(data: ByteArray) {
        if (state == State.DISCONNECTED) {
            if (data.contains(13)) start()
            return
        }
        val t = transport ?: return
        writer.execute { runCatching { t.write(data) } }
    }

    fun writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    private fun sendResponse(data: ByteArray) {
        val t = transport ?: return
        writer.execute { runCatching { t.write(data) } }
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (cols == this.cols && rows == this.rows && widthPx == this.widthPx) return
        this.cols = cols; this.rows = rows; this.widthPx = widthPx; this.heightPx = heightPx
        synchronized(terminal) { terminal.resize(cols, rows) }
        val t = transport ?: return
        writer.execute { runCatching { t.resize(cols, rows, widthPx, heightPx) } }
    }

    fun reconnect() {
        generation++
        transport?.let { t -> writer.execute { t.close() } }
        transport = null
        main.postDelayed({ if (!closed) start() }, 300)
    }

    fun close() {
        closed = true
        state = State.DISCONNECTED
        transport?.let { t -> writer.execute { t.close() } }
        writer.shutdown()
    }

    companion object {
        private val nextId = AtomicInteger()
    }
}

/** Sessions ouvertes, partagées entre les écrans ; vit aussi longtemps que le processus. */
object SessionManager {
    val sessions = mutableListOf<TerminalSession>()
    var current = 0
    private val listeners = mutableSetOf<() -> Unit>()

    /** Remplacé par l'activité terminal quand elle est visible. */
    var prompter: Prompter = object : Prompter {
        override fun confirm(title: String, message: String) = false
        override fun askSecret(title: String, message: String): String? = null
        override fun info(message: String) {}
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() = listeners.toList().forEach { it() }

    fun open(context: Context, profile: Profile): TerminalSession {
        val s = TerminalSession(context.applicationContext, profile)
        sessions += s
        current = sessions.size - 1
        s.start()
        updateService(context)
        notifyChanged()
        return s
    }

    fun close(context: Context, s: TerminalSession) {
        s.close()
        val i = sessions.indexOf(s)
        sessions.remove(s)
        if (current >= sessions.size || (i in 0 until current)) current = maxOf(0, current - 1)
        updateService(context)
        notifyChanged()
    }

    fun updateService(context: Context) {
        val intent = Intent(context, ConnectionService::class.java)
        if (sessions.isEmpty()) context.stopService(intent)
        else if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
}
