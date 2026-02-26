package com.example.motoristainteligente

data class RideOfferCandidateSelection(
    val price: Double,
    val distanceKm: Double?,
    val estimatedTimeMin: Int?,
    val pickupDistanceKm: Double?,
    val pickupTimeMin: Int?,
    val userRating: Double?,
    val score: Int
)

object RideInfoOfferCandidateSelector {

    fun selectBestOfferCandidate(
        text: String,
        matches: List<MatchResult>,
        parsePriceFromMatch: (MatchResult) -> Double?,
        parseFirstKmValue: (String) -> Double?,
        parseFirstMinValue: (String) -> Int?,
        parsePickupDistanceFromText: (String) -> Double?,
        parsePickupTimeFromText: (String) -> Int?,
        parseUserRatingFromText: (String) -> Double?,
        actionKeywords: List<String>,
        contextKeywords: List<String>
    ): RideOfferCandidateSelection? {
        val candidates = matches.mapNotNull { match ->
            val parsedPrice = parsePriceFromMatch(match) ?: return@mapNotNull null

            val index = match.range.first
            val start = (index - 250).coerceAtLeast(0)
            val end = (match.range.last + 250).coerceAtMost(text.length - 1)
            val context = text.substring(start, end + 1)

            val afterPrice = text.substring(match.range.last + 1, text.length.coerceAtMost(match.range.last + 300))
            val beforePrice = text.substring((index - 200).coerceAtLeast(0), index)

            val distAfter = parseFirstKmValue(afterPrice)
            val distBefore = parseFirstKmValue(beforePrice)
            val distContext = parseFirstKmValue(context)
            val rideDistanceKm = distAfter ?: distContext

            val timeAfter = parseFirstMinValue(afterPrice)
            val timeBefore = parseFirstMinValue(beforePrice)
            val timeContext = parseFirstMinValue(context)
            val rideTimeMin = timeAfter ?: timeContext

            val pickupKm = distBefore.takeIf { distAfter != null && it != distAfter }
                ?: parsePickupDistanceFromText(text)
            val pickupMin = timeBefore.takeIf { timeAfter != null && it != timeAfter }
                ?: parsePickupTimeFromText(text)

            val rating = parseUserRatingFromText(context) ?: parseUserRatingFromText(text)

            val hasPlusPrice = Regex("""\+\s*R\$\s*\d""").containsMatchIn(context)
            val hasAction = actionKeywords.any { context.contains(it, ignoreCase = true) }
            val hasContext = contextKeywords.any { context.contains(it, ignoreCase = true) }

            val score =
                (if (rideDistanceKm != null) 3 else 0) +
                    (if (rideTimeMin != null) 3 else 0) +
                    (if (hasAction) 2 else 0) +
                    (if (hasContext) 2 else 0) +
                    (if (hasPlusPrice) 2 else 0) +
                    (if (rating != null) 1 else 0) +
                    (if (pickupKm != null) 1 else 0)

            RideOfferCandidateSelection(
                price = parsedPrice,
                distanceKm = rideDistanceKm,
                estimatedTimeMin = rideTimeMin,
                pickupDistanceKm = pickupKm,
                pickupTimeMin = pickupMin,
                userRating = rating,
                score = score
            )
        }

        return candidates.maxByOrNull { it.score }
    }
}