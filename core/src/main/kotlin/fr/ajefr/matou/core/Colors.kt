package fr.ajefr.matou.core

/**
 * Encodage compact des couleurs de cellule dans un Int :
 *  - 0                        : couleur par défaut (fg ou bg du thème)
 *  - 0x01000000 or index      : couleur de la palette 256 couleurs
 *  - 0x02000000 or 0xRRGGBB   : couleur vraie (truecolor)
 */
object Colors {
    const val DEFAULT = 0
    private const val INDEXED = 0x01000000
    private const val RGB = 0x02000000
    private const val TYPE_MASK = 0xFF000000.toInt()

    fun indexed(i: Int) = INDEXED or (i and 0xFF)
    fun rgb(r: Int, g: Int, b: Int) = RGB or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isDefault(c: Int) = c == DEFAULT
    fun isIndexed(c: Int) = (c and TYPE_MASK) == INDEXED
    fun isRgb(c: Int) = (c and TYPE_MASK) == RGB
    fun index(c: Int) = c and 0xFF

    /** Palette xterm 256 couleurs standard (ARGB) ; les 16 premières sont remplacées par le thème. */
    fun defaultPalette(): IntArray {
        val p = IntArray(256)
        val base16 = intArrayOf(
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
        )
        for (i in 0 until 16) p[i] = argb(base16[i])
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 16 until 232) {
            val n = i - 16
            p[i] = argb((steps[n / 36] shl 16) or (steps[(n / 6) % 6] shl 8) or steps[n % 6])
        }
        for (i in 232 until 256) {
            val v = 8 + (i - 232) * 10
            p[i] = argb((v shl 16) or (v shl 8) or v)
        }
        return p
    }

    fun argb(rgb: Int) = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
}

/** Bits d'attributs de cellule. */
object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
}
