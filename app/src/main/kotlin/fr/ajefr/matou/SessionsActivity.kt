package fr.ajefr.matou

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Écran d'accueil : la liste des sessions sauvegardées, comme la fenêtre de configuration de KiTTY. */
class SessionsActivity : Activity() {
    private val store by lazy { ProfileStore(this) }
    private var profiles = listOf<Profile>()
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.sessions_title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = TextView(this).apply {
            text = getString(R.string.sessions_empty)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        list = ListView(this)
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> connect(profiles[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> showActions(profiles[pos]); true }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        invalidateOptionsMenu()
    }

    private fun reload() {
        profiles = store.load().sortedWith(compareByDescending<Profile> { it.lastUsed }.thenBy { it.displayName().lowercase() })
        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = object : BaseAdapter() {
            override fun getCount() = profiles.size
            override fun getItem(i: Int) = profiles[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val p = profiles[i]
                val dp = resources.displayMetrics.density
                return LinearLayout(this@SessionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                    addView(TextView(context).apply { text = p.displayName(); textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
                    addView(TextView(context).apply {
                        text = p.summary
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                        alpha = 0.7f
                    })
                }
            }
        }
    }

    private fun connect(p: Profile) {
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_PROFILE_ID, p.id))
    }

    private fun showActions(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle(p.displayName())
            .setItems(arrayOf(getString(R.string.action_open), getString(R.string.action_edit), getString(R.string.action_duplicate), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> connect(p)
                    1 -> startActivity(Intent(this, EditSessionActivity::class.java).putExtra(EditSessionActivity.EXTRA_ID, p.id))
                    2 -> {
                        val copy = p.copy(id = java.util.UUID.randomUUID().toString(), name = getString(R.string.copy_suffix, p.displayName()), keyPath = "", lastUsed = 0)
                        // La clé est recopiée pour que chaque session garde la sienne.
                        if (p.keyPath.isNotEmpty() && java.io.File(p.keyPath).exists()) {
                            val dst = java.io.File(java.io.File(filesDir, "keys"), "${copy.id}.key")
                            java.io.File(p.keyPath).copyTo(dst, overwrite = true)
                            java.io.File(p.keyPath + ".pub").takeIf { it.exists() }?.copyTo(java.io.File(dst.path + ".pub"), true)
                            copy.keyPath = dst.absolutePath
                        }
                        store.upsert(copy)
                        reload()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.delete_confirm, p.displayName()))
                        .setPositiveButton(R.string.delete) { _, _ -> store.delete(p.id); reload() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun quickConnect() {
        val input = EditText(this).apply {
            hint = getString(R.string.quick_connect_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_connect)
            .setView(box)
            .setPositiveButton(R.string.connect) { _, _ ->
                val text = input.text.toString()
                if (Profile.parseQuick(text) != null) {
                    startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_QUICK, text))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    /** Ferme toutes les connexions, arrête le service et quitte l'application. */
    private fun quitApp() {
        val open = SessionManager.sessions.size
        fun doQuit() {
            SessionManager.closeAll(this)
            finishAndRemoveTask()
        }
        if (open == 0) { doQuit(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.quit_confirm_title)
            .setMessage(getString(R.string.quit_confirm_message, open))
            .setPositiveButton(R.string.quit) { _, _ -> doQuit() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val open = SessionManager.sessions.size
        if (open > 0) menu.add(0, 3, 0, getString(R.string.menu_open_sessions, open)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "⚡").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 1, 2, "＋").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 5, 3, getString(R.string.log_choose_folder)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 4, 4, getString(R.string.menu_quit)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, EditSessionActivity::class.java))
            2 -> quickConnect()
            3 -> startActivity(Intent(this, TerminalActivity::class.java))
            4 -> quitApp()
            5 -> @Suppress("DEPRECATION") startActivityForResult(LogFolder.pickIntent(), REQ_LOG_FOLDER)
        }
        return true
    }

    @Deprecated("API Activity simple, suffisante ici")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOG_FOLDER || resultCode != RESULT_OK) return
        data?.data?.let { uri ->
            runCatching { LogFolder.set(this, uri) }
            Toast.makeText(this, getString(R.string.log_folder_is, LogFolder.describe(this)), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_LOG_FOLDER = 1
    }
}
