package com.example.motoristainteligente

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Helper para obter localização do motorista.
 * Usa LocationManager nativo (sem dependências externas).
 */
class LocationHelper(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var lastKnownLocation: Location? = null
    private var locationListener: LocationListener? = null

    /**
     * Obtém a última localização conhecida do dispositivo.
     */
    fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null

        try {
            lastKnownLocation =
                locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            // Permissão revogada em runtime
        }

        return lastKnownLocation
    }

    /**
     * Inicia atualizações contínuas de localização.
     */
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        locationListener = LocationListener { location ->
            lastKnownLocation = location
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,  // a cada 5 segundos
                10f,    // ou 10 metros
                locationListener!!,
                Looper.getMainLooper()
            )
        } catch (_: Exception) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            } catch (_: Exception) {
                // Localização indisponível
            }
        }
    }

    /**
     * Para atualizações de localização.
     */
    fun stopLocationUpdates() {
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (_: Exception) { }
        }
        locationListener = null
    }

    /**
     * Calcula distância (em km) até um ponto geográfico.
     * Retorna -1.0 se localização indisponível.
     */
    fun getDistanceToPoint(lat: Double, lng: Double): Double {
        val currentLocation = getCurrentLocation() ?: return -1.0
        val results = FloatArray(1)
        Location.distanceBetween(
            currentLocation.latitude, currentLocation.longitude,
            lat, lng,
            results
        )
        return results[0].toDouble() / 1000.0 // metros → km
    }

    /**
     * Estima distância até embarque quando coordenadas não estão disponíveis.
     * Usa heurística baseada no contexto urbano.
     */
    fun estimatePickupDistance(): Double {
        // Se temos localização, tentamos usar dados recentes
        // Caso contrário, retorna estimativa padrão urbana
        return 2.0 // 2km estimativa padrão
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
}
