package com.example.motoristainteligente

object RideInfoPriceParser {

    fun parsePriceFromMatch(match: MatchResult): Double? {
        val raw = match.groupValues.getOrNull(1)?.replace(",", ".") ?: return null
        return raw.toDoubleOrNull()
    }

    fun isAvgPerKmPriceMatch(
        text: String,
        match: MatchResult,
        avgPricePerKmSuffixPattern: Regex
    ): Boolean {
        val suffixStart = (match.range.last + 1).coerceAtMost(text.length)
        val suffixEnd = (suffixStart + 20).coerceAtMost(text.length)
        val suffix = text.substring(suffixStart, suffixEnd)

        if (avgPricePerKmSuffixPattern.containsMatchIn(suffix)) return true

        val prefixStart = (match.range.first - 16).coerceAtLeast(0)
        val prefix = text.substring(prefixStart, match.range.first)
        if (prefix.contains("média", ignoreCase = true) ||
            prefix.contains("media", ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    fun selectRidePriceMatch(
        text: String,
        appSource: AppSource,
        priceMatches: List<MatchResult>,
        minRidePrice: Double,
        avgPricePerKmSuffixPattern: Regex
    ): MatchResult? {
        if (priceMatches.isEmpty()) return null

        val validMatches = priceMatches.filter {
            parsePriceFromMatch(it)?.let { price -> price >= minRidePrice } == true
        }
        if (validMatches.isEmpty()) return null

        if (appSource != AppSource.NINETY_NINE) {
            return validMatches.maxByOrNull { parsePriceFromMatch(it) ?: 0.0 }
        }

        val rideMatches = validMatches.filterNot {
            isAvgPerKmPriceMatch(text, it, avgPricePerKmSuffixPattern)
        }
        if (rideMatches.isEmpty()) {
            return validMatches.maxByOrNull { parsePriceFromMatch(it) ?: 0.0 }
        }

        val hasAvgPerKmCompanion = validMatches.size > rideMatches.size
        return if (hasAvgPerKmCompanion) {
            rideMatches.minByOrNull { it.range.first }
        } else {
            rideMatches.maxByOrNull { parsePriceFromMatch(it) ?: 0.0 }
        }
    }

    fun parseFirstPriceFromMiddleThird(
        text: String,
        appSource: AppSource,
        minRidePrice: Double,
        pricePattern: Regex,
        avgPricePerKmSuffixPattern: Regex
    ): Double? {
        val lines = text
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.size < 3) return null

        val middleStart = lines.size / 3
        val middleEndExclusive = (lines.size * 2 / 3).coerceAtLeast(middleStart + 1)
        val middleLines = lines.subList(middleStart, middleEndExclusive)
        val middleText = middleLines.joinToString("\n")

        if (appSource == AppSource.UBER) {
            val uberMiddlePrice = Regex(
                """UberX[\s\S]{0,100}?R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
                RegexOption.IGNORE_CASE
            ).find(middleText)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
            if (uberMiddlePrice != null && uberMiddlePrice >= minRidePrice) return uberMiddlePrice
        }

        val matches = pricePattern.findAll(middleText).toList()
        if (matches.isEmpty()) return null

        val validMatches = matches.filter {
            parsePriceFromMatch(it)?.let { price -> price >= minRidePrice } == true
        }
        if (validMatches.isEmpty()) return null

        if (appSource == AppSource.NINETY_NINE) {
            val firstRidePrice = validMatches
                .firstOrNull { !isAvgPerKmPriceMatch(middleText, it, avgPricePerKmSuffixPattern) }
                ?.let { parsePriceFromMatch(it) }
            if (firstRidePrice != null) return firstRidePrice
        }

        return parsePriceFromMatch(validMatches.first())
    }

    fun parseCardPrice(
        text: String,
        appSource: AppSource,
        minRidePrice: Double,
        pricePattern: Regex,
        avgPricePerKmSuffixPattern: Regex,
        uberCardPattern: Regex,
        ninetyNineCorridaLongaPattern: Regex,
        ninetyNineNegociaPattern: Regex,
        ninetyNinePrioritarioPattern: Regex,
        ninetyNineAcceptPattern: Regex,
        ninetyNinePrioritarioSimplePattern: Regex
    ): Double? {
        val middleThirdPrice = parseFirstPriceFromMiddleThird(
            text = text,
            appSource = appSource,
            minRidePrice = minRidePrice,
            pricePattern = pricePattern,
            avgPricePerKmSuffixPattern = avgPricePerKmSuffixPattern
        )
        if (middleThirdPrice != null) return middleThirdPrice

        return when (appSource) {
            AppSource.UBER -> {
                val strict = uberCardPattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
                if (strict != null && strict >= minRidePrice) return strict

                Regex(
                    """UberX[\s\S]{0,120}?R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
                    RegexOption.IGNORE_CASE
                ).findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
            }

            AppSource.NINETY_NINE -> {
                val corridaLongaMax = ninetyNineCorridaLongaPattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
                val negociaMax = ninetyNineNegociaPattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
                val prioritarioMax = ninetyNinePrioritarioPattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
                val aceitarMax = ninetyNineAcceptPattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()
                val prioritarioSimpleMax = ninetyNinePrioritarioSimplePattern.findAll(text)
                    .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                    .filter { it >= minRidePrice }
                    .maxOrNull()

                val strict = listOfNotNull(
                    corridaLongaMax,
                    negociaMax,
                    prioritarioMax,
                    aceitarMax,
                    prioritarioSimpleMax
                ).maxOrNull()
                if (strict != null) return strict

                val genericMatches = pricePattern.findAll(text).toList()
                val selected = selectRidePriceMatch(
                    text = text,
                    appSource = appSource,
                    priceMatches = genericMatches,
                    minRidePrice = minRidePrice,
                    avgPricePerKmSuffixPattern = avgPricePerKmSuffixPattern
                )
                selected?.let { parsePriceFromMatch(it) }
            }

            else -> null
        }
    }

    fun parseHeaderRating(
        text: String,
        appSource: AppSource,
        uberHeaderRatingPattern: Regex,
        ninetyNineHeaderRatingPattern: Regex
    ): Double? {
        val pattern = when (appSource) {
            AppSource.UBER -> uberHeaderRatingPattern
            AppSource.NINETY_NINE -> ninetyNineHeaderRatingPattern
            else -> return null
        }
        val match = pattern.find(text) ?: return null
        val rating = match.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        return rating.takeIf { it in 1.0..5.0 }
    }
}