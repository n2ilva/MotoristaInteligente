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
    val destinationDistance = analysis.rideData.distanceKm
    val totalDistanceKm = (analysis.rideData.distanceKm + pickupDistance).coerceAtLeast(0.1)
    val valuePerKm = analysis.rideData.ridePrice / totalDistanceKm
    val valuePerHour = analysis.estimatedEarningsPerHour

    card.findViewById<TextView>(R.id.tvAppSource).text = "ANÁLISE"

    val ivAppIcon = card.findViewById<ImageView>(R.id.ivAppIcon)
    val tvAppIconLabel = card.findViewById<TextView>(R.id.tvAppIconLabel)
    ivAppIcon.setImageResource(R.drawable.ic_analytics)
    tvAppIconLabel.text = ""

    // Referências das configurações do motorista
    val minPricePerKm = analysis.referencePricePerKm
    val minEarningsPerHour = RideAnalyzer.getCurrentReferences()["minEarningsPerHour"] ?: 20.0
    val prefs = DriverPreferences(card.context)
    val maxPickupDistance = prefs.maxPickupDistance
    val maxRideDistance = prefs.maxRideDistance

    // Cor verde se atingiu o mínimo, vermelho se ficou abaixo
    val colorGreen = 0xFF4CAF50.toInt()
    val colorRed = 0xFFF44336.toInt()
    val recColor = when (analysis.recommendation) {
        Recommendation.WORTH_IT -> colorGreen
        Recommendation.NOT_WORTH_IT -> colorRed
        Recommendation.NEUTRAL -> 0xFFFF9800.toInt()
    }

    card.findViewById<TextView>(R.id.tvRideValue).apply {
        text = String.format("R$ %.2f", analysis.rideData.ridePrice)
        setTextColor(recColor)
    }

    card.findViewById<TextView>(R.id.tvPickupKm).apply {
        text = String.format("%.1f km", pickupDistance)
        setTextColor(if (pickupDistance >= maxPickupDistance) colorGreen else colorRed)
    }

    card.findViewById<TextView>(R.id.tvDestinationKm).apply {
        text = String.format("%.1f km", destinationDistance)
        setTextColor(if (destinationDistance >= maxRideDistance) colorGreen else colorRed)
    }

    card.findViewById<TextView>(R.id.tvAvgPerKm).apply {
        text = String.format("R$ %.2f", valuePerKm)
        setTextColor(if (valuePerKm >= minPricePerKm) colorGreen else colorRed)
    }

    card.findViewById<TextView>(R.id.tvAvgPerHour).apply {
        text = String.format("R$ %.2f", valuePerHour)
        setTextColor(if (valuePerHour >= minEarningsPerHour) colorGreen else colorRed)
    }

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
