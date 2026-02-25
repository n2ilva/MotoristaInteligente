package com.example.motoristainteligente

import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rastreia a demanda de corridas em tempo real.
 *
 * Armazena histórico de corridas recebidas e calcula métricas:
 * - Frequência de corridas (corridas/hora)
 * - Tendência de demanda (subindo, caindo, estável)
 * - Preço médio das últimas corridas
 * - Preço/km médio recente
 *
 * Usado pelo PauseAdvisor e pelo card de análise para
 * recomendar se o motorista deve continuar ou pausar.
 */
object DemandTracker {

    // Histórico das últimas corridas recebidas (até 200 para 2h de dados)
    private val rideHistory = CopyOnWriteArrayList<RideSnapshot>()
    private const val MAX_HISTORY = 200

    // Janelas de tempo para análise (em milissegundos)
    private const val WINDOW_SHORT = 15 * 60 * 1000L   // 15 min
    private const val WINDOW_MEDIUM = 30 * 60 * 1000L   // 30 min
    private const val WINDOW_LONG = 60 * 60 * 1000L     // 1 hora

    // Timestamp de quando o motorista começou a rodar
    private var sessionStartTime = 0L
    private var totalEarnings = 0.0
    private var completedRides = 0

    /**
     * Snapshot simplificado de uma corrida recebida.
     */
    data class RideSnapshot(
        val timestamp: Long,
        val price: Double,
        val distanceKm: Double,
        val estimatedTimeMin: Int,
        val pricePerKm: Double,
        val appSource: AppSource,
        val wasAccepted: Boolean = false
    )

    /**
     * Indicadores de demanda calculados.
     */
    data class DemandStats(
        val ridesLast15Min: Int,
        val ridesLast30Min: Int,
        val ridesLastHour: Int,
        val ridesUberLast30Min: Int,
        val rides99Last30Min: Int,
        val ridesPerHour: Double,
        val avgPriceLast15Min: Double,
        val avgPricePerKmLast15Min: Double,
        val avgPriceLast30Min: Double,
        val avgPricePerKmLast30Min: Double,
        val avgRideTimeLast30Min: Double,
        val trend: DemandTrend,
        val priceTrend: PriceTrend,
        val demandLevel: DemandLevel,
        val sessionDurationMin: Long,
        val sessionTotalEarnings: Double,
        val sessionAvgEarningsPerHour: Double,
        // Per-platform stats (ofertas recebidas)
        val totalRidesUber: Int,
        val totalRides99: Int,
        val avgPriceUber: Double,
        val avgPrice99: Double,
        val avgTimeUber: Double,
        val avgTime99: Double,
        // Per-platform stats (corridas ACEITAS)
        val acceptedRidesUber: Int,
        val acceptedRides99: Int,
        val acceptedRidesTotal: Int,
        // Accepted rides quality
        val acceptedBelowAverage: Boolean,
        val noRidesLast15Min: Boolean,
        // Ofertas por hora por plataforma
        val offersPerHourUber: Int,
        val offersPerHour99: Int,
        // Ofertas na hora anterior (para comparação hora-a-hora)
        val ridesPreviousHour: Int
    )

    enum class DemandTrend(val displayText: String, val emoji: String) {
        RISING("Subindo", "📈"),
        FALLING("Caindo", "📉"),
        STABLE("Estável", "➡️")
    }

    enum class PriceTrend(val displayText: String, val emoji: String) {
        INCREASING("Subindo", "💹"),
        DECREASING("Caindo", "🔻"),
        STABLE("Estável", "➡️")
    }

    enum class DemandLevel(val displayText: String, val emoji: String, val color: Long) {
        HIGH("Alta", "🔥", 0xFF4CAF50),
        MEDIUM("Média", "⚡", 0xFFFFC107),
        LOW("Baixa", "❄️", 0xFFF44336),
        UNKNOWN("—", "❓", 0xFF9E9E9E)
    }

