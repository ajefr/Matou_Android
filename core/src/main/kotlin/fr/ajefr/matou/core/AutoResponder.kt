package fr.ajefr.matou.core

/**
 * Répond automatiquement à des invites (« login: », « Password: »…) dans l'ordre, une seule fois
 * chacune, comme l'auto-login Telnet de KiTTY. La recherche ignore la casse et les séquences ESC.
 */
class AutoResponder(private val rules: List<Pair<String, String>>, private val send: (String) -> Unit) {
    private var next = 0
    private val tail = StringBuilder()

    val done get() = next >= rules.size

    fun onText(text: String) {
        if (done) return
        tail.append(stripEscapes(text))
        if (tail.length > 512) tail.delete(0, tail.length - 512)
        while (!done) {
            val (trigger, response) = rules[next]
            val idx = tail.indexOf(trigger, ignoreCase = true)
            if (idx < 0) return
            tail.delete(0, idx + trigger.length)
            next++
            send(response)
        }
    }

    private fun stripEscapes(s: String) = s.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b[@-_]"), "")
}
