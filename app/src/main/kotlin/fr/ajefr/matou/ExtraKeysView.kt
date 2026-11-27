package fr.ajefr.matou

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import fr.ajefr.matou.core.Key
import fr.ajefr.matou.core.KeyEncoder

/** Barre de touches absentes des claviers tactiles : Échap, Ctrl, Alt, Tab, flèches, F1-F12… */
class ExtraKeysView(
    context: Context,
    private val onKey: (Key) -> Unit,
    private val onText: (String) -> Unit,
    private val onSwipeToggle: () -> Unit,
) :
    HorizontalScrollView(context) {

    private var ctrl = 0      // 0 = off, 1 = une fois, 2 = verrouillé
    private var alt = 0
    private lateinit var ctrlView: TextView
    private lateinit var altView: TextView
    private lateinit var swipeView: TextView

    /** Affiche le mode du glissement vertical : historique (↑/↓) ou défilement du terminal. */
    fun setSwipeMode(arrows: Boolean) {
        swipeView.text = context.getString(if (arrows) R.string.key_swipe_history else R.string.key_swipe_scroll)
        swipeView.setBackgroundColor(if (arrows) 0xFF3A5A40.toInt() else 0)
    }

    val modifiers: Int
        get() = (if (ctrl > 0) KeyEncoder.CTRL else 0) or (if (alt > 0) KeyEncoder.ALT else 0)

    fun consumeOneShot() {
        if (ctrl == 1) ctrl = 0
        if (alt == 1) alt = 0
        refresh()
    }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0xFF1E1E28.toInt())
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addView(row)
        fun button(label: String, action: () -> Unit, long: (() -> Unit)? = null): TextView {
            val dp = resources.displayMetrics.density
            return TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = (44 * dp).toInt()
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() }
                if (long != null) setOnLongClickListener { long(); true }
                row.addView(this)
            }
        }
        swipeView = button(context.getString(R.string.key_swipe_scroll), { onSwipeToggle() })
        button(context.getString(R.string.key_esc), { onKey(Key.ESCAPE) })
        ctrlView = button("Ctrl", { ctrl = if (ctrl == 0) 1 else 0; refresh() }, { ctrl = 2; refresh() })
        altView = button("Alt", { alt = if (alt == 0) 1 else 0; refresh() }, { alt = 2; refresh() })
        button("Tab", { onKey(Key.TAB) })
        button("←", { onKey(Key.LEFT) })
        button("↓", { onKey(Key.DOWN) })
        button("↑", { onKey(Key.UP) })
        button("→", { onKey(Key.RIGHT) })
        button(context.getString(R.string.key_home), { onKey(Key.HOME) })
        button(context.getString(R.string.key_end), { onKey(Key.END) })
        button(context.getString(R.string.key_pgup), { onKey(Key.PAGE_UP) })
        button(context.getString(R.string.key_pgdn), { onKey(Key.PAGE_DOWN) })
        for (c in listOf("|", "-", "/", "~", "\\", "*", "&", ">", "<", ":")) button(c, { onText(c) })
        button(context.getString(R.string.key_del), { onKey(Key.DELETE) })
        for (i in 1..12) button("F$i", { onKey(Key.entries[Key.F1.ordinal + i - 1]) })
        refresh()
    }

    private fun refresh() {
        fun style(v: TextView, state: Int) {
            v.setBackgroundColor(when (state) { 1 -> 0xFF4A4A66.toInt(); 2 -> 0xFFB86C30.toInt(); else -> 0 })
        }
        style(ctrlView, ctrl)
        style(altView, alt)
    }
}