    /**
     * Iniciar nova sessão de trabalho.
     */
    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        totalEarnings = 0.0
        completedRides = 0
        rideHistory.clear()
    }

    /**
     * Registra uma corrida que apareceu.
     */
    fun recordRideOffer(rideData: RideData) {
        val snapshot = RideSnapshot(
            timestamp = System.currentTimeMillis(),
            price = rideData.ridePrice,
            distanceKm = rideData.distanceKm,
            estimatedTimeMin = rideData.estimatedTimeMin,
            pricePerKm = if (rideData.distanceKm > 0) rideData.ridePrice / (rideData.distanceKm + (rideData.pickupDistanceKm ?: 0.0)).coerceAtLeast(0.1) else 0.0,
            appSource = rideData.appSource
        )

        rideHistory.add(snapshot)

        // Limitar histórico
        while (rideHistory.size > MAX_HISTORY) {
            rideHistory.removeAt(0)
        }
    }

    /**
     * Registra que uma corrida foi aceita (para cálculo de ganho da sessão).
     */
    fun recordRideAccepted(price: Double) {
        totalEarnings += price
        completedRides++
    }

    /**
     * Marca a oferta mais recente de um determinado app como aceita.
     * Chamado quando o serviço de acessibilidade detecta sinais de aceitação
     * (tela mudou para modo "a caminho", "corrida aceita", etc.)
     */
    fun markLastOfferAsAccepted(appSource: AppSource): Boolean {
        // Procurar a oferta mais recente (não aceita) desse app nos últimos 60s
        val now = System.currentTimeMillis()
        val candidate = rideHistory.lastOrNull {
            it.appSource == appSource && !it.wasAccepted && (now - it.timestamp) < 60_000L
        } ?: return false

        // Substituir com wasAccepted = true
        val idx = rideHistory.indexOf(candidate)
        if (idx >= 0) {
            rideHistory[idx] = candidate.copy(wasAccepted = true)
            recordRideAccepted(candidate.price)
            return true
        }
        return false
    }

    /**
     * Calcula as estatísticas atuais de demanda.
     */
    fun getStats(): DemandStats {
        val now = System.currentTimeMillis()

        val last15 = rideHistory.filter { now - it.timestamp <= WINDOW_SHORT }
        val last30 = rideHistory.filter { now - it.timestamp <= WINDOW_MEDIUM }
        val lastHour = rideHistory.filter { now - it.timestamp <= WINDOW_LONG }

        // Frequência
        val sessionDurationMs = if (sessionStartTime > 0) now - sessionStartTime else WINDOW_LONG
        val sessionHours = sessionDurationMs.toDouble() / (60 * 60 * 1000)
        val ridesPerHour = if (sessionHours > 0) lastHour.size / sessionHours.coerceAtMost(1.0) else 0.0

        // Médias de preço
        val avgPrice15 = last15.map { it.price }.averageOrZero()
        val avgPriceKm15 = last15.map { it.pricePerKm }.averageOrZero()
        val avgPrice30 = last30.map { it.price }.averageOrZero()
        val avgPriceKm30 = last30.map { it.pricePerKm }.averageOrZero()
        val avgTime30 = last30.map { it.estimatedTimeMin.toDouble() }.averageOrZero()

        val previousHour = rideHistory.filter { now - it.timestamp in WINDOW_LONG..(WINDOW_LONG * 2) }

        val ridesUber30 = last30.count { it.appSource == AppSource.UBER }
        val rides9930 = last30.count { it.appSource == AppSource.NINETY_NINE }

        // Ofertas por hora por plataforma
        val offersPerHourUber = lastHour.count { it.appSource == AppSource.UBER }
        val offersPerHour99 = lastHour.count { it.appSource == AppSource.NINETY_NINE }

        // Per-platform metrics (all history)
        val uberRides = rideHistory.filter { it.appSource == AppSource.UBER }
        val ninetyNineRides = rideHistory.filter { it.appSource == AppSource.NINETY_NINE }
        val avgPriceUber = uberRides.map { it.price }.averageOrZero()
        val avgPrice99 = ninetyNineRides.map { it.price }.averageOrZero()
        val avgTimeUber = uberRides.map { it.estimatedTimeMin.toDouble() }.averageOrZero()
        val avgTime99 = ninetyNineRides.map { it.estimatedTimeMin.toDouble() }.averageOrZero()

        // Accepted rides quality check
        val acceptedRides = rideHistory.filter { it.wasAccepted }
        val avgPriceAll = rideHistory.map { it.pricePerKm }.averageOrZero()
        val avgPriceAccepted = acceptedRides.map { it.pricePerKm }.averageOrZero()
        val acceptedBelowAvg = acceptedRides.isNotEmpty() && avgPriceAccepted < avgPriceAll * 0.85

        // Tendência de demanda: comparar 15min recentes vs 15min anteriores
        val trend = calculateDemandTrend(now)
        val priceTrend = calculatePriceTrend(now)

        // Nível de demanda (mais estável usando 30 min)
        val demandLevel = when {
            last30.size >= 8 -> DemandLevel.HIGH
            last30.size >= 4 -> DemandLevel.MEDIUM
            last30.size >= 1 -> DemandLevel.LOW
            lastHour.isEmpty() -> DemandLevel.UNKNOWN
            else -> DemandLevel.LOW
        }

        // Sessão
        val sessionMin = if (sessionStartTime > 0) (now - sessionStartTime) / (60 * 1000) else 0
        val sessionAvgPerHour = if (sessionHours > 0.1) totalEarnings / sessionHours else 0.0

        // Corridas aceitas por plataforma
        val acceptedUber = rideHistory.count { it.appSource == AppSource.UBER && it.wasAccepted }
        val accepted99 = rideHistory.count { it.appSource == AppSource.NINETY_NINE && it.wasAccepted }
        val acceptedTotal = acceptedUber + accepted99

        return DemandStats(
            ridesLast15Min = last15.size,
            ridesLast30Min = last30.size,
            ridesLastHour = lastHour.size,
            ridesUberLast30Min = ridesUber30,
            rides99Last30Min = rides9930,
            ridesPerHour = ridesPerHour,
            avgPriceLast15Min = avgPrice15,
            avgPricePerKmLast15Min = avgPriceKm15,
            avgPriceLast30Min = avgPrice30,
            avgPricePerKmLast30Min = avgPriceKm30,
            avgRideTimeLast30Min = avgTime30,
            trend = trend,
            priceTrend = priceTrend,
            demandLevel = demandLevel,
            sessionDurationMin = sessionMin,
            sessionTotalEarnings = totalEarnings,
            sessionAvgEarningsPerHour = sessionAvgPerHour,
            totalRidesUber = uberRides.size,
            totalRides99 = ninetyNineRides.size,
            avgPriceUber = avgPriceUber,
            avgPrice99 = avgPrice99,
            avgTimeUber = avgTimeUber,
            avgTime99 = avgTime99,
            acceptedRidesUber = acceptedUber,
            acceptedRides99 = accepted99,
            acceptedRidesTotal = acceptedTotal,
            acceptedBelowAverage = acceptedBelowAvg,
            noRidesLast15Min = last15.isEmpty(),
            offersPerHourUber = offersPerHourUber,
            offersPerHour99 = offersPerHour99,
            ridesPreviousHour = previousHour.size
        )
    }

    /**
     * Calcula tendência de demanda comparando ofertas hora-a-hora.
     * Compara a hora atual com a hora anterior.
     */
    private fun calculateDemandTrend(now: Long): DemandTrend {
        val currentHour = rideHistory.count {
            now - it.timestamp in 0..WINDOW_LONG
        }
        val previousHour = rideHistory.count {
            now - it.timestamp in WINDOW_LONG..(WINDOW_LONG * 2)
        }

        return when {
            previousHour == 0 && currentHour == 0 -> DemandTrend.STABLE
            previousHour == 0 -> if (currentHour > 0) DemandTrend.RISING else DemandTrend.STABLE
            currentHour > previousHour -> DemandTrend.RISING
            currentHour < previousHour -> DemandTrend.FALLING
            else -> DemandTrend.STABLE
        }
    }

    /**
     * Calcula tendência de preço comparando médias recentes.
     */
    private fun calculatePriceTrend(now: Long): PriceTrend {
        val recentPrices = rideHistory
            .filter { now - it.timestamp <= WINDOW_SHORT }
            .map { it.pricePerKm }

        val previousPrices = rideHistory
            .filter { now - it.timestamp in WINDOW_SHORT..(WINDOW_SHORT * 2) }
            .map { it.pricePerKm }

        if (recentPrices.isEmpty() || previousPrices.isEmpty()) return PriceTrend.STABLE

        val recentAvg = recentPrices.average()
        val previousAvg = previousPrices.average()
        val diff = (recentAvg - previousAvg) / previousAvg

        return when {
            diff > 0.10 -> PriceTrend.INCREASING   // +10%
            diff < -0.10 -> PriceTrend.DECREASING   // -10%
            else -> PriceTrend.STABLE
        }
    }

    fun getRideCount(): Int = rideHistory.size
    fun getSessionStartTime(): Long = sessionStartTime

    /**
     * Restaura o histórico de ofertas do dia a partir de dados do Firebase.
     * Chamado ao abrir o app para que os cards mostrem dados do dia atual,
     * mesmo que o serviço não esteja ativo.
     * Só restaura se o histórico atual estiver vazio (evita duplicatas).
     */
    fun restoreFromFirebase(offers: List<RideSnapshot>) {
        if (rideHistory.isNotEmpty()) return // já tem dados na memória
        if (offers.isEmpty()) return

        rideHistory.addAll(offers.sortedBy { it.timestamp })

        // Limitar histórico
        while (rideHistory.size > MAX_HISTORY) {
            rideHistory.removeAt(0)
        }

        // Se não tem sessão ativa, marcar o timestamp da oferta mais antiga como início
        if (sessionStartTime == 0L) {
            sessionStartTime = offers.minOf { it.timestamp }
        }
    }

    private fun List<Double>.averageOrZero(): Double {
        return if (isEmpty()) 0.0 else average()
    }
}
