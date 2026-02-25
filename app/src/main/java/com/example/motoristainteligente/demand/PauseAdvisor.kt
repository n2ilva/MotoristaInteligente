package com.example.motoristainteligente

import android.location.Location
import java.util.Calendar
import java.text.Normalizer

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

    data class CityPeakGuidance(
        val inPeakNow: Boolean,
        val nextPeakText: String,
        val tipText: String,
        val shouldPauseNow: Boolean
    )

    data class CityPeakSignal(
        val supportedCity: Boolean,
        val cityName: String,
        val inPeakNow: Boolean,
        val activePeakLabel: String?,
        val minutesToPeakEnd: Int?,
        val nextPeakLabel: String?,
        val minutesToNextPeak: Int?
    )

    private data class PeakWindow(
        val startMin: Int,
        val endMin: Int,
        val label: String
    ) {
        fun contains(minuteOfDay: Int): Boolean = minuteOfDay in startMin..endMin
    }

    private data class CityPeakProfile(
        val normalizedCityName: String,
        val weekdayWindows: List<PeakWindow>,
        val weekendWindows: List<PeakWindow>,
        val peakTips: List<String>,
        val offPeakTip: String,
        val weekendTip: String
    )

    private data class CityPeakContext(
        val profile: CityPeakProfile,
        val isWeekend: Boolean,
        val minuteOfDay: Int,
        val windows: List<PeakWindow>,
        val activeWindow: PeakWindow?,
        val nextWindow: PeakWindow?
    )

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

    fun getCityPeakGuidance(
        cityName: String?,
        hour: Int,
        minute: Int,
        dayOfWeek: Int
    ): CityPeakGuidance {
        val context = buildCityPeakContext(cityName, hour, minute, dayOfWeek)
        if (context == null) {
            return CityPeakGuidance(
                inPeakNow = false,
                nextPeakText = getNextPeakInfo(hour),
                tipText = "Use janelas de pico para rodar e faça pausa curta entre-picos.",
                shouldPauseNow = false
            )
        }

        val inPeakNow = context.activeWindow != null
        val nextPeakText = if (inPeakNow) {
            "Pico atual: ${context.activeWindow!!.label}"
        } else if (context.nextWindow != null) {
            "Próximo pico: ${context.nextWindow.label}"
        } else {
            val first = context.windows.firstOrNull()
            if (first != null) "Próximo pico amanhã: ${first.label}" else "Próximo pico: —"
        }

        val shouldPauseNow = !inPeakNow
        val tipText = when {
            context.isWeekend -> context.profile.weekendTip
            inPeakNow -> context.profile.peakTips.firstOrNull() ?: "Horário forte: priorize corridas com melhor retorno."
            else -> context.profile.offPeakTip
        }

        return CityPeakGuidance(
            inPeakNow = inPeakNow,
            nextPeakText = nextPeakText,
            tipText = tipText,
            shouldPauseNow = shouldPauseNow
        )
    }

    fun getCityPeakSignal(
        cityName: String?,
        hour: Int,
        minute: Int,
        dayOfWeek: Int
    ): CityPeakSignal {
        val context = buildCityPeakContext(cityName, hour, minute, dayOfWeek)
        if (context == null) {
            return CityPeakSignal(
                supportedCity = false,
                cityName = cityName ?: "",
                inPeakNow = false,
                activePeakLabel = null,
                minutesToPeakEnd = null,
                nextPeakLabel = null,
                minutesToNextPeak = null
            )
        }

        val minutesToEnd = context.activeWindow?.let {
            (it.endMin - context.minuteOfDay).coerceAtLeast(0)
        }
        val minutesToNext = if (context.activeWindow != null) {
            null
        } else {
            context.nextWindow?.let { (it.startMin - context.minuteOfDay).coerceAtLeast(0) }
        }

        return CityPeakSignal(
            supportedCity = true,
            cityName = context.profile.normalizedCityName,
            inPeakNow = context.activeWindow != null,
            activePeakLabel = context.activeWindow?.label,
            minutesToPeakEnd = minutesToEnd,
            nextPeakLabel = context.nextWindow?.label,
            minutesToNextPeak = minutesToNext
        )
    }

    private fun buildCityPeakContext(
        cityName: String?,
        hour: Int,
        minute: Int,
        dayOfWeek: Int
    ): CityPeakContext? {
        val profile = resolveCityProfile(cityName) ?: return null
        val minuteOfDay = hour * 60 + minute
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val windows = if (isWeekend) profile.weekendWindows else profile.weekdayWindows
        val activeWindow = windows.firstOrNull { it.contains(minuteOfDay) }
        val nextWindow = windows.firstOrNull { it.startMin > minuteOfDay }

        return CityPeakContext(
            profile = profile,
            isWeekend = isWeekend,
            minuteOfDay = minuteOfDay,
            windows = windows,
            activeWindow = activeWindow,
            nextWindow = nextWindow
        )
    }

    private fun resolveCityProfile(cityName: String?): CityPeakProfile? {
        val normalized = normalizeCityName(cityName)
        if (normalized.isBlank()) return null
        return getGoiasPeakProfiles().firstOrNull { profile ->
            normalized.contains(profile.normalizedCityName)
        }
    }

    private fun normalizeCityName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents.lowercase()
    }

    private fun h(hour: Int, minute: Int): Int = hour * 60 + minute

    private fun getGoiasPeakProfiles(): List<CityPeakProfile> {
        return listOf(
            CityPeakProfile(
                normalizedCityName = "goiania",
                weekdayWindows = listOf(
                    PeakWindow(h(5, 0), h(9, 0), "Manhã (5h-9h)"),
                    PeakWindow(h(11, 0), h(14, 0), "Almoço (11h-14h)"),
                    PeakWindow(h(16, 0), h(20, 0), "Final da tarde/noite (16h-20h)"),
                    PeakWindow(h(22, 0), h(23, 59), "Noturno (após 22h)")
                ),
                weekendWindows = listOf(
                    PeakWindow(h(0, 0), h(3, 0), "Madrugada (00h-3h)"),
                    PeakWindow(h(5, 0), h(10, 0), "Sábado manhã (até 10h)"),
                    PeakWindow(h(9, 0), h(12, 0), "Domingo manhã"),
                    PeakWindow(h(16, 0), h(20, 0), "Domingo fim da tarde"),
                    PeakWindow(h(22, 0), h(23, 59), "Noite (22h-00h)")
                ),
                peakTips = listOf(
                    "Manhã (5h-9h): pico de deslocamento para trabalho e escolas.",
                    "Almoço (11h-14h): alta demanda no Centro, Oeste, Marista e Bueno.",
                    "Final da tarde/noite (16h-20h): maior volume de chamadas e tarifa dinâmica.",
                    "Após 22h: foco em bares e saída de faculdades."
                ),
                offPeakTip = "Fora dos picos, reposicione e faça pausa estratégica para voltar no próximo horário forte.",
                weekendTip = "Fim de semana: madrugada de festas/restaurantes, sábado manhã e domingo manhã/fim da tarde tendem a aquecer."
            ),
            CityPeakProfile(
                normalizedCityName = "senador canedo",
                weekdayWindows = listOf(
                    PeakWindow(h(5, 0), h(9, 30), "Manhã (5h-9h30)"),
                    PeakWindow(h(11, 45), h(14, 0), "Almoço (11h45-14h)"),
                    PeakWindow(h(17, 0), h(20, 0), "Final da tarde/noite (17h-20h)")
                ),
                weekendWindows = listOf(
                    PeakWindow(h(5, 0), h(9, 30), "Manhã (5h-9h30)"),
                    PeakWindow(h(11, 45), h(14, 0), "Almoço (11h45-14h)"),
                    PeakWindow(h(17, 0), h(20, 0), "Final da tarde/noite (17h-20h)")
                ),
                peakTips = listOf(
                    "Manhã (5h-9h30): pico de saída para trabalho em Goiânia e indústrias locais.",
                    "Almoço (11h45-14h): movimentação interna e para regiões centrais.",
                    "Final da tarde/noite (17h-20h): alto fluxo de retorno para casa."
                ),
                offPeakTip = "Entre os picos, priorize pausa curta e reposicionamento em eixos de saída/retorno.",
                weekendTip = "Siga os mesmos horários de alta e monitore deslocamentos para Goiânia e centros locais."
            ),
            CityPeakProfile(
                normalizedCityName = "aparecida de goiania",
                weekdayWindows = listOf(
                    PeakWindow(h(5, 0), h(9, 30), "Manhã (5h-9h30)"),
                    PeakWindow(h(11, 45), h(14, 0), "Almoço (11h45-14h)"),
                    PeakWindow(h(17, 0), h(20, 0), "Final da tarde/noite (17h-20h)")
                ),
                weekendWindows = listOf(
                    PeakWindow(h(0, 0), h(2, 0), "Madrugada (00h-2h)"),
                    PeakWindow(h(16, 0), h(20, 0), "Fim da tarde/noite (16h-20h)"),
                    PeakWindow(h(21, 0), h(23, 59), "Noite (21h-00h)")
                ),
                peakTips = listOf(
                    "Manhã (5h-9h30): forte saída de bairros residenciais para Aparecida/Goiânia.",
                    "Almoço (11h45-14h): boa movimentação para restaurantes, clínicas e centros empresariais.",
                    "Final da tarde/noite (17h-20h): alto fluxo de retorno e maior incidência de dinâmica."
                ),
                offPeakTip = "Fora dos picos, mantenha-se em corredores de retorno e faça pausa estratégica curta.",
                weekendTip = "Sexta/sábado à noite até 2h e domingo 16h-20h costumam ter demanda alta por lazer e retorno de viagens."
            ),
            CityPeakProfile(
                normalizedCityName = "trindade",
                weekdayWindows = listOf(
                    PeakWindow(h(5, 0), h(9, 0), "Manhã (5h-9h)"),
                    PeakWindow(h(11, 45), h(14, 0), "Almoço (11h45-14h)"),
                    PeakWindow(h(16, 30), h(20, 0), "Tarde/Noite (16h30-20h)")
                ),
                weekendWindows = listOf(
                    PeakWindow(h(0, 0), h(2, 0), "Madrugada (00h-2h)"),
                    PeakWindow(h(16, 0), h(20, 0), "Domingo fim da tarde (16h-20h)"),
                    PeakWindow(h(21, 0), h(23, 59), "Noite (21h-00h)")
                ),
                peakTips = listOf(
                    "Manhã (5h-9h): fluxo intenso de deslocamento para Trindade e Goiânia.",
                    "Almoço (11h45-14h): alta demanda no centro para alimentação, clínicas e empresas.",
                    "Tarde/noite (16h30-20h): maior pico com retorno do trabalho e saída de escolas."
                ),
                offPeakTip = "Fora do pico, priorize pausa estratégica e retorne nos horários de maior fluxo.",
                weekendTip = "Sexta/sábado à noite (21h-2h) e domingo 16h-20h tendem a elevar demanda com lazer, eventos e romeiros."
            )
        )
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
