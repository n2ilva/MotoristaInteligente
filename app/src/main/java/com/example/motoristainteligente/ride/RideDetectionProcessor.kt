package com.example.motoristainteligente

import android.location.Location

data class RideDetectionContext(
    val rideData: RideData,
    val currentLocation: Location?,
    val lastResolvedCity: String?,
    val lastResolvedNeighborhood: String?,
    val fallbackRegionName: String?,
    val gpsPickupDistanceKm: Double,
    val minPricePerKmReference: Double
)

data class RideDetectionResult(
    val analysis: RideAnalysis,
    val city: String?,
    val neighborhood: String?
)

fun processRideDetection(
    context: RideDetectionContext,
    resolveRegion: (latitude: Double, longitude: Double) -> ResolvedRegion?
): RideDetectionResult {
    var city: String? = null
    var neighborhood: String? = null

    val location = context.currentLocation
    if (location != null) {
        val resolved = resolveRegion(location.latitude, location.longitude)
        city = resolved?.city
        neighborhood = resolved?.neighborhood
    }

    if (city.isNullOrBlank()) {
        city = context.lastResolvedCity ?: context.fallbackRegionName
        neighborhood = neighborhood ?: context.lastResolvedNeighborhood
    }

    val pickupDistance = context.rideData.pickupDistanceKm ?: context.gpsPickupDistanceKm
    val isLimitedData = context.rideData.rawText.startsWith("LIMITED_DATA_NO_PRICE")

    val analysis = if (isLimitedData) {
        RideAnalysis(
            rideData = context.rideData,
            pricePerKm = 0.0f,
            effectivePricePerKm = 0.0f,
            referencePricePerKm = context.minPricePerKmReference,
            estimatedEarningsPerHour = 0.0f,
            pickupDistanceKm = pickupDistance,
            score = 50,
            recommendation = Recommendation.NEUTRAL,
            reasons = listOf("Dados limitados: Uber/99 não expôs preço/distância acessíveis nesta oferta")
        )
    } else {
        RideAnalyzer.analyze(context.rideData, pickupDistance)
    }

    return RideDetectionResult(
        analysis = analysis,
        city = city,
        neighborhood = neighborhood
    )
}
