package fr.ajefr.matou

import android.annotation.SuppressLint
import android.text.InputType
import android.view.MotionEvent
import android.widget.EditText

/** Ajoute une icône œil à droite d'un champ mot de passe pour afficher / masquer la saisie. */
object PasswordToggle {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(field: EditText) {
        var visible = false
        fun apply() {
            val sel = field.selectionEnd
            field.inputType = InputType.TYPE_CLASS_TEXT or
                if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye, 0)
            field.compoundDrawablePadding = (8 * field.resources.displayMetrics.density).toInt()
            field.contentDescription = if (visible) "Masquer le mot de passe" else "Afficher le mot de passe"
            if (sel >= 0) field.setSelection(minOf(sel, field.text.length))
        }
        apply()
        field.setOnTouchListener { v, e ->
            val icon = field.compoundDrawablesRelative[2] ?: return@setOnTouchListener false
            val rtl = field.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val hit = if (rtl) e.x <= field.paddingStart + icon.bounds.width() + field.compoundDrawablePadding
                      else e.x >= field.width - field.paddingEnd - icon.bounds.width() - field.compoundDrawablePadding
            if (!hit) return@setOnTouchListener false
            if (e.action == MotionEvent.ACTION_UP) {
                visible = !visible
                apply()
                v.performClick()
            }
            true
        }
    }
}
