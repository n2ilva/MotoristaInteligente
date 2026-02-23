package com.example.motoristainteligente

import android.location.Location
import java.util.Calendar

/**
 * Consultor inteligente de pausas para o motorista.
 *
 * Analisa múltiplos fatores para recomendar se o motorista
 * deve continuar rodando ou fazer uma pausa:
 *
 * - Tempo de sessão (fadiga)
 * - Demanda atual (caindo = pausa, subindo = continuar)
 * - Horário do dia (janelas de pico vs vales)
 * - Tendência de preços (caindo = melhor pausar)
 * - Dados de mercado da internet (preços de referência por região/hora)
 * - Localização (zona quente vs zona fria)
 */
object PauseAdvisor {

    /**
     * Resultado da recomendação de pausa.
     */
    data class PauseRecommendation(
        val shouldPause: Boolean,
        val urgency: PauseUrgency,
        val reasons: List<String>,
        val suggestedPauseMin: Int,
        val resumeTime: String,
        val demandForecast: String,
        val score: Int // 0-100, quanto maior MAIS deve pausar
    )

    enum class PauseUrgency(val displayText: String, val emoji: String, val color: Long) {
        CRITICAL("Pare agora!", "🛑", 0xFFF44336),
        RECOMMENDED("Considere pausar", "⚠️", 0xFFFFC107),
        OPTIONAL("Pode pausar", "💤", 0xFF2196F3),
        NOT_NOW("Continue!", "✅", 0xFF4CAF50)
    }

    /**
     * Dados de mercado de uma região/horário (da internet).
     */
    data class MarketHourData(
        val hour: Int,
        val demandIndex: Double,      // 0.0 a 1.0 (1 = demanda máxima)
        val avgPricePerKm: Double,    // R$/km médio na região/horário
        val surgeMultiplier: Double   // multiplicador de surge pricing
    )

    // Dados de mercado padrão por hora (atualizados via MarketDataService)
    private var marketData: Map<Int, MarketHourData> = getDefaultMarketData()

    /**
     * Atualiza dados de mercado obtidos da internet.
     */
    fun updateMarketData(data: Map<Int, MarketHourData>) {
        marketData = data
    }

