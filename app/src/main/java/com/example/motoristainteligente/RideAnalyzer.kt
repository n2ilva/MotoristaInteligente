package com.example.motoristainteligente

import java.util.Calendar

/**
 * Motor de análise de corridas.
 *
 * Calcula score (0-100) baseado em:
 * - Preço por km efetivo (inclui deslocamento até embarque)
 * - Estimativa de ganho por hora
 * - Distância até o ponto de embarque
 * - Horário da corrida (pico/fora de pico)
 *
 * Referências de preço baseadas no mercado brasileiro.
 */
object RideAnalyzer {

    // Valores de referência (atualizáveis via internet ou preferências do motorista)
    private var referencePricePerKm = 1.50   // R$/km mínimo aceitável
    private var fuelCostPerKm = 0.55         // R$/km custo de combustível
    private var maintenanceCostPerKm = 0.20  // R$/km custo de manutenção
    private var minEarningsPerHour = 20.0    // R$/hora mínimo aceitável
    private var maxPickupDistanceKm = 5.0    // Distância máxima aceitável para embarque
    private var maxRideDistanceKm = 50.0     // Distância máxima da corrida

    fun analyze(rideData: RideData, pickupDistanceKm: Double): RideAnalysis {
        // Distância total = corrida + deslocamento até embarque
        val totalDistanceKm = (rideData.distanceKm + pickupDistanceKm).coerceAtLeast(0.1)

        // Preço por km total (dist corrida + dist pickup)
        val pricePerKm = rideData.ridePrice / totalDistanceKm

        // Preço efetivo (mesmo cálculo, mantido para compatibilidade)
        val effectivePricePerKm = pricePerKm

        // Tempo estimado total (inclui deslocamento)
        // Usa tempo de pickup extraído da tela se disponível, senão estima por velocidade urbana
        val pickupTimeMin = rideData.pickupTimeMin?.toDouble()
            ?: ((pickupDistanceKm / 30.0) * 60) // ~30km/h média urbana
        val totalTimeMin = rideData.estimatedTimeMin + pickupTimeMin
        val earningsPerHour = if (totalTimeMin > 0) {
            (rideData.ridePrice / totalTimeMin) * 60
        } else 0.0

        // Fator horário
        val timeFactor = getTimeOfDayFactor()
        val adjustedReference = referencePricePerKm / timeFactor

        // Componentes do score
        val priceScore = calculatePriceScore(effectivePricePerKm, adjustedReference)
        val earningsScore = calculateEarningsScore(earningsPerHour)
        val pickupScore = calculatePickupScore(pickupDistanceKm)
        val timeBonus = calculateTimeBonus()

        // Score final ponderado
        val score = (priceScore * 0.40 +
                earningsScore * 0.30 +
                pickupScore * 0.20 +
                timeBonus * 0.10).toInt().coerceIn(0, 100)

        // Verificar limites definidos pelo motorista
        val exceedsPickup = pickupDistanceKm > maxPickupDistanceKm
        val exceedsRideDistance = rideData.distanceKm > maxRideDistanceKm
        val belowMinPriceKm = effectivePricePerKm < referencePricePerKm * 0.6
        val belowMinHourly = earningsPerHour < minEarningsPerHour * 0.5

        // Se excede limites do motorista, penalizar score
        var finalScore = score
        if (exceedsPickup) finalScore = (finalScore * 0.6).toInt()
        if (exceedsRideDistance) finalScore = (finalScore * 0.7).toInt()
        finalScore = finalScore.coerceIn(0, 100)

        // Recomendação (usa finalScore)
        val recommendation = when {
            exceedsPickup || exceedsRideDistance -> Recommendation.NOT_WORTH_IT
            finalScore >= 60 -> Recommendation.WORTH_IT
            finalScore >= 40 -> Recommendation.NEUTRAL
            else -> Recommendation.NOT_WORTH_IT
        }

        // Motivos
        val reasons = buildReasons(
            pricePerKm, effectivePricePerKm,
            earningsPerHour, pickupDistanceKm, adjustedReference,
            exceedsPickup, exceedsRideDistance
        )

        return RideAnalysis(
            rideData = rideData,
            pricePerKm = pricePerKm,
            effectivePricePerKm = effectivePricePerKm,
            referencePricePerKm = adjustedReference,
            estimatedEarningsPerHour = earningsPerHour,
            pickupDistanceKm = pickupDistanceKm,
            score = finalScore,
            recommendation = recommendation,
            reasons = reasons
        )
    }

