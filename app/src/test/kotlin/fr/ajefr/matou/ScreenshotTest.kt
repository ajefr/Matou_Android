package fr.ajefr.matou

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Génère les captures d'écran de la documentation, sans appareil ni émulateur, grâce au rendu
 * graphique natif de Robolectric. Lancer avec MATOU_SCREENSHOTS=dossier ./gradlew :app:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w400dp-h860dp-xhdpi")
class ScreenshotTest {
    private val outDir = System.getenv("MATOU_SCREENSHOTS")?.let { File(it) }

    private fun save(view: View, name: String) {
        val dir = outDir ?: return
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sampleProfiles() {
        val store = ProfileStore(org.robolectric.RuntimeEnvironment.getApplication())
        store.saveAll(listOf(
            Profile(name = "Home server", host = "192.168.1.10", username = "alex", lastUsed = 5),
            Profile(name = "Raspberry Pi", host = "raspberrypi.local", username = "pi", lastUsed = 4,
                forwards = "L 8080:localhost:80"),
            Profile(name = "VPS (Debian)", host = "vps.example.com", port = 2222, username = "root", lastUsed = 3),
            Profile(name = "Router", protocol = Protocol.TELNET, host = "192.168.1.1", port = 23, username = "admin", lastUsed = 2),
            Profile(name = "ser2net console", protocol = Protocol.RAW, host = "10.0.0.5", port = 4001, lastUsed = 1),
        ))
    }

    private fun demoSession(theme: Theme): TerminalSession {
        val p = Profile(name = "alex@home-server", host = "192.168.1.10", username = "alex", theme = theme.name, fontSize = 11f)
        val s = TerminalSession(org.robolectric.RuntimeEnvironment.getApplication(), p)
        val e = "\u001b"
        val demo = buildString {
            append("${e}[2mSSH: connecting to 192.168.1.10:22…${e}[0m\r\n")
            append("${e}[36mForwarding active: L 127.0.0.1:8080 → localhost:80${e}[0m\r\n")
            append("Linux home-server 6.8.0-49-generic x86_64\r\n\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ ls --color\r\n")
            append("${e}[1;34mbackups${e}[0m  ${e}[1;34mdocker${e}[0m  ${e}[1;32mdeploy.sh${e}[0m  notes.md  ${e}[1;31marchive.tar.gz${e}[0m\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ git log --oneline -4\r\n")
            append("${e}[33ma1c9e2f${e}[0m (${e}[1;36mHEAD -> ${e}[1;32mmain${e}[0m) Add swipe mode\r\n")
            append("${e}[33m7d41b0a${e}[0m Bilingual interface\r\n")
            append("${e}[33m3fe8c17${e}[0m Port forwarding\r\n")
            append("${e}[33m0b2d9e4${e}[0m Initial commit\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ df -h /\r\n")
            append("Filesystem      Size  Used Avail Use% Mounted on\r\n")
            append("/dev/nvme0n1p2  468G  191G  254G  ${e}[33m43%${e}[0m /\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ systemctl status nginx | head -3\r\n")
            append("${e}[1;32m●${e}[0m nginx.service - A high performance web server\r\n")
            append("     Loaded: loaded (nginx.service; ${e}[1;32menabled${e}[0m)\r\n")
            append("     Active: ${e}[1;32mactive (running)${e}[0m since Fri 09:12:44 CET\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ ./colors.sh\r\n")
            for (i in 0..7) append("${e}[4${i}m    ")
            append("${e}[0m\r\n")
            for (i in 0..7) append("${e}[10${i}m    ")
            append("${e}[0m\r\n")
            for (i in 0 until 32) append("${e}[48;2;${i * 8};${120 - i * 3};${255 - i * 8}m ")
            append("${e}[0m\r\n")
            append("${e}[1;32malex@home-server${e}[0m:${e}[1;34m~${e}[0m\$ ")
        }
        s.terminal.feed(demo)
        return s
    }

    private fun tui(): TerminalSession {
        val p = Profile(name = "htop@vps", host = "vps.example.com", username = "root", theme = "Dracula", fontSize = 11f)
        val s = TerminalSession(org.robolectric.RuntimeEnvironment.getApplication(), p)
        val e = "\u001b"
        val b = StringBuilder("${e}[?1049h${e}[H${e}[2J")
        fun bar(label: String, pct: Int, color: Int) {
            val n = pct * 20 / 100
            b.append("  ${e}[36m$label${e}[0m[${e}[3${color}m" + "|".repeat(n) + "${e}[0m" + " ".repeat(20 - n) + "${e}[2m$pct%${e}[0m]\r\n")
        }
        bar("CPU0 ", 37, 2); bar("CPU1 ", 64, 2); bar("CPU2 ", 12, 2); bar("CPU3 ", 81, 1)
        bar("Mem  ", 52, 4); bar("Swp  ", 3, 1)
        b.append("  ${e}[36mTasks:${e}[0m ${e}[1m142${e}[0m, ${e}[1m389${e}[0m thr; ${e}[1;32m2${e}[0m running\r\n")
        b.append("  ${e}[36mLoad average:${e}[0m 0.84 0.67 0.59\r\n\r\n")
        b.append("${e}[30;42m  PID USER     CPU% MEM%  Command".padEnd(74) + "${e}[0m\r\n")
        val rows = listOf(
            "  812 www-data 24.1  3.2  nginx: worker", " 1204 postgres 11.7  8.9  postgres: writer",
            " 2210 root      6.3  1.1  dockerd", "  977 alex      2.0  0.4  tmux: server",
            " 3341 alex      1.2  0.2  htop", "    1 root      0.0  0.3  /sbin/init")
        rows.forEachIndexed { i, r -> b.append(if (i == 0) "${e}[30;46m${r.padEnd(66)}${e}[0m\r\n" else "$r\r\n") }
        b.append("${e}[30m${e}[46mF1${e}[0mHelp ${e}[30;46mF2${e}[0mSetup ${e}[30;46mF3${e}[0mSearch ${e}[30;46mF9${e}[0mKill ${e}[30;46mF10${e}[0mQuit")
        s.terminal.feed(b.toString())
        return s
    }

    private fun terminalShot(sessions: List<TerminalSession>, current: Int, name: String) {
        SessionManager.sessions.clear()
        SessionManager.sessions += sessions
        SessionManager.current = current
        val ctl = Robolectric.buildActivity(TerminalActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val root = ctl.get().window.decorView
        save(root, name)
        ctl.pause().stop().destroy()
        SessionManager.sessions.clear()
    }

    @Test fun sessionsScreen() {
        assumeTrue(outDir != null)
        sampleProfiles()
        val ctl = Robolectric.buildActivity(SessionsActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        save(ctl.get().window.decorView, "01-sessions")
    }

    @Test @Config(qualifiers = "en-w400dp-h720dp-xhdpi") fun terminalScreen() {
        assumeTrue(outDir != null)
        terminalShot(listOf(demoSession(Themes.DEFAULT), tui()), 0, "02-terminal")
    }

    @Test @Config(qualifiers = "en-w400dp-h720dp-xhdpi") fun tuiScreen() {
        assumeTrue(outDir != null)
        terminalShot(listOf(demoSession(Themes.DEFAULT), tui()), 1, "03-htop")
    }

    @Test @Config(qualifiers = "en-w400dp-h720dp-xhdpi") fun puttyTheme() {
        assumeTrue(outDir != null)
        terminalShot(listOf(demoSession(Themes.byName("PuTTY classique"))), 0, "04-putty-theme")
    }

    @Test fun editorScreen() {
        assumeTrue(outDir != null)
        sampleProfiles()
        val id = ProfileStore(org.robolectric.RuntimeEnvironment.getApplication()).load()[1].id
        val intent = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), EditSessionActivity::class.java)
            .putExtra(EditSessionActivity.EXTRA_ID, id)
        val ctl = Robolectric.buildActivity(EditSessionActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        save(ctl.get().window.decorView, "05-editor")
    }

    @Test @Config(qualifiers = "fr-w400dp-h860dp-xhdpi") fun editorScreenFrench() {
        assumeTrue(outDir != null)
        sampleProfiles()
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val id = ProfileStore(app).load()[1].id
        val intent = android.content.Intent(app, EditSessionActivity::class.java).putExtra(EditSessionActivity.EXTRA_ID, id)
        val ctl = Robolectric.buildActivity(EditSessionActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        save(ctl.get().window.decorView, "06-editor-fr")
    }
}
