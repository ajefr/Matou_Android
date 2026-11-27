package fr.ajefr.matou

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import fr.ajefr.matou.core.LogFilter
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dossier choisi par l'utilisateur pour les journaux de session (accès via le sélecteur Android). */
object LogFolder {
    private const val PREFS = "matou"
    private const val KEY = "log_tree_uri"

    fun get(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val uri = Uri.parse(s)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (granted) uri else null
    }

    fun set(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
    }

    /** Nom lisible du dossier choisi, ou du dossier de secours dans le stockage de l'appli. */
    fun describe(context: Context): String {
        val uri = get(context) ?: return fallbackDir(context).absolutePath
        val id = DocumentsContract.getTreeDocumentId(uri)
        return id.substringAfter(':', id).ifEmpty { "/" }.let { if (id.startsWith("primary:")) "/$it" else id }
    }

    fun fallbackDir(context: Context): File =
        (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }

    fun pickIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
}

/**
 * Écrit la sortie d'une session dans un fichier, comme la journalisation de PuTTY / KiTTY :
 * texte lisible (séquences d'échappement retirées) ou sortie brute.
 * Appelé depuis le thread de lecture de la session.
 */
class SessionLogger private constructor(
    private val out: OutputStream,
    val fileName: String,
    val location: String,
    private val raw: Boolean,
) {
    private val filter = LogFilter()
    @Volatile var failed: Throwable? = null; private set

    @Synchronized
    fun write(data: ByteArray, off: Int, len: Int) {
        if (failed != null) return
        try {
            if (raw) out.write(data, off, len)
            else out.write(filter.filter(data, off, len).toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) { failed = e }
    }

    @Synchronized
    fun mark(text: String) {
        if (failed != null) return
        runCatching { out.write(text.toByteArray(Charsets.UTF_8)); out.flush() }.onFailure { failed = it }
    }

    @Synchronized
    fun flush() { runCatching { out.flush() } }

    @Synchronized
    fun close() { runCatching { out.flush(); out.close() } }

    companion object {
        const val MODE_OFF = 0
        const val MODE_TEXT = 1
        const val MODE_RAW = 2

        /** Crée le fichier journal de [profile] dans le dossier choisi, ou dans le dossier de secours. */
        fun open(context: Context, profile: Profile, mode: Int): SessionLogger {
            val now = Date()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
            val base = profile.displayName().replace(Regex("[^A-Za-z0-9._@-]+"), "_").take(60).ifEmpty { "session" }
            val name = "$base-$stamp.log"
            val tree = LogFolder.get(context)
            val (stream, location) = if (tree != null) {
                val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/octet-stream", name)
                    ?: throw Exception(context.getString(R.string.log_create_failed))
                context.contentResolver.openOutputStream(doc)!! to LogFolder.describe(context)
            } else {
                val dir = LogFolder.fallbackDir(context)
                File(dir, name).outputStream() to dir.absolutePath
            }
            val logger = SessionLogger(BufferedOutputStream(stream, 16 * 1024), name, location, mode == MODE_RAW)
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            logger.mark("=~=~=~= Matou log $date ${profile.protocol.name.lowercase()} ${profile.displayName()} (${profile.host}:${profile.port}) =~=~=~=\n")
            return logger
        }
    }
}
