package com.example.motoristainteligente

import android.content.Context

object AnalysisServiceState {
    private const val PREFS_NAME = "analysis_service_state"
    private const val KEY_ANALYSIS_ENABLED = "analysis_enabled"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ANALYSIS_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ANALYSIS_ENABLED, enabled).apply()
    }

    fun isPaused(context: Context): Boolean {
        return false
    }

    fun setPaused(context: Context, paused: Boolean) {
        // Pausa removida: estado mantido sempre ativo quando habilitado
    }
}
