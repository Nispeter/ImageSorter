package com.imagesorter.ui

import android.content.Context

/** Cuánto hay que deslizar una foto para decidir, como fracción del ancho. Se guarda en el teléfono. */
object SwipeSettings {
    const val DEFAULT = 0.2f
    const val MIN = 0.1f
    const val MAX = 0.5f
    private const val KEY = "swipe_threshold"

    fun load(context: Context): Float = prefs(context).getFloat(KEY, DEFAULT).coerceIn(MIN, MAX)

    fun save(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY, value.coerceIn(MIN, MAX)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
