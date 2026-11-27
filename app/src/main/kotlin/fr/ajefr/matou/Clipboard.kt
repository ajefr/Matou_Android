package fr.ajefr.matou

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object Clipboard {
    fun copy(context: Context, text: String) {
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Matou", text))
        Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
    }

    fun get(context: Context): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}
