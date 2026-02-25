package com.example.motoristainteligente

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

fun bindStatusCardCloseAction(card: View, onClose: () -> Unit) {
    card.findViewById<View>(R.id.btnCloseStatus).setOnClickListener { onClose() }
}

fun bindStatusCardSessionTime(card: View, sessionDurationMin: Int) {
    val hours = sessionDurationMin / 60
    val mins = sessionDurationMin % 60
    card.findViewById<TextView>(R.id.tvSessionTime).text =
        if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
}

fun bindStatusCardDemandLevel(card: View, label: String, color: Int) {
    card.findViewById<TextView>(R.id.tvDemandLevel).apply {
        text = label
        setTextColor(color)
    }
}

fun bindStatusCardNoOffersAlert(card: View, visible: Boolean) {
    val layoutAlert = card.findViewById<LinearLayout>(R.id.layoutNoRidesAlert)
    val dividerAlert = card.findViewById<View>(R.id.dividerAfterAlert)
    layoutAlert.visibility = if (visible) View.VISIBLE else View.GONE
    dividerAlert.visibility = if (visible) View.VISIBLE else View.GONE
}

fun bindStatusCardDemandMetrics(
    card: View,
    stats: FirestoreManager.RideOfferStats,
    uberPerHour: Int,
    ninetyNinePerHour: Int
) {
    card.findViewById<TextView>(R.id.tvRidesUber).text = "${stats.offersUber}"
    card.findViewById<TextView>(R.id.tvRides99).text = "${stats.offers99}"

    card.findViewById<TextView>(R.id.tvAvgPriceUber).text =
        if (stats.avgPriceUber > 0) String.format("R$ %.2f", stats.avgPriceUber) else "—"
    card.findViewById<TextView>(R.id.tvAvgPrice99).text =
        if (stats.avgPrice99 > 0) String.format("R$ %.2f", stats.avgPrice99) else "—"

    card.findViewById<TextView>(R.id.tvAvgTimeUber).text =
        if (stats.avgEstimatedTimeMinUber > 0) String.format("%.0f min", stats.avgEstimatedTimeMinUber) else "—"
    card.findViewById<TextView>(R.id.tvAvgTime99).text =
        if (stats.avgEstimatedTimeMin99 > 0) String.format("%.0f min", stats.avgEstimatedTimeMin99) else "—"

    card.findViewById<TextView>(R.id.tvSessionHourly).text = "${uberPerHour.coerceAtLeast(0)}"
    card.findViewById<TextView>(R.id.tvAvgPrice).text = "${ninetyNinePerHour.coerceAtLeast(0)}"
}

fun bindStatusCardFooter(
    card: View,
    advice: String,
    reason: String,
    location: String,
    peakNext: String,
    peakTip: String,
    backgroundRes: Int
) {
    val pauseBg = card.findViewById<FrameLayout>(R.id.pauseBackground)
    val tvPauseAdvice = card.findViewById<TextView>(R.id.tvPauseAdvice)
    val tvPauseReason = card.findViewById<TextView>(R.id.tvPauseReason)
    val tvLocation = card.findViewById<TextView>(R.id.tvLocation)
    val tvPeakNext = card.findViewById<TextView>(R.id.tvPeakNext)
    val tvPeakTips = card.findViewById<TextView>(R.id.tvPeakTips)

    tvPauseAdvice.text = advice
    tvPauseReason.text = reason
    tvPauseReason.visibility = if (reason.isBlank()) View.GONE else View.VISIBLE

    tvLocation.text = location
    tvPeakNext.text = peakNext
    tvPeakTips.text = peakTip
    tvPeakTips.visibility = if (peakTip.isBlank()) View.GONE else View.VISIBLE

    pauseBg.setBackgroundResource(backgroundRes)
}

fun bindStatusCardLoggedOutState(card: View) {
    bindStatusCardDemandLevel(card, "Login necessário", 0xFFFFC107.toInt())
    bindStatusCardNoOffersAlert(card, visible = false)

    card.findViewById<TextView>(R.id.tvRidesUber).text = "—"
    card.findViewById<TextView>(R.id.tvRides99).text = "—"
    card.findViewById<TextView>(R.id.tvAvgPriceUber).text = "—"
    card.findViewById<TextView>(R.id.tvAvgPrice99).text = "—"
    card.findViewById<TextView>(R.id.tvAvgTimeUber).text = "—"
    card.findViewById<TextView>(R.id.tvAvgTime99).text = "—"
    card.findViewById<TextView>(R.id.tvSessionHourly).text = "—"
    card.findViewById<TextView>(R.id.tvAvgPrice).text = "—"

    bindStatusCardFooter(
        card = card,
        advice = "ENTRE COM GOOGLE",
        reason = "Faça login para liberar a análise",
        location = "—",
        peakNext = "Pico: login",
        peakTip = "",
        backgroundRes = R.drawable.bg_card_neutral
    )
}
