package com.example.motoristainteligente

import android.view.View
import android.widget.ImageView
import android.widget.TextView

fun bindRideAnalysisCard(
    card: View,
    analysis: RideAnalysis,
    onClose: () -> Unit
) {
    val pickupDistance = analysis.pickupDistanceKm
    val totalDistanceKm = (analysis.rideData.distanceKm + pickupDistance).coerceAtLeast(0.1)
    val valuePerKm = analysis.rideData.ridePrice / totalDistanceKm
    val valuePerHour = analysis.estimatedEarningsPerHour

    card.findViewById<TextView>(R.id.tvAppSource).text = "CORRIDA"

    val ivAppIcon = card.findViewById<ImageView>(R.id.ivAppIcon)
    val tvAppIconLabel = card.findViewById<TextView>(R.id.tvAppIconLabel)
    ivAppIcon.setImageResource(R.drawable.ic_analytics)
    tvAppIconLabel.text = ""

    card.findViewById<TextView>(R.id.tvPricePerKm).text =
        String.format("R$ %.2f", valuePerKm)

    card.findViewById<TextView>(R.id.tvValuePerMin).text =
        String.format("R$ %.2f", valuePerHour)

    card.findViewById<TextView>(R.id.tvTotalDistance).text =
        String.format("R$ %.2f", analysis.rideData.ridePrice)

    val tvRecommendation = card.findViewById<TextView>(R.id.tvRecommendation)
    when (analysis.recommendation) {
        Recommendation.WORTH_IT -> {
            tvRecommendation.text = "COMPENSA"
            tvRecommendation.setTextColor(0xFF4CAF50.toInt())
        }
        Recommendation.NOT_WORTH_IT -> {
            tvRecommendation.text = "EVITAR"
            tvRecommendation.setTextColor(0xFFF44336.toInt())
        }
        Recommendation.NEUTRAL -> {
            tvRecommendation.text = "NEUTRO"
            tvRecommendation.setTextColor(0xFFFF9800.toInt())
        }
    }

    val tvPickup = card.findViewById<TextView>(R.id.tvPickupAddress)
    val tvDropoff = card.findViewById<TextView>(R.id.tvDropoffAddress)
    val tvQuickInsights = card.findViewById<TextView>(R.id.tvQuickInsights)
    tvPickup.text = analysis.rideData.pickupAddress.ifBlank { "Endereço não extraído" }
    tvDropoff.text = analysis.rideData.dropoffAddress.ifBlank { "Endereço não extraído" }
    tvQuickInsights.text = analysis.reasons.take(3).joinToString(" • ")

    val isWithinParameters = analysis.reasons.size == 1 &&
        analysis.reasons.first().equals("Dentro dos seus parâmetros", ignoreCase = true)
    tvQuickInsights.setTextColor(
        if (isWithinParameters) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
    )

    card.findViewById<View>(R.id.btnClose).setOnClickListener {
        onClose()
    }
}
