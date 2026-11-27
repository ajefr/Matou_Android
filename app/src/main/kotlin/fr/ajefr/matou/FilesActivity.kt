package fr.ajefr.matou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpProgressMonitor
import fr.ajefr.matou.core.Scp
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.Executors

/**
 * Transfert de fichiers sur la connexion SSH d'un onglet : navigateur SFTP (télécharger, envoyer,
 * renommer, supprimer, créer un dossier), avec repli SCP pour les serveurs sans SFTP.
 */
class FilesActivity : Activity() {
    private class Entry(val name: String, val attrs: SftpATTRS) {
        val isDir get() = attrs.isDir
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ssh: Session
    private var sftp: ChannelSftp? = null
    private var scpMode = false
    private var cwd = "."
    private var entries = listOf<Entry>()
    private var showHidden = false
    private var pendingDownload: String? = null
    private var pendingUploadDir: String? = null
    @Volatile private var cancelled = false

    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var scpBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_SESSION, -1)
        val session = SessionManager.sessions.firstOrNull { it.id == id }
        val s = session?.sshSession()
        if (s == null) {
            Toast.makeText(this, R.string.files_not_connected, Toast.LENGTH_LONG).show()
            finish(); return
        }
        ssh = s
        title = getString(R.string.files_title, session.title)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pathView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            setBackgroundColor(0xFF1E1E28.toInt())
            setOnClickListener { askPath() }
        }
        root.addView(pathView)
        status = TextView(this).apply {
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), 0)
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.7f
        }
        root.addView(status)
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scpBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            visibility = View.GONE
            addView(TextView(context).apply { setText(R.string.scp_explain); alpha = 0.8f })
            addView(Button(context).apply { setText(R.string.scp_download); setOnClickListener { askScpDownload() } })
            addView(Button(context).apply { setText(R.string.scp_upload); setOnClickListener { askScpUpload() } })
        }
        root.addView(scpBox)
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> open(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ -> if (pos > 0) showActions(entriesShown[pos - 1]); true }

        status.setText(R.string.files_loading)
        io.execute {
            try {
                val ch = ssh.openChannel("sftp") as ChannelSftp
                ch.connect(15_000)
                sftp = ch
                val home = ch.pwd()
                main.post { cd(home) }
            } catch (e: Throwable) {
                main.post { sftpUnavailable(e) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        io.execute { runCatching { sftp?.disconnect() } }
        io.shutdown()
    }

    // ---------------------------------------------------------------- Navigation SFTP

    private fun sftpUnavailable(e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_unavailable_title)
            .setMessage(getString(R.string.sftp_unavailable_message, e.message ?: e.javaClass.simpleName))
            .setPositiveButton(R.string.scp_use) { _, _ -> enterScpMode() }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun enterScpMode() {
        scpMode = true
        list.visibility = View.GONE
        status.visibility = View.GONE
        pathView.setText(R.string.scp_mode)
        pathView.setOnClickListener(null)
        scpBox.visibility = View.VISIBLE
        invalidateOptionsMenu()
    }

    private fun cd(path: String) {
        val ch = sftp ?: return
        status.visibility = View.VISIBLE
        status.setText(R.string.files_loading)
        io.execute {
            try {
                val target = ch.realpath(path)
                @Suppress("UNCHECKED_CAST")
                val raw = ch.ls(target) as java.util.Vector<ChannelSftp.LsEntry>
                val items = raw.filter { it.filename != "." && it.filename != ".." }
                    .map { Entry(it.filename, resolveLink(ch, target, it)) }
                    .sortedWith(compareBy<Entry> { !it.isDir }.thenBy { it.name.lowercase() })
                main.post {
                    cwd = target
                    entries = items
                    render()
                }
            } catch (e: Throwable) {
                main.post {
                    status.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    error(e)
                }
            }
        }
    }

    /** Un lien symbolique vers un dossier se parcourt comme un dossier. */
    private fun resolveLink(ch: ChannelSftp, dir: String, e: ChannelSftp.LsEntry): SftpATTRS =
        if (e.attrs.isLink) runCatching { ch.stat(child(dir, e.filename)) }.getOrDefault(e.attrs) else e.attrs

    private fun child(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    private fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    private fun visible() = if (showHidden) entries else entries.filter { !it.name.startsWith(".") }

    private fun render() {
        pathView.text = cwd
        val shown = visible()
        status.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        status.setText(R.string.files_empty)
        val dp = resources.displayMetrics.density
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size + 1
            override fun getItem(i: Int) = i
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View =
                LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                    if (i == 0) {
                        addView(TextView(context).apply { text = "📁  .."; textSize = 16f })
                        return@apply
                    }
                    val e = shown[i - 1]
                    addView(TextView(context).apply {
                        text = (if (e.isDir) "📁  " else "📄  ") + e.name
                        textSize = 16f
                    })
                    addView(TextView(context).apply {
                        val date = DateFormat.getMediumDateFormat(context).format(Date(e.attrs.mTime * 1000L)) + " " +
                            DateFormat.getTimeFormat(context).format(Date(e.attrs.mTime * 1000L))
                        text = (if (e.isDir) "" else Formatter.formatFileSize(context, e.attrs.size) + "  ·  ") +
                            date + "  ·  " + e.attrs.permissionsString
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        alpha = 0.6f
                        setPadding((28 * dp).toInt(), 0, 0, 0)
                    })
                }
        }
        entriesShown = shown
    }

    private var entriesShown = listOf<Entry>()

    private fun open(pos: Int) {
        if (pos == 0) { cd(parent(cwd)); return }
        val e = entriesShown[pos - 1]
        if (e.isDir) cd(child(cwd, e.name)) else download(child(cwd, e.name), e.name)
    }

    private fun showActions(e: Entry) {
        val path = child(cwd, e.name)
        val items = if (e.isDir) arrayOf(getString(R.string.files_open), getString(R.string.files_rename),
            getString(R.string.delete), getString(R.string.files_copy_path))
        else arrayOf(getString(R.string.files_download), getString(R.string.files_rename),
            getString(R.string.delete), getString(R.string.files_copy_path))
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (e.isDir) cd(path) else download(path, e.name)
                    1 -> rename(e)
                    2 -> confirmDelete(e)
                    3 -> Clipboard.copy(this, path)
                }
            }
            .show()
    }

    private fun askPath() {
        if (sftp == null) return
        prompt(getString(R.string.files_go_to), cwd) { cd(it) }
    }

    private fun rename(e: Entry) {
        prompt(getString(R.string.files_rename), e.name) { newName ->
            if (newName.isBlank() || '/' in newName) return@prompt
            run({ it.rename(child(cwd, e.name), child(cwd, newName)) }) { cd(cwd) }
        }
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm, e.name))
            .setMessage(if (e.isDir) getString(R.string.files_delete_dir_note) else null)
            .setPositiveButton(R.string.delete) { _, _ ->
                run({ ch -> if (e.isDir) ch.rmdir(child(cwd, e.name)) else ch.rm(child(cwd, e.name)) }) { cd(cwd) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        prompt(getString(R.string.files_new_folder), "") { name ->
            if (name.isBlank() || '/' in name) return@prompt
            run({ it.mkdir(child(cwd, name)) }) { cd(cwd) }
        }
    }

    /** Exécute une opération SFTP en arrière-plan puis [then] sur le thread principal. */
    private fun run(op: (ChannelSftp) -> Unit, then: () -> Unit) {
        val ch = sftp ?: return
        io.execute {
            try { op(ch); main.post(then) } catch (e: Throwable) { main.post { error(e) } }
        }
    }

    private fun error(e: Throwable) {
        if (isFinishing) return
        Toast.makeText(this, getString(R.string.files_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    private fun prompt(title: String, value: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(value); isSingleLine = true; setSelection(value.length) }
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- Transferts

    private fun download(remotePath: String, name: String) {
        pendingDownload = remotePath
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQ_SAVE)
    }

    private fun pickUpload(remoteDir: String) {
        pendingUploadDir = remoteDir
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_UPLOAD)
    }

    @Deprecated("API Activity simple, suffisante ici")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_SAVE -> {
                val remote = pendingDownload ?: return
                val uri = data.data ?: return
                transfer(getString(R.string.files_downloading, remote.substringAfterLast('/'))) { progress ->
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        if (scpMode) scpGet(remote, out, progress) else sftpGet(remote, out, progress)
                    }
                    getString(R.string.files_downloaded, remote.substringAfterLast('/'))
                }
            }
            REQ_UPLOAD -> {
                val dir = pendingUploadDir ?: return
                val uris = data.clipData?.let { c -> List(c.itemCount) { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                if (uris.isEmpty()) return
                transfer(resources.getQuantityString(R.plurals.files_uploading, uris.size, uris.size)) { progress ->
                    for (uri in uris) {
                        val (name, size) = describe(uri)
                        contentResolver.openInputStream(uri)!!.use { input ->
                            if (scpMode) scpPut(dir, name, size, input, progress)
                            else sftpPut(child(dir, name), size, input, progress)
                        }
                    }
                    resources.getQuantityString(R.plurals.files_uploaded, uris.size, uris.size)
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name.replace('/', '_') to size
    }

    /** Lance un transfert avec une fenêtre de progression annulable. */
    private fun transfer(label: String, work: (progress: (done: Long, total: Long) -> Boolean) -> String) {
        cancelled = false
        val dp = resources.displayMetrics.density
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val text = TextView(this).apply { this.text = label }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, 0)
            addView(text); addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()
        var last = 0L
        io.execute {
            val result = runCatching {
                work { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 150) {
                        last = now
                        main.post {
                            if (total > 0) { bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt() }
                            text.text = label + "\n" + Formatter.formatFileSize(this, done) +
                                (if (total > 0) " / " + Formatter.formatFileSize(this, total) else "")
                        }
                    }
                    !cancelled
                }
            }
            main.post {
                runCatching { dialog.dismiss() }
                result.onSuccess { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); if (!scpMode) cd(cwd) }
                result.onFailure { e ->
                    if (cancelled) Toast.makeText(this, R.string.files_cancelled, Toast.LENGTH_SHORT).show() else error(e)
                    if (!scpMode) cd(cwd)
                }
            }
        }
    }

    private fun monitor(progress: (Long, Long) -> Boolean, size: Long) = object : SftpProgressMonitor {
        var total = size
        var done = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) { if (max > 0) total = max }
        override fun count(count: Long): Boolean { done += count; return progress(done, total) }
        override fun end() {}
    }

    private fun sftpGet(remote: String, out: OutputStream, progress: (Long, Long) -> Boolean) {
        val ch = sftp ?: throw IllegalStateException()
        ch.get(remote, out, monitor(progress, -1))
        if (cancelled) throw Scp.Cancelled()
    }

    private fun sftpPut(remote: String, size: Long, input: InputStream, progress: (Long, Long) -> Boolean) {
        val ch = sftp ?: throw IllegalStateException()
        ch.put(input, remote, monitor(progress, size), ChannelSftp.OVERWRITE)
        if (cancelled) throw Scp.Cancelled()
    }

    // ---------------------------------------------------------------- SCP

    private fun askScpDownload() {
        prompt(getString(R.string.scp_remote_file), "") { path -> if (path.isNotBlank()) download(path, path.substringAfterLast('/')) }
    }

    private fun askScpUpload() {
        prompt(getString(R.string.scp_remote_dir), ".") { dir -> pickUpload(dir.ifBlank { "." }) }
    }

    private fun <T> exec(command: String, block: (InputStream, OutputStream) -> T): T {
        val ch = ssh.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val input = ch.inputStream
        val output = ch.outputStream
        ch.connect(15_000)
        try { return block(input, output) } finally { ch.disconnect() }
    }

    private fun scpGet(remote: String, out: OutputStream, progress: (Long, Long) -> Boolean) {
        var total = -1L
        exec(Scp.downloadCommand(remote)) { i, o ->
            Scp.receive(i, o, { _, size -> total = size; NonClosing(out) }, { progress(it, total) })
        }
    }

    private fun scpPut(dir: String, name: String, size: Long, input: InputStream, progress: (Long, Long) -> Boolean) {
        // SCP a besoin de la taille à l'avance : on lit le fichier en mémoire si le fournisseur ne la donne pas.
        val (data, length) = if (size >= 0) input to size else input.readBytes().let { it.inputStream() to it.size.toLong() }
        exec(Scp.uploadCommand(dir)) { i, o -> Scp.send(i, o, name, length, data) { progress(it, length) } }
    }

    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    // ---------------------------------------------------------------- Menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (scpMode) return false
        menu.add(0, 1, 0, "⬆").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, R.string.files_upload).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 2, R.string.files_new_folder).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 4, 3, R.string.files_refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 5, 4, R.string.files_go_to).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 6, 5, R.string.files_show_hidden).setCheckable(true).setChecked(showHidden)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 7, 6, R.string.scp_use).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> cd(parent(cwd))
            2 -> pickUpload(cwd)
            3 -> newFolder()
            4 -> cd(cwd)
            5 -> askPath()
            6 -> { showHidden = !showHidden; item.isChecked = showHidden; render() }
            7 -> enterScpMode()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        const val EXTRA_SESSION = "session"
        private const val REQ_SAVE = 1
        private const val REQ_UPLOAD = 2
    }
}
