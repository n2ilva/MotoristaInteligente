package com.example.motoristainteligente

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gerencia o histórico de corridas aceitas pelo motorista.
 *
 * Persiste em SharedPreferences como JSON.
 * Funcionalidades:
 * - Grava corridas aceitas com análise
 * - Consulta corridas por dia / semana
 * - Cálculo de totais, médias, comparações
 */
class RideHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ride_history_prefs"
        private const val KEY_HISTORY = "ride_history_json"
        private const val MAX_ENTRIES = 500 // Últimas 500 corridas
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val history = CopyOnWriteArrayList<AcceptedRide>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Referência opcional ao FirestoreManager para sync automático */
    var firestoreManager: FirestoreManager? = null

    init {
        loadFromPrefs()
    }

    /**
     * Uma corrida que foi aceita e registrada.
     */
    data class AcceptedRide(
        val timestamp: Long,
        val appSource: String,
        val price: Double,
        val distanceKm: Double,
        val estimatedTimeMin: Int,
        val pricePerKm: Double,
        val earningsPerHour: Double,
        val score: Int,
        val recommendation: String,
        val pickupDistanceKm: Double
    ) {
        val date: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(timestamp)
        val time: String get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(timestamp)
        val dateDisplay: String get() = SimpleDateFormat("dd/MM", Locale.getDefault()).format(timestamp)
    }

    /**
     * Resumo estatístico de um período.
     */
    data class PeriodSummary(
        val totalRides: Int,
        val totalEarnings: Double,
        val totalDistanceKm: Double,
        val avgPricePerKm: Double,
        val avgEarningsPerHour: Double,
        val avgScore: Double,
        val bestRidePrice: Double,
        val worstRidePrice: Double,
        val avgRidePrice: Double,
        val totalTimeMin: Int,
        val rides: List<AcceptedRide>
    )

    // ========================
    // CRUD
    // ========================

    /**
     * Registra uma corrida aceita no histórico.
     */
    fun recordAcceptedRide(analysis: RideAnalysis) {
        val ride = AcceptedRide(
            timestamp = System.currentTimeMillis(),
            appSource = analysis.rideData.appSource.displayName,
            price = analysis.rideData.ridePrice,
            distanceKm = analysis.rideData.distanceKm,
            estimatedTimeMin = analysis.rideData.estimatedTimeMin,
            pricePerKm = analysis.pricePerKm,
            earningsPerHour = analysis.estimatedEarningsPerHour,
            score = analysis.score,
            recommendation = analysis.recommendation.displayText,
            pickupDistanceKm = analysis.pickupDistanceKm
        )
        history.add(ride)

        // Também registra no DemandTracker para ganhos de sessão
        DemandTracker.recordRideAccepted(ride.price)

        // Sync com Firestore
        firestoreManager?.saveRide(ride)

        // Limitar tamanho
        while (history.size > MAX_ENTRIES) {
            history.removeAt(0)
        }
        saveToPrefs()
    }

    /**
     * Registra uma corrida manualmente (dados simples).
     */
    fun recordRide(
        appSource: String,
        price: Double,
        distanceKm: Double,
        timeMin: Int,
        pricePerKm: Double,
        earningsPerHour: Double,
        score: Int,
        pickupDistanceKm: Double = 0.0
    ) {
        val ride = AcceptedRide(
            timestamp = System.currentTimeMillis(),
            appSource = appSource,
            price = price,
            distanceKm = distanceKm,
            estimatedTimeMin = timeMin,
            pricePerKm = pricePerKm,
            earningsPerHour = earningsPerHour,
            score = score,
            recommendation = if (score >= 60) "COMPENSA" else if (score >= 40) "NEUTRO" else "EVITAR",
            pickupDistanceKm = pickupDistanceKm
        )
        history.add(ride)
        while (history.size > MAX_ENTRIES) {
            history.removeAt(0)
        }
        saveToPrefs()
    }

    /**
     * Remove todo o histórico.
     */
    fun clearHistory() {
        history.clear()
        saveToPrefs()
    }

    // ========================
    // Consultas
    // ========================

    /** Todas as corridas */
    fun getAll(): List<AcceptedRide> = history.toList().sortedByDescending { it.timestamp }

    /** Corridas de hoje */
    fun getToday(): List<AcceptedRide> {
        val today = dateFormat.format(System.currentTimeMillis())
        return history.filter { it.date == today }.sortedByDescending { it.timestamp }
    }

    /** Corridas dos últimos 7 dias */
    fun getLastWeek(): List<AcceptedRide> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val weekAgo = cal.timeInMillis
        return history.filter { it.timestamp >= weekAgo }.sortedByDescending { it.timestamp }
    }

    /** Corridas de uma data específica */
    fun getByDate(date: String): List<AcceptedRide> {
        return history.filter { it.date == date }.sortedByDescending { it.timestamp }
    }

    /** Resumo de um período */
    fun getSummary(rides: List<AcceptedRide>): PeriodSummary {
        if (rides.isEmpty()) {
            return PeriodSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, emptyList())
        }
        return PeriodSummary(
            totalRides = rides.size,
            totalEarnings = rides.sumOf { it.price },
            totalDistanceKm = rides.sumOf { it.distanceKm },
            avgPricePerKm = rides.map { it.pricePerKm }.average(),
            avgEarningsPerHour = rides.map { it.earningsPerHour }.average(),
            avgScore = rides.map { it.score.toDouble() }.average(),
            bestRidePrice = rides.maxOf { it.price },
            worstRidePrice = rides.minOf { it.price },
            avgRidePrice = rides.map { it.price }.average(),
            totalTimeMin = rides.sumOf { it.estimatedTimeMin },
            rides = rides
        )
    }

    /** Resumo de hoje */
    fun getTodaySummary(): PeriodSummary = getSummary(getToday())

    /** Resumo da semana */
    fun getWeekSummary(): PeriodSummary = getSummary(getLastWeek())

    /**
     * Ganhos por dia nos últimos 7 dias (para gráfico de comparação).
     */
    fun getDailyEarningsLast7Days(): List<Pair<String, Double>> {
        val cal = Calendar.getInstance()
        val result = mutableListOf<Pair<String, Double>>()
        val dayNameFormat = SimpleDateFormat("EEE", Locale("pt", "BR"))
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dateStr = dateFormat.format(cal.time)
            val dayName = dayNameFormat.format(cal.time).replaceFirstChar { it.uppercase() }
            val total = history.filter { it.date == dateStr }.sumOf { it.price }
            result.add(Pair(dayName, total))
        }
        return result
    }

    /**
     * Número de corridas por dia nos últimos 7 dias.
     */
    fun getDailyRidesLast7Days(): List<Pair<String, Int>> {
        val cal = Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()
        val dayNameFormat = SimpleDateFormat("EEE", Locale("pt", "BR"))
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dateStr = dateFormat.format(cal.time)
            val dayName = dayNameFormat.format(cal.time).replaceFirstChar { it.uppercase() }
            val count = history.count { it.date == dateStr }
            result.add(Pair(dayName, count))
        }
        return result
    }

    /**
     * Resumo diário para cada um dos últimos 7 dias.
     * Retorna lista de (nomeAbreviadoDoDia, dataBR, PeriodSummary).
     */
    fun getDailySummariesLast7Days(): List<Triple<String, String, PeriodSummary>> {
        val cal = Calendar.getInstance()
        val result = mutableListOf<Triple<String, String, PeriodSummary>>()
        val dayNameFormat = SimpleDateFormat("EEEE", Locale("pt", "BR"))
        val displayDateFormat = SimpleDateFormat("dd/MM", Locale("pt", "BR"))
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dateStr = dateFormat.format(cal.time)
            val dayName = dayNameFormat.format(cal.time).replaceFirstChar { it.uppercase() }
            val dateBR = displayDateFormat.format(cal.time)
            val dayRides = history.filter { it.date == dateStr }
            result.add(Triple(dayName, dateBR, getSummary(dayRides)))
        }
        return result
    }

    /** Total de corridas registradas */
    fun count(): Int = history.size

    // ========================
    // Persistência
    // ========================

    private fun saveToPrefs() {
        try {
            val jsonArray = JSONArray()
            history.forEach { ride ->
                jsonArray.put(JSONObject().apply {
                    put("timestamp", ride.timestamp)
                    put("appSource", ride.appSource)
                    put("price", ride.price)
                    put("distanceKm", ride.distanceKm)
                    put("estimatedTimeMin", ride.estimatedTimeMin)
                    put("pricePerKm", ride.pricePerKm)
                    put("earningsPerHour", ride.earningsPerHour)
                    put("score", ride.score)
                    put("recommendation", ride.recommendation)
                    put("pickupDistanceKm", ride.pickupDistanceKm)
                })
            }
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun loadFromPrefs() {
        try {
            val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                history.add(
                    AcceptedRide(
                        timestamp = obj.getLong("timestamp"),
                        appSource = obj.getString("appSource"),
                        price = obj.getDouble("price"),
                        distanceKm = obj.getDouble("distanceKm"),
                        estimatedTimeMin = obj.getInt("estimatedTimeMin"),
                        pricePerKm = obj.getDouble("pricePerKm"),
                        earningsPerHour = obj.getDouble("earningsPerHour"),
                        score = obj.getInt("score"),
                        recommendation = obj.getString("recommendation"),
                        pickupDistanceKm = obj.optDouble("pickupDistanceKm", 0.0)
                    )
                )
            }
        } catch (_: Exception) { }
    }
}
