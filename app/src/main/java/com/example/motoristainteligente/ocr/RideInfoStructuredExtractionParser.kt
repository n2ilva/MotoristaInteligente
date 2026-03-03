package com.example.motoristainteligente

data class StructuredNodeInput(
    val idSuffix: String,
    val combinedText: String,
    val traversalIndex: Int
)

data class StructuredExtractionResult(
    val price: Double?,
    val rideDistanceKm: Double?,
    val rideTimeMin: Int?,
    val pickupDistanceKm: Double?,
    val pickupTimeMin: Int?,
    val confidence: Int,
    val source: String
)

private enum class StructuredNodeCategory {
    PRICE,
    PICKUP_DISTANCE,
    PICKUP_TIME,
    RIDE_DISTANCE,
    RIDE_TIME,
    ADDRESS,
    ACTION,
    UNKNOWN
}

object RideInfoStructuredExtractionParser {

    fun parseStructuredExtraction(
        nodes: List<StructuredNodeInput>,
        minRidePrice: Double,
        pricePattern: Regex,
        fallbackPricePattern: Regex,
        distancePattern: Regex,
        timePattern: Regex,
        minRangePattern: Regex
    ): StructuredExtractionResult? {
        if (nodes.isEmpty()) return null

        var price: Double? = null
        var rideDistanceKm: Double? = null
        var rideTimeMin: Int? = null
        var pickupDistanceKm: Double? = null
        var pickupTimeMin: Int? = null
        var confidence = 0
        var priceNodeIndex = -1

        for ((idx, node) in nodes.withIndex()) {
            val category = classifyNodeCategory(node.idSuffix)
            val text = node.combinedText
            if (text.isBlank()) continue

            when (category) {
                StructuredNodeCategory.PRICE -> {
                    if (price == null) {
                        val priceMatch = pricePattern.find(text)
                            ?: fallbackPricePattern.find(text)
                        val value = priceMatch?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (value != null && value >= minRidePrice) {
                            price = value
                            priceNodeIndex = idx
                            confidence += 2
                        }
                    }
                }

                StructuredNodeCategory.RIDE_DISTANCE -> {
                    if (rideDistanceKm == null) {
                        val value = distancePattern.find(text)?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (value != null && value in 0.2..300.0) {
                            rideDistanceKm = value
                            confidence += 2
                        }
                    }
                }

                StructuredNodeCategory.RIDE_TIME -> {
                    if (rideTimeMin == null) {
                        val value = timePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (value != null && value in 1..300) {
                            rideTimeMin = value
                            confidence += 2
                        }
                    }
                }

                StructuredNodeCategory.PICKUP_DISTANCE -> {
                    if (pickupDistanceKm == null) {
                        val value = distancePattern.find(text)?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (value != null && value in 0.1..50.0) {
                            pickupDistanceKm = value
                            confidence += 2
                        }
                    }
                }

                StructuredNodeCategory.PICKUP_TIME -> {
                    if (pickupTimeMin == null) {
                        val value = timePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: minRangePattern.find(text)?.let { range ->
                                Regex("""\d{1,3}""").findAll(range.value)
                                    .mapNotNull { m -> m.value.toIntOrNull() }
                                    .maxOrNull()
                            }
                        if (value != null && value in 1..120) {
                            pickupTimeMin = value
                            confidence += 2
                        }
                    }
                }

                else -> Unit
            }
        }

        if (price != null && priceNodeIndex >= 0 && (rideDistanceKm == null || rideTimeMin == null)) {
            for ((idx, node) in nodes.withIndex()) {
                val category = classifyNodeCategory(node.idSuffix)
                if (category != StructuredNodeCategory.UNKNOWN) continue
                val text = node.combinedText
                if (text.isBlank()) continue

                val kmVal = distancePattern.find(text)?.groupValues?.getOrNull(1)
                    ?.replace(",", ".")?.toDoubleOrNull()
                val minVal = timePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: minRangePattern.find(text)?.let { range ->
                        Regex("""\d{1,3}""").findAll(range.value)
                            .mapNotNull { m -> m.value.toIntOrNull() }
                            .maxOrNull()
                    }

                if (idx < priceNodeIndex) {
                    if (kmVal != null && pickupDistanceKm == null && kmVal in 0.1..50.0) {
                        pickupDistanceKm = kmVal
                        confidence += 1
                    }
                    if (minVal != null && pickupTimeMin == null && minVal in 1..120) {
                        pickupTimeMin = minVal
                        confidence += 1
                    }
                } else if (idx > priceNodeIndex) {
                    if (kmVal != null && rideDistanceKm == null && kmVal in 0.2..300.0) {
                        rideDistanceKm = kmVal
                        confidence += 1
                    }
                    if (minVal != null && rideTimeMin == null && minVal in 1..300) {
                        rideTimeMin = minVal
                        confidence += 1
                    }
                }
            }
        }

        if (price == null && confidence < 2) return null

        return StructuredExtractionResult(
            price = price,
            rideDistanceKm = rideDistanceKm,
            rideTimeMin = rideTimeMin,
            pickupDistanceKm = pickupDistanceKm,
            pickupTimeMin = pickupTimeMin,
            confidence = confidence,
            source = "node-semantic"
        )
    }

    private fun classifyNodeCategory(idSuffix: String): StructuredNodeCategory {
        if (idSuffix.isBlank()) return StructuredNodeCategory.UNKNOWN

        val lower = idSuffix.lowercase()

        if (Regex("(?:fare|price|amount|valor|tarifa|earning|ganho|cost|surge|promo)").containsMatchIn(lower)) {
            return StructuredNodeCategory.PRICE
        }
        if (Regex("(?:pickup_dist|eta_dist|arrival_dist|buscar_dist)").containsMatchIn(lower)) {
            return StructuredNodeCategory.PICKUP_DISTANCE
        }
        if (Regex("(?:pickup_eta|pickup_time|arrival|eta_time|eta_min|chegada|buscar_time|time_to_pickup)").containsMatchIn(lower)) {
            return StructuredNodeCategory.PICKUP_TIME
        }
        if (Regex("(?:trip_dist|ride_dist|route_dist|trip_length)").containsMatchIn(lower)) {
            return StructuredNodeCategory.RIDE_DISTANCE
        }
        if (lower.contains("distance") && !lower.contains("pickup") && !lower.contains("eta")) {
            return StructuredNodeCategory.RIDE_DISTANCE
        }
        if (Regex("(?:trip_time|ride_time|trip_duration|duration|ride_eta|trip_eta|estimated_time)").containsMatchIn(lower)) {
            return StructuredNodeCategory.RIDE_TIME
        }
        if (Regex("(?:address|location|origin|destination|destino|pickup_loc|dropoff|endereco)").containsMatchIn(lower)) {
            return StructuredNodeCategory.ADDRESS
        }
        if (Regex("(?:accept|decline|reject|cancel|aceitar|recusar|ignorar|pular|skip)").containsMatchIn(lower)) {
            return StructuredNodeCategory.ACTION
        }

        return StructuredNodeCategory.UNKNOWN
    }
}