    /**
     * Analisa se o motorista deve fazer uma pausa AGORA.
     */
    fun analyze(
        demandStats: DemandTracker.DemandStats,
        location: Location?,
        marketInfo: MarketDataService.MarketInfo? = null
    ): PauseRecommendation {
        val reasons = mutableListOf<String>()
        var pauseScore = 0 // 0 = nenhuma razão pra parar, 100 = pare agora

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val currentMarket = marketInfo?.let {
            MarketHourData(
                hour = hour,
                demandIndex = it.demandIndex,
                avgPricePerKm = it.avgPricePerKm,
                surgeMultiplier = it.surgeMultiplier
            )
        } ?: marketData[hour] ?: marketData[12]!!

        // ========== 1. FADIGA (tempo de sessão) ==========
        val sessionMin = demandStats.sessionDurationMin
        when {
            sessionMin > 360 -> { // +6h
                pauseScore += 40
                reasons.add("🛑 Você está rodando há ${sessionMin / 60}h! Descanse")
            }
            sessionMin > 240 -> { // +4h
                pauseScore += 25
                reasons.add("⚠️ ${sessionMin / 60}h+ de sessão — fadiga aumenta risco")
            }
            sessionMin > 120 -> { // +2h
                pauseScore += 10
                reasons.add("🕐 ${sessionMin / 60}h de sessão")
            }
        }

        // ========== 2. DEMANDA ATUAL ==========
        when (demandStats.demandLevel) {
            DemandTracker.DemandLevel.LOW -> {
                pauseScore += 20
                reasons.add("❄️ Demanda baixa — poucas corridas chegando")
            }
            DemandTracker.DemandLevel.UNKNOWN -> {
                pauseScore += 15
                reasons.add("❓ Sem corridas recentes — demanda incerta")
            }
            DemandTracker.DemandLevel.MEDIUM -> {
                pauseScore += 5
            }
            DemandTracker.DemandLevel.HIGH -> {
                pauseScore -= 15
                reasons.add("🔥 Demanda alta — bom momento para continuar")
            }
        }

        // ========== 3. TENDÊNCIA DE DEMANDA ==========
        when (demandStats.trend) {
            DemandTracker.DemandTrend.FALLING -> {
                pauseScore += 15
                reasons.add("📉 Demanda caindo — pode piorar")
            }
            DemandTracker.DemandTrend.RISING -> {
                pauseScore -= 10
                reasons.add("📈 Demanda subindo — momento favorável")
            }
            DemandTracker.DemandTrend.STABLE -> { /* neutro */ }
        }

        // ========== 4. TENDÊNCIA DE PREÇOS ==========
        when (demandStats.priceTrend) {
            DemandTracker.PriceTrend.DECREASING -> {
                pauseScore += 15
                reasons.add("🔻 Preços caindo — corridas menos rentáveis")
            }
            DemandTracker.PriceTrend.INCREASING -> {
                pauseScore -= 10
                reasons.add("💹 Preços subindo — boa rentabilidade")
            }
            DemandTracker.PriceTrend.STABLE -> { /* neutro */ }
        }

        // ========== 5. HORÁRIO / PREVISÃO DE MERCADO ==========
        val nextPeakInfo = getNextPeakInfo(hour)
        if (currentMarket.demandIndex < 0.3) {
            pauseScore += 15
            reasons.add("📊 Horário de baixa demanda na sua região")
        } else if (currentMarket.demandIndex > 0.7) {
            pauseScore -= 15
            reasons.add("📊 Horário de alta demanda — aproveite!")
        }

        if (currentMarket.surgeMultiplier > 1.3) {
            pauseScore -= 20
            reasons.add("💰 Surge ${String.format("%.1fx", currentMarket.surgeMultiplier)} ativo!")
        }

        // ========== 6. GANHO DA SESSÃO ==========
        if (demandStats.sessionAvgEarningsPerHour > 0 && sessionMin > 30) {
            if (demandStats.sessionAvgEarningsPerHour < 15) {
                pauseScore += 15
                reasons.add("💸 Ganho médio baixo: R$ ${String.format("%.0f", demandStats.sessionAvgEarningsPerHour)}/h")
            } else if (demandStats.sessionAvgEarningsPerHour > 35) {
                pauseScore -= 10
                reasons.add("💵 Ótimo ganho: R$ ${String.format("%.0f", demandStats.sessionAvgEarningsPerHour)}/h")
            }
        }

        // ========== Calcular resultado ==========
        pauseScore = pauseScore.coerceIn(0, 100)

        val urgency = when {
            pauseScore >= 70 -> PauseUrgency.CRITICAL
            pauseScore >= 50 -> PauseUrgency.RECOMMENDED
            pauseScore >= 30 -> PauseUrgency.OPTIONAL
            else -> PauseUrgency.NOT_NOW
        }

        val shouldPause = pauseScore >= 50

        // Sugestão de pausa
        val suggestedPauseMin = when (urgency) {
            PauseUrgency.CRITICAL -> 30
            PauseUrgency.RECOMMENDED -> 20
            PauseUrgency.OPTIONAL -> 15
            PauseUrgency.NOT_NOW -> 0
        }

        // Horário para retomar
        val resumeTime = if (shouldPause) {
            val cal = Calendar.getInstance()
            val nextPeak = getNextPeakHour(hour)
            if (nextPeak > hour) {
                cal.set(Calendar.HOUR_OF_DAY, nextPeak)
                cal.set(Calendar.MINUTE, 0)
                String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            } else {
                val min = cal.get(Calendar.MINUTE) + suggestedPauseMin
                cal.add(Calendar.MINUTE, suggestedPauseMin)
                String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            }
        } else ""

        // Previsão
        val demandForecast = nextPeakInfo

        if (reasons.isEmpty()) {
            reasons.add("Condições normais de operação")
        }

        return PauseRecommendation(
            shouldPause = shouldPause,
            urgency = urgency,
            reasons = reasons,
            suggestedPauseMin = suggestedPauseMin,
            resumeTime = resumeTime,
            demandForecast = demandForecast,
            score = pauseScore
        )
    }

