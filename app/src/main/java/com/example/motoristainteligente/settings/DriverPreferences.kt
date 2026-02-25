package com.example.motoristainteligente

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

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

    /** Referência opcional ao FirestoreManager para sync automático */
    var firestoreManager: FirestoreManager? = null

    companion object {
        private const val PREFS_NAME = "driver_preferences"

        // Keys
        private const val KEY_MIN_PRICE_PER_KM = "min_price_per_km"
        private const val KEY_MIN_EARNINGS_PER_HOUR = "min_earnings_per_hour"
        private const val KEY_MAX_PICKUP_DISTANCE = "max_pickup_distance"
        private const val KEY_MAX_RIDE_DISTANCE = "max_ride_distance"
        private const val KEY_FIRST_SETUP_DONE = "first_setup_done"

        // Vehicle keys
        private const val KEY_VEHICLE_TYPE = "vehicle_type"          // "combustion" | "electric"
        private const val KEY_FUEL_TYPE = "fuel_type"                // "gasoline" | "ethanol"
        private const val KEY_KM_PER_LITER_GASOLINE = "km_per_liter_gasoline"
        private const val KEY_KM_PER_LITER_ETHANOL = "km_per_liter_ethanol"
        private const val KEY_GASOLINE_PRICE = "gasoline_price"
        private const val KEY_ETHANOL_PRICE = "ethanol_price"

        // Defaults
        const val DEFAULT_MIN_PRICE_PER_KM = 1.50
        const val DEFAULT_MIN_EARNINGS_PER_HOUR = 20.0
        const val DEFAULT_MAX_PICKUP_DISTANCE = 5.0
        const val DEFAULT_MAX_RIDE_DISTANCE = 50.0  // Desativado por padrão (valor alto)

        // Vehicle defaults
        const val DEFAULT_VEHICLE_TYPE = "combustion"
        const val DEFAULT_FUEL_TYPE = "gasoline"
        const val DEFAULT_KM_PER_LITER_GASOLINE = 10.0
        const val DEFAULT_KM_PER_LITER_ETHANOL = 7.0
        const val DEFAULT_GASOLINE_PRICE = 6.00
        const val DEFAULT_ETHANOL_PRICE = 4.00

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
    private var suppressCloudSync = false

    private fun currentUserScope(): String {
        return FirebaseAuth.getInstance().currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }

    private fun scopedKey(baseKey: String): String = "${baseKey}__${currentUserScope()}"

    private fun getScopedFloat(baseKey: String, defaultValue: Float): Float {
        val scoped = scopedKey(baseKey)
        if (prefs.contains(scoped)) return prefs.getFloat(scoped, defaultValue)

        if (prefs.contains(baseKey)) {
            val legacyValue = prefs.getFloat(baseKey, defaultValue)
            prefs.edit().putFloat(scoped, legacyValue).apply()
            return legacyValue
        }

        return defaultValue
    }

    private fun putScopedFloat(baseKey: String, value: Float) {
        prefs.edit().putFloat(scopedKey(baseKey), value).apply()
        onLocalPreferencesChanged()
    }

    private fun getScopedString(baseKey: String, defaultValue: String): String {
        val scoped = scopedKey(baseKey)
        if (prefs.contains(scoped)) {
            return prefs.getString(scoped, defaultValue) ?: defaultValue
        }

        if (prefs.contains(baseKey)) {
            val legacyValue = prefs.getString(baseKey, defaultValue) ?: defaultValue
            prefs.edit().putString(scoped, legacyValue).apply()
            return legacyValue
        }

        return defaultValue
    }

    private fun putScopedString(baseKey: String, value: String) {
        prefs.edit().putString(scopedKey(baseKey), value).apply()
        onLocalPreferencesChanged()
    }

    private fun getScopedBoolean(baseKey: String, defaultValue: Boolean): Boolean {
        val scoped = scopedKey(baseKey)
        if (prefs.contains(scoped)) return prefs.getBoolean(scoped, defaultValue)

        if (prefs.contains(baseKey)) {
            val legacyValue = prefs.getBoolean(baseKey, defaultValue)
            prefs.edit().putBoolean(scoped, legacyValue).apply()
            return legacyValue
        }

        return defaultValue
    }

    private fun putScopedBoolean(baseKey: String, value: Boolean) {
        prefs.edit().putBoolean(scopedKey(baseKey), value).apply()
        onLocalPreferencesChanged()
    }

    private fun onLocalPreferencesChanged() {
        if (suppressCloudSync) return
        syncToCloud()
    }

    fun runWithoutCloudSync(block: () -> Unit) {
        suppressCloudSync = true
        try {
            block()
        } finally {
            suppressCloudSync = false
        }
    }

    // ========================
    // Getters
    // ========================

    /** Valor mínimo aceitável por km (R$/km) */
    var minPricePerKm: Double
        get() = getScopedFloat(KEY_MIN_PRICE_PER_KM, DEFAULT_MIN_PRICE_PER_KM.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MIN_PRICE_PER_KM_FLOOR, MIN_PRICE_PER_KM_CEIL)
            putScopedFloat(KEY_MIN_PRICE_PER_KM, clamped.toFloat())
        }

    /** Ganho mínimo por hora desejado (R$/h) */
    var minEarningsPerHour: Double
        get() = getScopedFloat(KEY_MIN_EARNINGS_PER_HOUR, DEFAULT_MIN_EARNINGS_PER_HOUR.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MIN_EARNINGS_FLOOR, MIN_EARNINGS_CEIL)
            putScopedFloat(KEY_MIN_EARNINGS_PER_HOUR, clamped.toFloat())
        }

    /** Distância máxima para ir buscar o cliente (km) */
    var maxPickupDistance: Double
        get() = getScopedFloat(KEY_MAX_PICKUP_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MAX_PICKUP_FLOOR, MAX_PICKUP_CEIL)
            putScopedFloat(KEY_MAX_PICKUP_DISTANCE, clamped.toFloat())
        }

    /** Distância máxima da corrida (km) — filtro de corridas muito longas */
    var maxRideDistance: Double
        get() = getScopedFloat(KEY_MAX_RIDE_DISTANCE, DEFAULT_MAX_RIDE_DISTANCE.toFloat()).toDouble()
        set(value) {
            val clamped = value.coerceIn(MAX_RIDE_DISTANCE_FLOOR, MAX_RIDE_DISTANCE_CEIL)
            putScopedFloat(KEY_MAX_RIDE_DISTANCE, clamped.toFloat())
        }

    /** Se o usuário já fez a configuração inicial */
    var isFirstSetupDone: Boolean
        get() = getScopedBoolean(KEY_FIRST_SETUP_DONE, false)
        set(value) = putScopedBoolean(KEY_FIRST_SETUP_DONE, value)

    // ========================
    // Dados do Veículo
    // ========================

    /** Tipo do veículo: "combustion" ou "electric" */
    var vehicleType: String
        get() = getScopedString(KEY_VEHICLE_TYPE, DEFAULT_VEHICLE_TYPE)
        set(value) = putScopedString(KEY_VEHICLE_TYPE, value)

    /** Tipo de combustível preferido: "gasoline" ou "ethanol" */
    var fuelType: String
        get() = getScopedString(KEY_FUEL_TYPE, DEFAULT_FUEL_TYPE)
        set(value) = putScopedString(KEY_FUEL_TYPE, value)

    /** Km por litro com gasolina */
    var kmPerLiterGasoline: Double
        get() = getScopedFloat(KEY_KM_PER_LITER_GASOLINE, DEFAULT_KM_PER_LITER_GASOLINE.toFloat()).toDouble()
        set(value) = putScopedFloat(KEY_KM_PER_LITER_GASOLINE, value.coerceIn(3.0, 30.0).toFloat())

    /** Km por litro com etanol */
    var kmPerLiterEthanol: Double
        get() = getScopedFloat(KEY_KM_PER_LITER_ETHANOL, DEFAULT_KM_PER_LITER_ETHANOL.toFloat()).toDouble()
        set(value) = putScopedFloat(KEY_KM_PER_LITER_ETHANOL, value.coerceIn(2.0, 25.0).toFloat())

    /** Preço da gasolina (R$/L) */
    var gasolinePrice: Double
        get() = getScopedFloat(KEY_GASOLINE_PRICE, DEFAULT_GASOLINE_PRICE.toFloat()).toDouble()
        set(value) = putScopedFloat(KEY_GASOLINE_PRICE, value.coerceIn(2.0, 12.0).toFloat())

    /** Preço do etanol (R$/L) */
    var ethanolPrice: Double
        get() = getScopedFloat(KEY_ETHANOL_PRICE, DEFAULT_ETHANOL_PRICE.toFloat()).toDouble()
        set(value) = putScopedFloat(KEY_ETHANOL_PRICE, value.coerceIn(1.5, 9.0).toFloat())

    /** Custo por km do combustível atual */
    val fuelCostPerKm: Double
        get() {
            if (vehicleType == "electric") return 0.10  // ~R$0.10/km elétrico estimado
            return if (fuelType == "gasoline") {
                gasolinePrice / kmPerLiterGasoline
            } else {
                ethanolPrice / kmPerLiterEthanol
            }
        }

    /** Custo por km da gasolina */
    val gasolineCostPerKm: Double get() = gasolinePrice / kmPerLiterGasoline

    /** Custo por km do etanol */
    val ethanolCostPerKm: Double get() = ethanolPrice / kmPerLiterEthanol

    /** Indica se etanol compensa (regra: etanol < 70% gasolina ou custo/km menor) */
    val isEthanolBetter: Boolean get() = ethanolCostPerKm < gasolineCostPerKm

    /** Combustível recomendado */
    val recommendedFuel: String get() = if (isEthanolBetter) "ethanol" else "gasoline"

    /** Valor mínimo sugerido para aceitar corrida (cobre combustível + margem) */
    fun suggestedMinPricePerKm(avgRideKm: Double = 8.0): Double {
        val fuel = fuelCostPerKm
        val maintenance = 0.08  // R$0.08/km manutenção estimada
        val depreciacao = 0.07  // R$0.07/km depreciação estimada
        val totalCost = fuel + maintenance + depreciacao
        // Margem de ~50% sobre custo (cobre impostos + lucro)
        return totalCost * 1.5
    }

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

    fun syncToCloud() {
        if (firestoreManager?.isGoogleUser != true) return
        firestoreManager?.savePreferences(this)
    }

    /**
     * Restaura valores padrão.
     */
    fun resetToDefaults() {
        runWithoutCloudSync {
            minPricePerKm = DEFAULT_MIN_PRICE_PER_KM
            minEarningsPerHour = DEFAULT_MIN_EARNINGS_PER_HOUR
            maxPickupDistance = DEFAULT_MAX_PICKUP_DISTANCE
            maxRideDistance = DEFAULT_MAX_RIDE_DISTANCE
        }
        applyToAnalyzer()
        syncToCloud()
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
