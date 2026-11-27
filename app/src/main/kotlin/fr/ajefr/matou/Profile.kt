package fr.ajefr.matou

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Protocol(val labelRes: Int, val defaultPort: Int) {
    SSH(R.string.proto_ssh, 22), TELNET(R.string.proto_telnet, 23), RAW(R.string.proto_raw, 23);

    fun label(context: Context) = context.getString(labelRes)
}

/** Une session sauvegardée, à la manière des sessions PuTTY/KiTTY. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var protocol: Protocol = Protocol.SSH,
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    /** Mot de passe chiffré avec le Keystore Android (vide = demander à la connexion). */
    var passwordEnc: String = "",
    /** Chemin de la clé privée copiée dans le stockage privé de l'appli. */
    var keyPath: String = "",
    var keyPassphraseEnc: String = "",
    /** Commandes envoyées après la connexion (une par ligne), comme l'« autocommand » de KiTTY. */
    var startupCommands: String = "",
    /** Auto-login Telnet : invites attendues. */
    var loginPrompt: String = "login:",
    var passwordPrompt: String = "password:",
    /** Redirections : « L [bind:]port:hôte:port » ou « R port:hôte:port », une par ligne. */
    var forwards: String = "",
    var keepAliveSec: Int = 30,
    var legacyAlgorithms: Boolean = false,
    var termType: String = "xterm-256color",
    var backspaceCtrlH: Boolean = false,
    /** Glissement vertical : true = envoie ↑/↓ (historique du shell), false = défile dans le terminal. */
    var swipeSendsArrows: Boolean = false,
    /** Journal de session : 0 = aucun, 1 = texte lisible, 2 = toute la sortie (brute). */
    var logMode: Int = 0,
    var theme: String = Themes.DEFAULT.name,
    var fontSize: Float = 13f,
    var lastUsed: Long = 0,
) {
    val summary: String
        get() = buildString {
            append(protocol.name.lowercase()).append("  ")
            if (username.isNotEmpty()) append(username).append('@')
            append(host)
            if (port != protocol.defaultPort) append(':').append(port)
        }

    fun displayName() = name.ifBlank { if (username.isNotEmpty()) "$username@$host" else host }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("protocol", protocol.name); put("host", host); put("port", port)
        put("username", username); put("passwordEnc", passwordEnc); put("keyPath", keyPath)
        put("keyPassphraseEnc", keyPassphraseEnc); put("startupCommands", startupCommands)
        put("loginPrompt", loginPrompt); put("passwordPrompt", passwordPrompt); put("forwards", forwards)
        put("keepAliveSec", keepAliveSec); put("legacyAlgorithms", legacyAlgorithms); put("termType", termType)
        put("backspaceCtrlH", backspaceCtrlH); put("swipeSendsArrows", swipeSendsArrows); put("logMode", logMode); put("theme", theme); put("fontSize", fontSize.toDouble())
        put("lastUsed", lastUsed)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.SSH),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            username = o.optString("username"),
            passwordEnc = o.optString("passwordEnc"),
            keyPath = o.optString("keyPath"),
            keyPassphraseEnc = o.optString("keyPassphraseEnc"),
            startupCommands = o.optString("startupCommands"),
            loginPrompt = o.optString("loginPrompt", "login:"),
            passwordPrompt = o.optString("passwordPrompt", "password:"),
            forwards = o.optString("forwards"),
            keepAliveSec = o.optInt("keepAliveSec", 30),
            legacyAlgorithms = o.optBoolean("legacyAlgorithms"),
            termType = o.optString("termType", "xterm-256color"),
            backspaceCtrlH = o.optBoolean("backspaceCtrlH"),
            swipeSendsArrows = o.optBoolean("swipeSendsArrows"),
            logMode = o.optInt("logMode", 0).coerceIn(0, 2),
            theme = o.optString("theme", Themes.DEFAULT.name),
            fontSize = o.optDouble("fontSize", 13.0).toFloat(),
            lastUsed = o.optLong("lastUsed"),
        )

        /** « user@hôte:port », « ssh://… » ou « telnet://… » pour la connexion rapide. */
        fun parseQuick(input: String): Profile? {
            var s = input.trim()
            if (s.isEmpty()) return null
            var proto = Protocol.SSH
            when {
                s.startsWith("ssh://") -> s = s.removePrefix("ssh://")
                s.startsWith("telnet://") -> { proto = Protocol.TELNET; s = s.removePrefix("telnet://") }
                s.startsWith("raw://") -> { proto = Protocol.RAW; s = s.removePrefix("raw://") }
            }
            s = s.trimEnd('/')
            val user = if ('@' in s) s.substringBeforeLast('@') else ""
            val hostPort = s.substringAfterLast('@')
            val host: String
            var port = proto.defaultPort
            if (hostPort.startsWith("[")) { // IPv6 [::1]:22
                host = hostPort.substringAfter('[').substringBefore(']')
                hostPort.substringAfter("]:", "").toIntOrNull()?.let { port = it }
            } else if (hostPort.count { it == ':' } == 1) {
                host = hostPort.substringBefore(':')
                hostPort.substringAfter(':').toIntOrNull()?.let { port = it }
            } else host = hostPort
            if (host.isBlank()) return null
            return Profile(protocol = proto, host = host, port = port, username = user)
        }
    }
}

/** Une redirection de port lue dans le profil. */
data class Forward(val remote: Boolean, val bindAddress: String, val bindPort: Int, val host: String, val hostPort: Int) {
    override fun toString() = if (remote) "R $bindPort → $host:$hostPort" else "L $bindAddress:$bindPort → $host:$hostPort"

    companion object {
        fun parseAll(text: String): List<Forward> = text.lines().mapNotNull { parse(it) }

        fun parse(line: String): Forward? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return null
            val remote = t.startsWith("R", ignoreCase = true)
            if (!remote && !t.startsWith("L", ignoreCase = true)) return null
            val parts = t.substring(1).trim().split(':')
            return when (parts.size) {
                3 -> Forward(remote, "127.0.0.1", parts[0].toIntOrNull() ?: return null, parts[1], parts[2].toIntOrNull() ?: return null)
                4 -> Forward(remote, parts[0], parts[1].toIntOrNull() ?: return null, parts[2], parts[3].toIntOrNull() ?: return null)
                else -> null
            }
        }
    }
}

/** Stockage des sessions dans un fichier JSON privé. */
class ProfileStore(private val context: Context) {
    private val file = File(context.filesDir, "sessions.json")

    fun load(): MutableList<Profile> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun saveAll(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
    }

    fun get(id: String) = load().firstOrNull { it.id == id }

    fun upsert(p: Profile) {
        val list = load()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        saveAll(list)
    }

    fun delete(id: String) {
        val list = load()
        list.firstOrNull { it.id == id }?.keyPath?.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        saveAll(list.filter { it.id != id })
    }

    fun touch(id: String) {
        val list = load()
        list.firstOrNull { it.id == id }?.let { it.lastUsed = System.currentTimeMillis(); saveAll(list) }
    }
}

/** Chiffrement des secrets (mots de passe, phrases de passe) avec une clé AES du Keystore Android. */
object SecretBox {
    private const val ALIAS = "matou-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return runCatching {
            val data = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