    /**
     * Retorna o próximo horário de pico.
     */
    fun getNextPeakHour(currentHour: Int): Int {
        val peakHours = listOf(7, 8, 12, 17, 18, 19, 22, 23)
        return peakHours.firstOrNull { it > currentHour } ?: peakHours.first()
    }

    /**
     * Retorna informação textual sobre próximo pico.
     */
    fun getNextPeakInfo(currentHour: Int): String {
        return when {
            currentHour in 5..6 -> "Próximo pico: Matutino (7h-9h)"
            currentHour in 7..9 -> "Pico atual: Matutino (7h-9h)"
            currentHour in 10..11 -> "Próximo pico: Almoço (11h30-13h)"
            currentHour in 12..13 -> "Pico atual: Almoço (12h-13h)"
            currentHour in 14..16 -> "Próximo pico: Noturno (17h-20h)"
            currentHour in 17..20 -> "Pico atual: Noturno (17h-20h)"
            currentHour in 21..22 -> "Próximo pico: Balada (22h-1h)"
            currentHour == 23 || currentHour in 0..1 -> "Pico atual: Balada (22h-1h)"
            currentHour in 2..4 -> "Próximo pico: Matutino (7h-9h)"
            else -> "Próximo pico em breve"
        }
    }

    /**
     * Dados de referência de demanda por hora (padrão brasileiro).
     * demandIndex: 0.0 (mínima) a 1.0 (máxima)
     */
    private fun getDefaultMarketData(): Map<Int, MarketHourData> {
        return mapOf(
            0 to MarketHourData(0, 0.45, 1.80, 1.2),
            1 to MarketHourData(1, 0.35, 1.90, 1.3),
            2 to MarketHourData(2, 0.20, 2.00, 1.4),
            3 to MarketHourData(3, 0.10, 2.00, 1.3),
            4 to MarketHourData(4, 0.08, 1.80, 1.1),
            5 to MarketHourData(5, 0.15, 1.50, 1.0),
            6 to MarketHourData(6, 0.35, 1.40, 1.0),
            7 to MarketHourData(7, 0.75, 1.50, 1.1),
            8 to MarketHourData(8, 0.85, 1.60, 1.2),
            9 to MarketHourData(9, 0.55, 1.50, 1.0),
            10 to MarketHourData(10, 0.40, 1.40, 1.0),
            11 to MarketHourData(11, 0.50, 1.40, 1.0),
            12 to MarketHourData(12, 0.65, 1.50, 1.1),
            13 to MarketHourData(13, 0.55, 1.45, 1.0),
            14 to MarketHourData(14, 0.35, 1.35, 1.0),
            15 to MarketHourData(15, 0.30, 1.35, 1.0),
            16 to MarketHourData(16, 0.45, 1.40, 1.0),
            17 to MarketHourData(17, 0.80, 1.55, 1.2),
            18 to MarketHourData(18, 0.95, 1.70, 1.3),
            19 to MarketHourData(19, 0.90, 1.65, 1.25),
            20 to MarketHourData(20, 0.70, 1.55, 1.1),
            21 to MarketHourData(21, 0.55, 1.50, 1.0),
            22 to MarketHourData(22, 0.60, 1.60, 1.15),
            23 to MarketHourData(23, 0.55, 1.70, 1.2)
        )
    }
}
