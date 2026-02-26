package com.example.motoristainteligente

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Serviço que busca locais com alta demanda de corridas
 * próximos à localização atual do motorista.
 *
 * Usa Google Places API (Nearby Search) para encontrar
 * locais que tipicamente geram demanda de transporte por app.
 */
class DemandHotspotsService(private val context: Context) {

    companion object {
        private const val TAG = "DemandHotspots"
        private const val PLACES_API_BASE = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
        private const val SEARCH_RADIUS_METERS = 8000 // 8km
    }

    /**
     * Um local com potencial de demanda alta para motoristas de app.
     */
    data class Hotspot(
        val name: String,
        val address: String,
        val lat: Double,
        val lng: Double,
        val distanceKm: Double,
        val demandLevel: DemandLevel,
        val demandScore: Int, // 0-100
        val category: String,
        val categoryIcon: String,
        val isOpenNow: Boolean?,
        val rating: Double?,
        val totalRatings: Int
    )

    enum class DemandLevel(val label: String, val color: Long) {
        HIGH("Alta", 0xFF4CAF50),
        MEDIUM("Média", 0xFFFF9800),
        LOW("Baixa", 0xFF9E9E9E)
    }

    /**
     * Categorias de lugares que geram demanda de corridas,
     * com peso base de demanda e horários de pico.
     */
    private data class PlaceCategory(
        val type: String,
        val label: String,
        val icon: String,
        val baseWeight: Int,
        val peakHours: List<IntRange>
    )

    private val categories = listOf(
        PlaceCategory("airport", "Aeroporto", "✈️", 95, listOf(5..10, 17..23)),
        PlaceCategory("bus_station", "Rodoviária", "🚌", 80, listOf(5..9, 16..21)),
        PlaceCategory("train_station", "Estação", "🚉", 80, listOf(6..9, 17..20)),
        PlaceCategory("subway_station", "Metrô", "🚇", 70, listOf(6..9, 17..20)),
        PlaceCategory("shopping_mall", "Shopping", "🛍️", 75, listOf(11..14, 17..22)),
        PlaceCategory("hospital", "Hospital", "🏥", 70, listOf(6..9, 12..14, 17..19)),
        PlaceCategory("university", "Universidade", "🎓", 65, listOf(6..8, 11..13, 17..19, 21..23)),
        PlaceCategory("stadium", "Estádio", "🏟️", 60, listOf(15..23)),
        PlaceCategory("night_club", "Balada", "🎵", 55, listOf(22..23, 0..5)),
        PlaceCategory("bar", "Bar", "🍺", 50, listOf(18..23, 0..3)),
        PlaceCategory("tourist_attraction", "Turismo", "📸", 45, listOf(8..18)),
        PlaceCategory("church", "Igreja", "⛪", 40, listOf(7..10, 18..21))
    )

