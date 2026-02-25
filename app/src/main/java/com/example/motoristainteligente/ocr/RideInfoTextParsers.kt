package com.example.motoristainteligente

object RideInfoTextParsers {

    fun parseRideDistanceFromText(
        text: String,
        pricePosition: Int,
        uberHourRoutePattern: Regex,
        distancePattern: Regex
    ): Double? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val hourMatch = uberHourRoutePattern.find(afterPrice)
        if (hourMatch != null) {
            val km = hourMatch.groupValues[3].replace(",", ".").toDoubleOrNull()
            if (km != null && km in 0.2..500.0) return km
        }

        val afterMatch = distancePattern.find(afterPrice)
        if (afterMatch != null) {
            val v = afterMatch.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null && v in 0.2..300.0) return v
        }

        val allMatches = distancePattern.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
            .filter { it in 0.2..300.0 }
            .toList()

        return if (allMatches.size >= 2) allMatches.maxOrNull() else allMatches.firstOrNull()
    }

    fun parseRideTimeFromText(
        text: String,
        pricePosition: Int,
        uberHourRoutePattern: Regex,
        minRangePattern: Regex,
        timePattern: Regex
    ): Int? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val hourMatch = uberHourRoutePattern.find(afterPrice)
        if (hourMatch != null) {
            val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatch.groupValues[2].toIntOrNull() ?: 0
            val totalMin = hours * 60 + mins
            if (totalMin in 1..600) return totalMin
        }

        val rangeMatch = minRangePattern.find(afterPrice)
        if (rangeMatch != null) {
            val max = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val simpleMatch = timePattern.find(afterPrice)
        val v = simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (v != null && v in 1..300) return v

        val hourMatchAll = uberHourRoutePattern.find(text)
        if (hourMatchAll != null) {
            val hours = hourMatchAll.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatchAll.groupValues[2].toIntOrNull() ?: 0
            val totalMin = hours * 60 + mins
            if (totalMin in 1..600) return totalMin
        }

        val allMatches = timePattern.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 1..300 }
            .toList()

        return if (allMatches.size >= 2) allMatches.maxOrNull() else allMatches.firstOrNull()
    }

    fun parseFirstKmValue(text: String, distancePattern: Regex): Double? {
        val match = distancePattern.find(text) ?: return null
        val v = match.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        return v.takeIf { it in 0.1..300.0 }
    }

    fun parseFirstMinValue(text: String, minRangePattern: Regex, timePattern: Regex): Int? {
        val rangeMatch = minRangePattern.find(text)
        val simpleMatch = timePattern.find(text)

        if (rangeMatch != null && (simpleMatch == null || rangeMatch.range.first <= simpleMatch.range.first)) {
            val max = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val v = simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return v.takeIf { it in 1..300 }
    }

    fun parseDistanceFromText(text: String, distancePattern: Regex): Double? {
        return distancePattern.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.2..300.0 }
    }

    fun parseMinutesFromText(text: String, minRangePattern: Regex, minValuePattern: Regex): Int? {
        val rangeMatch = minRangePattern.find(text)
        if (rangeMatch != null) {
            val values = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            val max = values.maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val minMatch = minValuePattern.find(text)
        val parsed = minMatch
            ?.value
            ?.let { Regex("""\d{1,3}""").find(it)?.value }
            ?.toIntOrNull()

        return parsed?.coerceIn(1, 300)
    }

    fun parsePickupDistanceFromText(
        text: String,
        pickupDistancePattern: Regex,
        pickupInlinePattern: Regex,
        pricePattern: Regex
    ): Double? {
        val explicit = pickupDistancePattern.find(text)
        if (explicit != null) {
            val v = explicit.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null && v in 0.1..50.0) return v
        }

        val priceIdx = pricePattern.find(text)?.range?.first ?: text.length
        val inlineMatches = pickupInlinePattern.findAll(text).toList()
        for (m in inlineMatches) {
            if (m.range.first < priceIdx) {
                val km = m.groupValues.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()
                if (km != null && km in 0.1..50.0) return km
            }
        }
        return null
    }

    fun parsePickupTimeFromText(
        text: String,
        pickupTimePattern: Regex,
        pickupInlinePattern: Regex,
        pricePattern: Regex
    ): Int? {
        val explicit = pickupTimePattern.find(text)
        if (explicit != null) {
            val v = explicit.groupValues.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 1..120) return v
        }

        val priceIdx = pricePattern.find(text)?.range?.first ?: text.length
        val inlineMatches = pickupInlinePattern.findAll(text).toList()
        for (m in inlineMatches) {
            if (m.range.first < priceIdx) {
                val min = m.groupValues.getOrNull(1)?.toIntOrNull()
                if (min != null && min in 1..120) return min
            }
        }
        return null
    }

    fun parseUserRatingFromText(text: String, userRatingPattern: Regex): Double? {
        val match = userRatingPattern.find(text) ?: return null
        val raw = match.groupValues.getOrNull(1).orEmpty().ifBlank {
            match.groupValues.getOrNull(2).orEmpty()
        }
        val rating = raw.replace(",", ".").toDoubleOrNull() ?: return null
        return rating.takeIf { it in 1.0..5.0 }
    }
}
