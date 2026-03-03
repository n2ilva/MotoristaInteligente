package com.example.motoristainteligente

import android.content.Context
import android.location.Geocoder
import java.util.Locale

data class ResolvedRegion(
    val city: String?,
    val neighborhood: String?
)

fun resolveRegionFromCoordinates(context: Context, latitude: Double, longitude: Double): ResolvedRegion? {
    val geocoder = Geocoder(context, Locale.forLanguageTag("pt-BR"))
    @Suppress("DEPRECATION")
    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
    if (addresses.isNullOrEmpty()) return null

    val addr = addresses[0]
    return ResolvedRegion(
        city = addr.locality ?: addr.subAdminArea ?: addr.adminArea,
        neighborhood = addr.subLocality
    )
}
