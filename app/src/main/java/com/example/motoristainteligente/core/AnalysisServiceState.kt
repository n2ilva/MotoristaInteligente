package com.example.motoristainteligente

import android.content.Context

object AnalysisServiceState {
    private const val PREFS_NAME = "analysis_service_state"
    private const val KEY_ANALYSIS_ENABLED = "analysis_enabled"
    private const val KEY_ANALYSIS_PAUSED = "analysis_paused"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ANALYSIS_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ANALYSIS_ENABLED, enabled).apply()
        if (!enabled) {
            prefs.edit().putBoolean(KEY_ANALYSIS_PAUSED, false).apply()
        }
    }

    fun isPaused(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ANALYSIS_PAUSED, false)
    }

    fun setPaused(context: Context, paused: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ANALYSIS_PAUSED, paused).apply()
    }
}