    /**
     * Busca locais com demanda alta próximos à localização atual.
     * Retorna lista ordenada por distância (mais perto primeiro).
     */
    suspend fun fetchHotspots(currentLocation: Location): List<Hotspot> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API Key não encontrada no google-services.json")
            return@withContext emptyList()
        }

        val allHotspots = mutableListOf<Hotspot>()
        val seenPlaceIds = mutableSetOf<String>()
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        for (category in categories) {
            try {
                val url = buildUrl(
                    lat = currentLocation.latitude,
                    lng = currentLocation.longitude,
                    type = category.type,
                    apiKey = apiKey
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Places API retornou $responseCode para ${category.type}")
                    connection.disconnect()
                    continue
                }

                val responseText = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val json = JSONObject(responseText)
                val status = json.optString("status")

                if (status != "OK" && status != "ZERO_RESULTS") {
                    Log.w(TAG, "Places API status=$status para ${category.type}: ${json.optString("error_message")}")
                    continue
                }

                val results = json.optJSONArray("results") ?: continue

                for (i in 0 until results.length().coerceAtMost(5)) {
                    val place = results.getJSONObject(i)
                    val placeId = place.optString("place_id")

                    // Evitar duplicatas
                    if (placeId in seenPlaceIds) continue
                    seenPlaceIds.add(placeId)

                    val name = place.optString("name", "")
                    val vicinity = place.optString("vicinity", "")
                    val geometry = place.optJSONObject("geometry")
                    val location = geometry?.optJSONObject("location")
                    val lat = location?.optDouble("lat") ?: continue
                    val lng = location?.optDouble("lng") ?: continue
                    val rating = if (place.has("rating")) place.optDouble("rating") else null
                    val totalRatings = place.optInt("user_ratings_total", 0)

                    val openNow = place.optJSONObject("opening_hours")?.optBoolean("open_now")

                    // Calcular distância
                    val distanceKm = calculateDistance(
                        currentLocation.latitude, currentLocation.longitude,
                        lat, lng
                    )

                    // Calcular score de demanda
                    val demandScore = calculateDemandScore(
                        category = category,
                        currentHour = currentHour,
                        totalRatings = totalRatings,
                        rating = rating,
                        isOpenNow = openNow,
                        distanceKm = distanceKm
                    )

                    val demandLevel = when {
                        demandScore >= 65 -> DemandLevel.HIGH
                        demandScore >= 40 -> DemandLevel.MEDIUM
                        else -> DemandLevel.LOW
                    }

                    allHotspots.add(
                        Hotspot(
                            name = name,
                            address = vicinity,
                            lat = lat,
                            lng = lng,
                            distanceKm = distanceKm,
                            demandLevel = demandLevel,
                            demandScore = demandScore,
                            category = category.label,
                            categoryIcon = category.icon,
                            isOpenNow = openNow,
                            rating = rating,
                            totalRatings = totalRatings
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar ${category.type}: ${e.message}")
            }
        }

        // Ordenar por distância (mais perto primeiro)
        allHotspots.sortedBy { it.distanceKm }
    }

    private fun buildUrl(lat: Double, lng: Double, type: String, apiKey: String): String {
        return "$PLACES_API_BASE?location=$lat,$lng&radius=$SEARCH_RADIUS_METERS&type=$type&language=pt-BR&key=$apiKey"
    }

    /**
     * Calcula score de demanda (0-100) baseado em múltiplos fatores.
     */
    private fun calculateDemandScore(
        category: PlaceCategory,
        currentHour: Int,
        totalRatings: Int,
        rating: Double?,
        isOpenNow: Boolean?,
        distanceKm: Double
    ): Int {
        var score = category.baseWeight.toDouble()

        // Bonus por horário de pico (+20)
        val isInPeakHour = category.peakHours.any { range ->
            if (range.first <= range.last) {
                currentHour in range
            } else {
                // Range que cruza meia-noite (ex: 22..3)
                currentHour >= range.first || currentHour <= range.last
            }
        }
        if (isInPeakHour) score += 20

        // Bonus por popularidade (até +15 baseado em avaliações)
        val popularityBonus = when {
            totalRatings > 5000 -> 15
            totalRatings > 2000 -> 12
            totalRatings > 500 -> 8
            totalRatings > 100 -> 4
            else -> 0
        }
        score += popularityBonus

        // Bonus se está aberto agora (+10)
        if (isOpenNow == true) score += 10

        // Penalidade se está fechado (-20)
        if (isOpenNow == false) score -= 20

        // Bonus por proximidade (mais perto = mais relevante, até +10)
        val proximityBonus = when {
            distanceKm <= 1.0 -> 10
            distanceKm <= 3.0 -> 6
            distanceKm <= 5.0 -> 3
            else -> 0
        }
        score += proximityBonus

        return score.toInt().coerceIn(0, 100)
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble() / 1000.0
    }

    /**
     * Extrai API key do google-services.json (via recursos do app).
     */
    private fun getApiKey(): String? {
        return try {
            val resId = context.resources.getIdentifier(
                "google_api_key", "string", context.packageName
            )
            if (resId != 0) context.getString(resId) else null
        } catch (_: Exception) {
            null
        }
    }
}
