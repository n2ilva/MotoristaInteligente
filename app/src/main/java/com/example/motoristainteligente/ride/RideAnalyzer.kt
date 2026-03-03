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
        val ridePrice = rideData.ridePrice.toDouble()

        // Preço por km total (dist corrida + dist pickup)
        val pricePerKm = (ridePrice / totalDistanceKm).toFloat()

        // Preço efetivo (mesmo cálculo, mantido para compatibilidade)
        val effectivePricePerKm = pricePerKm

        // Tempo estimado total (inclui deslocamento)
        // Usa tempo de pickup extraído da tela se disponível, senão estima por velocidade urbana
        val pickupTimeMin = rideData.pickupTimeMin?.toDouble()
            ?: ((pickupDistanceKm / 30.0) * 60) // ~30km/h média urbana
        val totalTimeMin = rideData.estimatedTimeMin + pickupTimeMin
        val earningsPerHour = if (totalTimeMin > 0) {
            (ridePrice / totalTimeMin) * 60
        } else 0.0
        val earningsPerHourFloat = earningsPerHour.toFloat()

        // Fator horário
        val timeFactor = getTimeOfDayFactor()
        val adjustedReference = referencePricePerKm / timeFactor

        // Recomendação baseada SOMENTE no mínimo de R$/km configurado pelo motorista
        val recommendation = if (effectivePricePerKm.toDouble() >= referencePricePerKm) {
            Recommendation.WORTH_IT
        } else {
            Recommendation.NOT_WORTH_IT
        }

        val exceedsPickupLimit = pickupDistanceKm > maxPickupDistanceKm
        val exceedsRideDistanceLimit = rideData.distanceKm > maxRideDistanceKm

        // Motivos
        val reasons = buildReasons(
            effectivePricePerKm = effectivePricePerKm,
            earningsPerHour = earningsPerHour,
            reference = adjustedReference,
            exceedsPickup = exceedsPickupLimit,
            exceedsRideDistance = exceedsRideDistanceLimit
        )

        return RideAnalysis(
            rideData = rideData,
            pricePerKm = pricePerKm,
            effectivePricePerKm = effectivePricePerKm,
            referencePricePerKm = adjustedReference,
            estimatedEarningsPerHour = earningsPerHourFloat,
            pickupDistanceKm = pickupDistanceKm,
            score = 0,
            recommendation = recommendation,
            reasons = reasons
        )
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
        effectivePricePerKm: Float,
        earningsPerHour: Double,
        reference: Double,
        exceedsPickup: Boolean = false,
        exceedsRideDistance: Boolean = false
    ): List<String> {
        val reasons = mutableListOf<String>()

        if (earningsPerHour < minEarningsPerHour) {
            reasons.add("R$/h abaixo do mínimo")
        }

        if (exceedsPickup) {
            reasons.add("Passageiro muito longe")
        }

        if (exceedsRideDistance) {
            reasons.add("Corrida longa demais")
        }

        if (effectivePricePerKm.toDouble() < reference) {
            reasons.add("R$/km abaixo do mínimo")
        }

        if (reasons.isEmpty()) {
            reasons.add("Dentro dos seus parâmetros")
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