    private fun calculatePriceScore(effectivePrice: Double, reference: Double): Double {
        if (reference <= 0) return 50.0
        val ratio = effectivePrice / reference
        return (ratio * 50).coerceIn(0.0, 100.0)
    }

    private fun calculateEarningsScore(earningsPerHour: Double): Double {
        if (minEarningsPerHour <= 0) return 50.0
        val ratio = earningsPerHour / minEarningsPerHour
        return (ratio * 50).coerceIn(0.0, 100.0)
    }

    private fun calculatePickupScore(pickupDistanceKm: Double): Double {
        return when {
            pickupDistanceKm <= 1.0 -> 100.0
            pickupDistanceKm <= 2.0 -> 80.0
            pickupDistanceKm <= 3.0 -> 60.0
            pickupDistanceKm <= 5.0 -> 40.0
            pickupDistanceKm <= 8.0 -> 20.0
            else -> 5.0
        }
    }

    private fun calculateTimeBonus(): Double {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 7..9 -> 80.0     // Rush matutino
            in 17..20 -> 90.0   // Rush noturno (melhor)
            in 22..23, in 0..5 -> 70.0  // Madrugada (possível surge)
            in 11..13 -> 60.0   // Almoço
            else -> 40.0        // Fora de pico
        }
    }

    private fun getTimeOfDayFactor(): Double {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 7..9 -> 1.2      // Rush matutino
            in 17..20 -> 1.3    // Rush noturno
            in 22..23, in 0..5 -> 1.15  // Madrugada
            else -> 1.0         // Normal
        }
    }

    private fun buildReasons(
        pricePerKm: Double,
        effectivePricePerKm: Double,
        earningsPerHour: Double,
        pickupDistanceKm: Double,
        reference: Double,
        exceedsPickup: Boolean = false,
        exceedsRideDistance: Boolean = false
    ): List<String> {
        val reasons = mutableListOf<String>()

        if (exceedsPickup) {
            reasons.add("⛔ Deslocamento excede seu limite (${String.format("%.1f", maxPickupDistanceKm)}km)")
        }
        if (exceedsRideDistance) {
            reasons.add("⛔ Corrida excede distância máxima (${String.format("%.0f", maxRideDistanceKm)}km)")
        }

        if (effectivePricePerKm >= reference * 1.2) {
            reasons.add("Preço/km acima da média")
        } else if (effectivePricePerKm < reference * 0.8) {
            reasons.add("Preço/km abaixo da média")
        }

        if (earningsPerHour >= minEarningsPerHour * 1.3) {
            reasons.add("Boa rentabilidade por hora")
        } else if (earningsPerHour < minEarningsPerHour * 0.7) {
            reasons.add("Baixa rentabilidade por hora")
        }

        if (pickupDistanceKm > maxPickupDistanceKm) {
            reasons.add("Embarque muito distante (${String.format("%.1f", pickupDistanceKm)}km)")
        } else if (pickupDistanceKm <= 1.5) {
            reasons.add("Embarque próximo")
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 17..20 || hour in 7..9) {
            reasons.add("Horário de pico")
        } else if (hour in 22..23 || hour in 0..5) {
            reasons.add("Horário noturno")
        }

        if (reasons.isEmpty()) {
            reasons.add("Corrida dentro da média")
        }

        return reasons
    }

    /**
     * Atualiza valores de referência (pode ser chamado com dados da internet)
     */
    fun updateReferences(
        newPricePerKm: Double = referencePricePerKm,
        newMinHourly: Double = minEarningsPerHour,
        newFuelCost: Double = fuelCostPerKm,
        newMaintenanceCost: Double = maintenanceCostPerKm
    ) {
        referencePricePerKm = newPricePerKm
        minEarningsPerHour = newMinHourly
        fuelCostPerKm = newFuelCost
        maintenanceCostPerKm = newMaintenanceCost
    }

    /**
     * Atualiza limites de distância (preferências do motorista).
     */
    fun updatePickupLimit(maxPickup: Double, maxRide: Double = maxRideDistanceKm) {
        maxPickupDistanceKm = maxPickup
        maxRideDistanceKm = maxRide
    }

    /**
     * Retorna os valores atuais de referência para exibição na UI.
     */
    fun getCurrentReferences(): Map<String, Double> = mapOf(
        "minPricePerKm" to referencePricePerKm,
        "minEarningsPerHour" to minEarningsPerHour,
        "maxPickupDistanceKm" to maxPickupDistanceKm,
        "maxRideDistanceKm" to maxRideDistanceKm
    )
}
