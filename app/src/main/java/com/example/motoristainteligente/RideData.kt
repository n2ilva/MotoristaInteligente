package com.example.motoristainteligente

/**
 * Dados extraídos de uma corrida do Uber ou 99
 */
data class RideData(
    val appSource: AppSource,
    val ridePrice: Double,
    val distanceKm: Double,
    val estimatedTimeMin: Int,
    val pickupDistanceKm: Double? = null,
    val pickupTimeMin: Int? = null,
    val userRating: Double? = null,
    val extractionSource: String = "unknown",
    val pickupAddress: String = "",
    val dropoffAddress: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val rawText: String = ""
)

/**
 * Fonte do aplicativo de corrida
 */
enum class AppSource(val displayName: String) {
    UBER("UBER"),
    NINETY_NINE("99"),
    UNKNOWN("Desconhecido")
}

/**
 * Resultado da análise de uma corrida
 */
data class RideAnalysis(
    val rideData: RideData,
    val pricePerKm: Double,
    val effectivePricePerKm: Double,
    val referencePricePerKm: Double,
    val estimatedEarningsPerHour: Double,
    val pickupDistanceKm: Double,
    val score: Int,
    val recommendation: Recommendation,
    val reasons: List<String>
)

/**
 * Recomendação da análise
 */
enum class Recommendation(val displayText: String, val emoji: String) {
    WORTH_IT("COMPENSA", "✅"),
    NOT_WORTH_IT("NÃO COMPENSA", "❌"),
    NEUTRAL("NEUTRO", "⚠️")
}
