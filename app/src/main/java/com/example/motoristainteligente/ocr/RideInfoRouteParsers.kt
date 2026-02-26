package com.example.motoristainteligente

import android.util.Log

data class OcrRoutePairExtraction(
    val price: Double?,
    val rideDistanceKm: Double?,
    val pickupDistanceKm: Double?,
    val userRating: Double?,
    val confidence: Int,
    val source: String
)

data class PositionalDisambiguation(
    val rideDistanceKm: Double?,
    val rideTimeMin: Int?,
    val pickupDistanceKm: Double?,
    val pickupTimeMin: Int?,
    val confidence: Int,
    val source: String
)

object RideInfoRouteParsers {

    fun parseOcrRoutePairs(
        text: String,
        minRidePrice: Double,
        routePairPattern: Regex,
        routePairMetersPattern: Regex,
        kmInParenPattern: Regex,
        metersInParenPattern: Regex,
        kmValuePattern: Regex,
        pricePattern: Regex,
        userRatingPattern: Regex,
        tag: String
    ): OcrRoutePairExtraction? {
        val routePairs = routePairPattern.findAll(text).toList()
        val routePairsMeters = routePairMetersPattern.findAll(text).toList()
        val parenthesizedKm = kmInParenPattern.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
            .filter { it in 0.1..300.0 }
            .toList()
        val parenthesizedMeters = metersInParenPattern.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull()?.div(1000.0) }
            .filter { it in 0.01..2.0 }
            .toList()

        parenthesizedKm.forEachIndexed { idx, km ->
            Log.d(tag, "OCR km-parenteses[$idx]: $km")
        }
        parenthesizedMeters.forEachIndexed { idx, m ->
            Log.d(tag, "OCR metros-parenteses[$idx]: ${String.format("%.3f", m)}km")
        }

        var pickupKm: Double? = null
        var rideKm: Double? = null

        if (parenthesizedKm.size >= 2) {
            pickupKm = parenthesizedKm[0]
            rideKm = parenthesizedKm[1]
        } else if (parenthesizedKm.size == 1 && parenthesizedMeters.isNotEmpty()) {
            pickupKm = parenthesizedMeters[0]
            rideKm = parenthesizedKm[0]
        } else if (routePairsMeters.isNotEmpty() && routePairs.isNotEmpty()) {
            pickupKm = routePairsMeters.first().groupValues[2].toDoubleOrNull()?.div(1000.0)
            rideKm = routePairs.first().groupValues[2].replace(",", ".").toDoubleOrNull()
        } else if (routePairs.size >= 2) {
            pickupKm = routePairs[0].groupValues[2].replace(",", ".").toDoubleOrNull()
            rideKm = routePairs[1].groupValues[2].replace(",", ".").toDoubleOrNull()
        } else if (parenthesizedKm.size == 1 || routePairs.size == 1) {
            pickupKm = parenthesizedKm.firstOrNull()
                ?: routePairs.firstOrNull()?.groupValues?.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()

            val tailStart = routePairs.firstOrNull()?.range?.last?.plus(1) ?: 0
            val tail = text.substring(tailStart.coerceAtMost(text.length))
            val secondKm = kmValuePattern.findAll(tail)
                .mapNotNull {
                    it.value
                        .replace("km", "", ignoreCase = true)
                        .trim()
                        .replace(",", ".")
                        .toDoubleOrNull()
                }
                .firstOrNull { km ->
                    val p = pickupKm ?: 0.0
                    km > p + 0.2 && km in 0.5..300.0
                }
            if (secondKm != null) {
                rideKm = secondKm
            }
        }

        if (rideKm == null && pickupKm == null) {
            Log.d(tag, "OCR route pairs: sem km suficiente para pickup/corrida")
            return null
        }

        val priceMatch = pricePattern.findAll(text)
            .mapNotNull { m ->
                val v = m.groupValues[1].replace(",", ".").toDoubleOrNull()
                v?.takeIf { it >= minRidePrice }
            }
            .maxOrNull()

        val ratingMatch = userRatingPattern.find(text)
        val rating = ratingMatch?.let {
            (it.groupValues[1].takeIf { g -> g.isNotEmpty() }
                ?: it.groupValues[2].takeIf { g -> g.isNotEmpty() }
                ?: it.groupValues.getOrNull(3)?.takeIf { g -> g.isNotEmpty() })
                ?.replace(",", ".")?.toDoubleOrNull()
        }

        var confidence = 0
        if (rideKm != null) confidence += 2
        if (pickupKm != null) confidence++
        if (priceMatch != null) confidence++
        if (rating != null) confidence++

        Log.d(
            tag,
            "OCR route pairs: pickup=($pickupKm km), ride=($rideKm km), price=$priceMatch, rating=$rating, conf=$confidence"
        )

        if (confidence < 4) return null

        return OcrRoutePairExtraction(
            price = priceMatch,
            rideDistanceKm = rideKm,
            pickupDistanceKm = pickupKm,
            userRating = rating,
            confidence = confidence,
            source = "ocr-route-pairs"
        )
    }

    fun disambiguateByPosition(
        text: String,
        pricePosition: Int,
        uberHourRoutePattern: Regex,
        distancePattern: Regex,
        minRangePattern: Regex,
        timePattern: Regex
    ): PositionalDisambiguation {
        val beforePrice = if (pricePosition > 0) text.substring(0, pricePosition) else ""
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val pickupKm = RideInfoTextParsers.parseFirstKmValue(beforePrice, distancePattern)
        val pickupMin = RideInfoTextParsers.parseFirstMinValue(beforePrice, minRangePattern, timePattern)

        val hourMatch = uberHourRoutePattern.find(afterPrice)
        val rideKm: Double?
        val rideMin: Int?
        if (hourMatch != null) {
            val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatch.groupValues[2].toIntOrNull() ?: 0
            rideMin = hours * 60 + mins
            rideKm = hourMatch.groupValues[3].replace(",", ".").toDoubleOrNull()
        } else {
            rideKm = RideInfoTextParsers.parseFirstKmValue(afterPrice, distancePattern)
            rideMin = RideInfoTextParsers.parseFirstMinValue(afterPrice, minRangePattern, timePattern)
        }

        var confidence = 0
        if (rideKm != null) confidence++
        if (rideMin != null) confidence++
        if (pickupKm != null) confidence++
        if (pickupMin != null) confidence++

        return PositionalDisambiguation(
            rideDistanceKm = rideKm,
            rideTimeMin = rideMin,
            pickupDistanceKm = pickupKm,
            pickupTimeMin = pickupMin,
            confidence = confidence,
            source = "positional"
        )
    }
}