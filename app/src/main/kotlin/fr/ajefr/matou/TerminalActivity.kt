package fr.ajefr.matou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Écran des sessions ouvertes : barre d'onglets, terminal, touches supplémentaires. */
class TerminalActivity : Activity(), Prompter {
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var tabRow: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { ProfileStore(this) }
    private val changeListener: () -> Unit = { refreshTabs() }
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF16161E.toInt()) }

        // Barre d'onglets
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF14141C.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabRow)
        bar.addView(tabScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("+") { startActivity(Intent(this, SessionsActivity::class.java)) })
        val menuButton = barButton("⋮") {}
        menuButton.setOnClickListener { showMenu(it) }
        bar.addView(menuButton)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).toInt()))

        terminalView = TerminalView(this)
        root.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        extraKeys = ExtraKeysView(this, { terminalView.sendKey(it, 0) }, { terminalView.sendText(it) }, { toggleSwipeMode() })
        root.addView(extraKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        terminalView.extraModifiers = { extraKeys.modifiers }
        terminalView.consumeExtraModifiers = { extraKeys.consumeOneShot() }
        terminalView.onFontSizeChanged = { size ->
            currentSession()?.let { s -> s.profile.fontSize = size; saveProfileSettings(s) }
        }
        setContentView(root)
        handleIntent(intent)
    }

    private fun barButton(label: String, action: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PROFILE_ID)
        val quick = intent?.getStringExtra(EXTRA_QUICK)
        val profile = when {
            id != null -> store.get(id)?.also { store.touch(id) }
            quick != null -> Profile.parseQuick(quick)
            else -> null
        }
        intent?.removeExtra(EXTRA_PROFILE_ID)
        intent?.removeExtra(EXTRA_QUICK)
        if (profile != null) SessionManager.open(this, profile)
        if (SessionManager.sessions.isEmpty()) {
            startActivity(Intent(this, SessionsActivity::class.java))
            finish()
            return
        }
        selectTab(SessionManager.current)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        SessionManager.prompter = this
        SessionManager.addListener(changeListener)
        if (SessionManager.sessions.isEmpty()) { finish(); return }
        selectTab(SessionManager.current)
        terminalView.showKeyboard()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        SessionManager.removeListener(changeListener)
    }

    private fun currentSession() = SessionManager.sessions.getOrNull(SessionManager.current)

    private fun selectTab(index: Int) {
        val s = SessionManager.sessions.getOrNull(index) ?: return
        SessionManager.current = index
        if (terminalView.session !== s) terminalView.session = s
        s.onUpdate = { terminalView.invalidate(); refreshTabs() }
        extraKeys.setSwipeMode(s.profile.swipeSendsArrows)
        refreshTabs()
    }

    /** Bascule le glissement vertical entre historique du shell (↑/↓) et défilement du terminal. */
    private fun toggleSwipeMode() {
        val s = currentSession() ?: return
        s.profile.swipeSendsArrows = !s.profile.swipeSendsArrows
        extraKeys.setSwipeMode(s.profile.swipeSendsArrows)
        Toast.makeText(this,
            getString(if (s.profile.swipeSendsArrows) R.string.swipe_history else R.string.swipe_scroll),
            Toast.LENGTH_SHORT).show()
        saveProfileSettings(s)
    }

    private var tabSignature = ""

    private fun refreshTabs() {
        if (SessionManager.sessions.isEmpty()) { finish(); return }
        val sig = SessionManager.current.toString() + SessionManager.sessions.joinToString("|") { "${it.id}${it.state}${it.title}" }
        if (sig == tabSignature) return
        tabSignature = sig
        val dp = resources.displayMetrics.density
        tabRow.removeAllViews()
        SessionManager.sessions.forEachIndexed { i, s ->
            val selected = i == SessionManager.current
            val dot = when (s.state) {
                TerminalSession.State.CONNECTED -> "●"
                TerminalSession.State.CONNECTING -> "◌"
                TerminalSession.State.DISCONNECTED -> "○"
            }
            val tab = TextView(this).apply {
                text = "$dot ${s.title.take(24)}"
                setTextColor(if (selected) Color.WHITE else 0xFF9090A0.toInt())
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                setBackgroundColor(if (selected) 0xFF2A2A3A.toInt() else 0)
                setOnClickListener { selectTab(i) }
                setOnLongClickListener { confirmClose(s); true }
            }
            tabRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (terminalView.session !== currentSession()) currentSession()?.let { terminalView.session = it; it.onUpdate = { terminalView.invalidate(); refreshTabs() }; extraKeys.setSwipeMode(it.profile.swipeSendsArrows) }
    }

    private fun confirmClose(s: TerminalSession) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.close_tab_confirm, s.title))
            .setPositiveButton(R.string.close) { _, _ -> SessionManager.close(this, s) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val s = currentSession() ?: return
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, R.string.paste)
        m.add(0, 2, 1, R.string.menu_copy_history)
        m.add(0, 3, 2, R.string.menu_duplicate)
        m.add(0, 4, 3, R.string.menu_reconnect)
        m.add(0, 5, 4, R.string.menu_reset)
        val themes = m.addSubMenu(0, 6, 5, R.string.menu_theme)
        Themes.all.forEachIndexed { i, t -> themes.add(1, 100 + i, i, t.label(this)) }
        m.add(0, 7, 6, R.string.menu_bigger)
        m.add(0, 8, 7, R.string.menu_smaller)
        m.add(0, 9, 8, R.string.menu_close_tab)
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> terminalView.paste()
                2 -> Clipboard.copy(this, synchronized(s.terminal) { s.terminal.transcript() })
                3 -> SessionManager.open(this, s.profile.copy()).also { selectTab(SessionManager.current) }
                4 -> s.reconnect()
                5 -> { synchronized(s.terminal) { s.terminal.reset() }; terminalView.invalidate() }
                7 -> terminalView.setFontSize(terminalView.fontSize + 1)
                8 -> terminalView.setFontSize(terminalView.fontSize - 1)
                9 -> confirmClose(s)
                in 100 until 100 + Themes.all.size -> {
                    val t = Themes.all[item.itemId - 100]
                    s.profile.theme = t.name
                    s.applyTheme(t)
                    saveProfileSettings(s)
                    terminalView.invalidate()
                }
            }
            true
        }
        pm.show()
    }

    /** Enregistre thème et taille de police dans la session sauvegardée correspondante. */
    private fun saveProfileSettings(s: TerminalSession) {
        val saved = store.get(s.profile.id) ?: return
        saved.theme = s.profile.theme
        saved.fontSize = s.profile.fontSize
        saved.swipeSendsArrows = s.profile.swipeSendsArrows
        store.upsert(saved)
    }

    // ---------------------------------------------------------------- Prompter (threads réseau)
    private fun <T> onUi(default: T, block: (CountDownLatch, (T) -> Unit) -> Unit): T {
        if (!visible) return default
        val latch = CountDownLatch(1)
        var result = default
        main.post { block(latch) { result = it; latch.countDown() } }
        latch.await(5, TimeUnit.MINUTES)
        return result
    }

    override fun confirm(title: String, message: String): Boolean = onUi(false) { _, done ->
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> done(true) }
            .setNegativeButton(R.string.no) { _, _ -> done(false) }
            .setOnCancelListener { done(false) }
            .show()
    }

    override fun askSecret(title: String, message: String, secret: Boolean): String? = onUi<String?>(null) { _, done ->
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        if (secret) PasswordToggle.attach(input)
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> done(input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> done(null) }
            .setOnCancelListener { done(null) }
            .show()
        input.requestFocus()
    }

    override fun info(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_QUICK = "quick"
    }
}
