package fr.ajefr.matou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

/** Formulaire de création / modification d'une session sauvegardée. */
class EditSessionActivity : Activity() {
    private lateinit var profile: Profile
    private val store by lazy { ProfileStore(this) }
    private var keyPath = ""

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_session)
        JschSetup.init()
        val id = intent.getStringExtra(EXTRA_ID)
        profile = id?.let { store.get(it) } ?: Profile()
        title = getString(if (id == null) R.string.edit_new else R.string.edit_edit)
        keyPath = profile.keyPath

        val protocol = v<Spinner>(R.id.protocol)
        protocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Protocol.entries.map { it.label(this) })
        protocol.setSelection(profile.protocol.ordinal)
        protocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, rowId: Long) {
                val p = Protocol.entries[pos]
                val portField = v<EditText>(R.id.port)
                if (!first || portField.text.isEmpty()) {
                    val current = portField.text.toString().toIntOrNull()
                    if (current == null || Protocol.entries.any { it.defaultPort == current }) portField.setText(p.defaultPort.toString())
                }
                first = false
                updateVisibility(p)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val theme = v<Spinner>(R.id.theme)
        theme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Themes.all.map { it.label(this) })
        theme.setSelection(Themes.all.indexOfFirst { it.name == profile.theme }.coerceAtLeast(0))

        v<EditText>(R.id.name).setText(profile.name)
        v<EditText>(R.id.host).setText(profile.host)
        v<EditText>(R.id.port).setText(profile.port.toString())
        v<EditText>(R.id.username).setText(profile.username)
        v<EditText>(R.id.password).setText(SecretBox.decrypt(profile.passwordEnc))
        v<EditText>(R.id.passphrase).setText(SecretBox.decrypt(profile.keyPassphraseEnc))
        v<EditText>(R.id.startup).setText(profile.startupCommands)
        v<EditText>(R.id.loginPrompt).setText(profile.loginPrompt)
        v<EditText>(R.id.passwordPrompt).setText(profile.passwordPrompt)
        v<EditText>(R.id.forwards).setText(profile.forwards)
        v<EditText>(R.id.termType).setText(profile.termType)
        v<EditText>(R.id.fontSize).setText(profile.fontSize.toString())
        v<EditText>(R.id.keepAlive).setText(profile.keepAliveSec.toString())
        v<CheckBox>(R.id.backspace).isChecked = profile.backspaceCtrlH
        v<CheckBox>(R.id.swipeArrows).isChecked = profile.swipeSendsArrows
        v<CheckBox>(R.id.legacy).isChecked = profile.legacyAlgorithms
        updateKeyStatus()
        PasswordToggle.attach(v(R.id.password))
        PasswordToggle.attach(v(R.id.passphrase))

        v<Button>(R.id.pickKey).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_KEY)
        }
        v<Button>(R.id.genKey).setOnClickListener { generateKey() }
        v<Button>(R.id.removeKey).setOnClickListener { keyPath = ""; updateKeyStatus() }
        v<Button>(R.id.save).setOnClickListener { if (save()) finish() }
        v<Button>(R.id.saveConnect).setOnClickListener {
            if (save()) {
                startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_PROFILE_ID, profile.id))
                finish()
            }
        }
    }

    private fun updateVisibility(p: Protocol) {
        val ssh = p == Protocol.SSH
        for (id in intArrayOf(R.id.pickKey, R.id.genKey, R.id.removeKey, R.id.passphrase, R.id.keyStatus,
            R.id.forwards, R.id.forwardsTitle, R.id.legacy)) {
            v<View>(id).visibility = if (ssh) View.VISIBLE else View.GONE
        }
        v<View>(R.id.telnetBox).visibility = if (p == Protocol.TELNET) View.VISIBLE else View.GONE
    }

    private fun keyDir() = File(filesDir, "keys").apply { mkdirs() }

    private fun updateKeyStatus() {
        v<TextView>(R.id.keyStatus).text = if (keyPath.isNotEmpty() && File(keyPath).exists()) {
            val pub = File("$keyPath.pub")
            getString(if (pub.exists()) R.string.key_set_with_pub else R.string.key_set, File(keyPath).name)
        } else getString(R.string.key_none)
        v<TextView>(R.id.keyStatus).setOnLongClickListener {
            val pub = File("$keyPath.pub")
            if (pub.exists()) Clipboard.copy(this, pub.readText().trim())
            true
        }
    }

    @Deprecated("API Activity simple, suffisante ici")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_KEY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > 64 * 1024) throw Exception(getString(R.string.key_too_big))
            // Vérifie que JSch sait lire la clé avant de l'enregistrer.
            KeyPair.load(JSch(), bytes, null).dispose()
            val f = File(keyDir(), "${profile.id}.key")
            f.writeBytes(bytes)
            File(f.path + ".pub").delete()
            keyPath = f.absolutePath
            updateKeyStatus()
        }.onFailure { Toast.makeText(this, getString(R.string.key_unreadable, it.message), Toast.LENGTH_LONG).show() }
    }

    private fun generateKey() {
        runCatching {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
            val priv = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "matou@android") }.toByteArray()
            kp.dispose()
            val f = File(keyDir(), "${profile.id}.key")
            f.writeBytes(priv)
            File(f.path + ".pub").writeBytes(pub)
            keyPath = f.absolutePath
            updateKeyStatus()
            val pubText = String(pub).trim()
            AlertDialog.Builder(this)
                .setTitle(R.string.key_generated_title)
                .setMessage(getString(R.string.key_generated_message, pubText))
                .setPositiveButton(R.string.copy) { _, _ -> Clipboard.copy(this, pubText) }
                .setNegativeButton(R.string.close, null)
                .show()
        }.onFailure { Toast.makeText(this, getString(R.string.key_generate_failed, it.message), Toast.LENGTH_LONG).show() }
    }

    private fun save(): Boolean {
        val host = v<EditText>(R.id.host).text.toString().trim()
        if (host.isEmpty()) { v<EditText>(R.id.host).error = getString(R.string.error_host_required); return false }
        val proto = Protocol.entries[v<Spinner>(R.id.protocol).selectedItemPosition]
        val badForward = v<EditText>(R.id.forwards).text.lines()
            .firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") && Forward.parse(it) == null }
        if (proto == Protocol.SSH && badForward != null) {
            v<EditText>(R.id.forwards).error = getString(R.string.error_bad_forward, badForward)
            return false
        }
        profile.apply {
            name = v<EditText>(R.id.name).text.toString().trim()
            protocol = proto
            this.host = host
            port = v<EditText>(R.id.port).text.toString().toIntOrNull() ?: proto.defaultPort
            username = v<EditText>(R.id.username).text.toString().trim()
            passwordEnc = SecretBox.encrypt(v<EditText>(R.id.password).text.toString())
            keyPath = this@EditSessionActivity.keyPath
            keyPassphraseEnc = SecretBox.encrypt(v<EditText>(R.id.passphrase).text.toString())
            startupCommands = v<EditText>(R.id.startup).text.toString()
            loginPrompt = v<EditText>(R.id.loginPrompt).text.toString()
            passwordPrompt = v<EditText>(R.id.passwordPrompt).text.toString()
            forwards = v<EditText>(R.id.forwards).text.toString()
            termType = v<EditText>(R.id.termType).text.toString().trim().ifEmpty { "xterm-256color" }
            fontSize = v<EditText>(R.id.fontSize).text.toString().toFloatOrNull()?.coerceIn(6f, 40f) ?: 13f
            keepAliveSec = v<EditText>(R.id.keepAlive).text.toString().toIntOrNull() ?: 30
            backspaceCtrlH = v<CheckBox>(R.id.backspace).isChecked
            swipeSendsArrows = v<CheckBox>(R.id.swipeArrows).isChecked
            legacyAlgorithms = v<CheckBox>(R.id.legacy).isChecked
            theme = Themes.all[v<Spinner>(R.id.theme).selectedItemPosition].name
        }
        store.upsert(profile)
        return true
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_KEY = 1
    }
}
