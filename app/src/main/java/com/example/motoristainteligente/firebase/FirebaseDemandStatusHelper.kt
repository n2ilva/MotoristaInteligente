package com.example.motoristainteligente

data class FirebaseDemandStatus(
    val cardLabel: String,
    val notificationLabel: String,
    val color: Int
)

fun computeFirebaseDemandStatus(stats: FirestoreManager.RideOfferStats): FirebaseDemandStatus {
    if (stats.totalOffersToday == 0 || stats.offersLast3h == 0) {
        return FirebaseDemandStatus(
            cardLabel = "Neutro",
            notificationLabel = "Neutro",
            color = 0xFFFFC107.toInt()
        )
    }

    val lastHour = stats.offersLast1h
    val previousHourEstimated = ((stats.offersLast3h - lastHour).coerceAtLeast(0) / 2.0)

    return when {
        lastHour > previousHourEstimated * 1.10 -> FirebaseDemandStatus(
            cardLabel = "Alta",
            notificationLabel = "Alta",
            color = 0xFF4CAF50.toInt()
        )
        lastHour < previousHourEstimated * 0.90 -> FirebaseDemandStatus(
            cardLabel = "Baixa",
            notificationLabel = "Baixa",
            color = 0xFFF44336.toInt()
        )
        else -> FirebaseDemandStatus(
            cardLabel = "Neutro",
            notificationLabel = "Neutro",
            color = 0xFFFFC107.toInt()
        )
    }
}
