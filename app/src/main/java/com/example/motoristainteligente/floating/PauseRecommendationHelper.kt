package com.example.motoristainteligente

import android.location.Location

data class PauseUiAction(
    val shouldPulseWarning: Boolean,
    val shouldShowStatusCard: Boolean
)

fun evaluatePauseUiAction(
    demandStats: DemandTracker.DemandStats,
    location: Location?,
    marketInfo: MarketDataService.MarketInfo?,
    isStatusCardVisible: Boolean
): PauseUiAction {
    val pauseRec = PauseAdvisor.analyze(demandStats, location, marketInfo)
    val isCritical = pauseRec.urgency == PauseAdvisor.PauseUrgency.CRITICAL

    return PauseUiAction(
        shouldPulseWarning = isCritical,
        shouldShowStatusCard = isCritical && !isStatusCardVisible
    )
}
