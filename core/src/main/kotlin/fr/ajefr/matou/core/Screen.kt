package fr.ajefr.matou.core

/** Une ligne de l'écran. Les tableaux sont publics pour un rendu rapide. */
class Line(cols: Int) {
    var cp = IntArray(cols)      // point de code ; 0 = vide ; WIDE_TAIL = 2e moitié d'un caractère large
    var fg = IntArray(cols)
    var bg = IntArray(cols)
    var attr = IntArray(cols)
    var wrapped = false          // la ligne continue sur la suivante (retour automatique)

    val cols get() = cp.size

    fun clear(from: Int, to: Int, bgColor: Int) {
        val end = minOf(to, cols)
        for (i in maxOf(0, from) until end) {
            cp[i] = 0; fg[i] = 0; bg[i] = bgColor; attr[i] = 0
        }
    }

    fun resize(n: Int) {
        if (n == cols) return
        cp = cp.copyOf(n); fg = fg.copyOf(n); bg = bg.copyOf(n); attr = attr.copyOf(n)
    }

    fun copyCell(from: Int, to: Int) {
        cp[to] = cp[from]; fg[to] = fg[from]; bg[to] = bg[from]; attr[to] = attr[from]
    }

    fun text(from: Int = 0, to: Int = cols, trimEnd: Boolean = true): String {
        val sb = StringBuilder()
        for (i in from until minOf(to, cols)) {
            val c = cp[i]
            when {
                c == WIDE_TAIL -> {}
                c == 0 -> sb.append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        return if (trimEnd) sb.trimEnd().toString() else sb.toString()
    }

    companion object {
        const val WIDE_TAIL = -1
    }
}

/** Tampon d'écran : lignes visibles + historique (scrollback). */
class Screen(var cols: Int, var rows: Int, private val maxScrollback: Int) {
    val lines = ArrayList<Line>(rows).apply { repeat(rows) { add(Line(cols)) } }
    val history = ArrayDeque<Line>()

    fun line(row: Int): Line = lines[row]

    /** Ligne à afficher : index négatif = historique (-1 = la plus récente). */
    fun displayLine(i: Int): Line? = when {
        i >= 0 -> lines.getOrNull(i)
        -i <= history.size -> history[history.size + i]
        else -> null
    }

    fun scrollUp(top: Int, bottom: Int, n: Int, bg: Int) {
        repeat(minOf(n, bottom - top + 1)) {
            val removed = lines.removeAt(top)
            val fresh = if (top == 0 && maxScrollback > 0) {
                history.addLast(removed)
                if (history.size > maxScrollback) history.removeFirst()
                Line(cols)
            } else removed
            fresh.wrapped = false
            fresh.clear(0, cols, bg)
            lines.add(bottom, fresh)
        }
    }

    fun scrollDown(top: Int, bottom: Int, n: Int, bg: Int) {
        repeat(minOf(n, bottom - top + 1)) {
            val l = lines.removeAt(bottom)
            l.wrapped = false
            l.clear(0, cols, bg)
            lines.add(top, l)
        }
    }

    fun clearHistory() = history.clear()

    /**
     * Redimensionne sans reflow. Quand on perd des lignes, celles du haut partent dans l'historique
     * pour garder le curseur visible. Retourne la nouvelle ligne du curseur.
     */
    fun resize(newCols: Int, newRows: Int, cursorRow: Int): Int {
        var cy = cursorRow
        for (l in lines) l.resize(newCols)
        while (lines.size > newRows) {
            if (cy < lines.size - 1 && lines.last().text().isEmpty()) {
                lines.removeAt(lines.size - 1)
            } else {
                val l = lines.removeAt(0)
                if (maxScrollback > 0) {
                    history.addLast(l)
                    if (history.size > maxScrollback) history.removeFirst()
                }
                cy--
            }
        }
        while (lines.size < newRows) {
            if (history.isNotEmpty() && maxScrollback > 0) {
                val l = history.removeLast(); l.resize(newCols); lines.add(0, l); cy++
            } else lines.add(Line(newCols))
        }
        cols = newCols
        rows = newRows
        return cy.coerceIn(0, newRows - 1)
    }
}
