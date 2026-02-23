package com.example.motoristainteligente

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as preferências personalizadas do motorista.
 *
 * Permite que o motorista configure:
 * - Valor mínimo por km aceitável
 * - Ganho mínimo por hora desejado
 * - Distância máxima para deslocamento vazio (ir buscar o cliente)
 * - Distância máxima da corrida (opcional)
 *
 * Os valores são persistidos em SharedPreferences e aplicados
 * automaticamente nas análises do RideAnalyzer.
 */
class DriverPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "driver_preferences"

        // Keys
        private const val KEY_MIN_PRICE_PER_KM = "min_price_per_km"
        private const val KEY_MIN_EARNINGS_PER_HOUR = "min_earnings_per_hour"
        private const val KEY_MAX_PICKUP_DISTANCE = "max_pickup_distance"
        private const val KEY_MAX_RIDE_DISTANCE = "max_ride_distance"
        private const val KEY_FIRST_SETUP_DONE = "first_setup_done"

        // Defaults
        const val DEFAULT_MIN_PRICE_PER_KM = 1.50
        const val DEFAULT_MIN_EARNINGS_PER_HOUR = 20.0
        const val DEFAULT_MAX_PICKUP_DISTANCE = 5.0
        const val DEFAULT_MAX_RIDE_DISTANCE = 50.0  // Desativado por padrão (valor alto)

        // Limites para validação
        const val MIN_PRICE_PER_KM_FLOOR = 0.50
        const val MIN_PRICE_PER_KM_CEIL = 5.00
        const val MIN_EARNINGS_FLOOR = 5.0
        const val MIN_EARNINGS_CEIL = 100.0
        const val MAX_PICKUP_FLOOR = 0.5
        const val MAX_PICKUP_CEIL = 20.0
        const val MAX_RIDE_DISTANCE_FLOOR = 1.0
        const val MAX_RIDE_DISTANCE_CEIL = 100.0
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========================
    // Getters
    // ========================

    /** Valor mínimo aceitável por km (R$/km) */
    var minPricePerKm: Double
        get() = prefs.getFloat(KEY_MIN_PRICE_PER_KM, DEFAULT_MIN_PRICE_PER_KM.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MIN_PRICE_PER_KM_FLOOR, MIN_PRICE_PER_KM_CEIL)
            prefs.edit().putFloat(KEY_MIN_PRICE_PER_KM, clamped.toFloat()).apply()
        }

    /** Ganho mínimo por hora desejado (R$/h) */
    var minEarningsPerHour: Double
        get() = prefs.getFloat(KEY_MIN_EARNINGS_PER_HOUR, DEFAULT_MIN_EARNINGS_PER_HOUR.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MIN_EARNINGS_FLOOR, MIN_EARNINGS_CEIL)
            prefs.edit().putFloat(KEY_MIN_EARNINGS_PER_HOUR, clamped.toFloat()).apply()
        }

    /** Distância máxima para ir buscar o cliente (km) */
    var maxPickupDistance: Double
        get() = prefs.getFloat(KEY_MAX_PICKUP_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MAX_PICKUP_FLOOR, MAX_PICKUP_CEIL)
            prefs.edit().putFloat(KEY_MAX_PICKUP_DISTANCE, clamped.toFloat()).apply()
        }

    /** Distância máxima da corrida (km) — filtro de corridas muito longas */
    var maxRideDistance: Double
        get() = prefs.getFloat(KEY_MAX_RIDE_DISTANCE, DEFAULT_MAX_RIDE_DISTANCE.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MAX_RIDE_DISTANCE_FLOOR, MAX_RIDE_DISTANCE_CEIL)
            prefs.edit().putFloat(KEY_MAX_RIDE_DISTANCE, clamped.toFloat()).apply()
        }

    /** Se o usuário já fez a configuração inicial */
    var isFirstSetupDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_SETUP_DONE, value).apply()

    // ========================
    // Helpers
    // ========================

    /**
     * Aplica as preferências do motorista no RideAnalyzer.
     * Chamado em onCreate e quando preferências mudam.
     */
    fun applyToAnalyzer() {
        RideAnalyzer.updateReferences(
            newPricePerKm = minPricePerKm,
            newMinHourly = minEarningsPerHour
        )
        RideAnalyzer.updatePickupLimit(maxPickupDistance, maxRideDistance)
    }

    /**
     * Restaura valores padrão.
     */
    fun resetToDefaults() {
        minPricePerKm = DEFAULT_MIN_PRICE_PER_KM
        minEarningsPerHour = DEFAULT_MIN_EARNINGS_PER_HOUR
        maxPickupDistance = DEFAULT_MAX_PICKUP_DISTANCE
        maxRideDistance = DEFAULT_MAX_RIDE_DISTANCE
        applyToAnalyzer()
    }

    /**
     * Retorna um resumo textual das configurações.
     */
    fun getSummary(): String {
        return buildString {
            append("R$ ${String.format("%.2f", minPricePerKm)}/km min")
            append(" • R$ ${String.format("%.0f", minEarningsPerHour)}/h min")
            append(" • ${String.format("%.1f", maxPickupDistance)}km pickup max")
        }
    }
}
