package com.example.motoristainteligente

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@Composable
fun DriverSettingsCard(
    firestoreManager: FirestoreManager? = null
) {
    val context = LocalContext.current
    val prefs = remember { DriverPreferences(context) }
    prefs.firestoreManager = firestoreManager

    var minPricePerKm by remember { mutableFloatStateOf(prefs.minPricePerKm.toFloat()) }
    var minEarningsPerHour by remember { mutableFloatStateOf(prefs.minEarningsPerHour.toFloat()) }
    var maxPickupDistance by remember { mutableFloatStateOf(prefs.maxPickupDistance.toFloat()) }
    var maxRideDistance by remember { mutableFloatStateOf(prefs.maxRideDistance.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF6F00).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Suas Preferências",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = {
                        prefs.resetToDefaults()
                        minPricePerKm = DriverPreferences.DEFAULT_MIN_PRICE_PER_KM.toFloat()
                        minEarningsPerHour = DriverPreferences.DEFAULT_MIN_EARNINGS_PER_HOUR.toFloat()
                        maxPickupDistance = DriverPreferences.DEFAULT_MAX_PICKUP_DISTANCE.toFloat()
                        maxRideDistance = DriverPreferences.DEFAULT_MAX_RIDE_DISTANCE.toFloat()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Resetar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingSlider(
                label = "Valor mínimo por km",
                value = minPricePerKm,
                valueText = String.format("R$ %.2f/km", minPricePerKm),
                min = DriverPreferences.MIN_PRICE_PER_KM_FLOOR.toFloat(),
                max = DriverPreferences.MIN_PRICE_PER_KM_CEIL.toFloat(),
                steps = 44,
                onValueChange = { minPricePerKm = it },
                onValueChangeFinished = {
                    prefs.minPricePerKm = minPricePerKm.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Ganho mínimo por hora",
                value = minEarningsPerHour,
                valueText = String.format("R$ %.0f/h", minEarningsPerHour),
                min = DriverPreferences.MIN_EARNINGS_FLOOR.toFloat(),
                max = DriverPreferences.MIN_EARNINGS_CEIL.toFloat(),
                steps = 18,
                onValueChange = { minEarningsPerHour = it },
                onValueChangeFinished = {
                    prefs.minEarningsPerHour = minEarningsPerHour.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Distância máx. para buscar cliente",
                value = maxPickupDistance,
                valueText = String.format("%.1f km", maxPickupDistance),
                min = DriverPreferences.MAX_PICKUP_FLOOR.toFloat(),
                max = DriverPreferences.MAX_PICKUP_CEIL.toFloat(),
                steps = 38,
                onValueChange = { maxPickupDistance = it },
                onValueChangeFinished = {
                    prefs.maxPickupDistance = maxPickupDistance.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Distância máx. da corrida",
                value = maxRideDistance,
                valueText = String.format("%.0f km", maxRideDistance),
                min = DriverPreferences.MAX_RIDE_DISTANCE_FLOOR.toFloat(),
                max = DriverPreferences.MAX_RIDE_DISTANCE_CEIL.toFloat(),
                steps = 19,
                onValueChange = { maxRideDistance = it },
                onValueChangeFinished = {
                    prefs.maxRideDistance = maxRideDistance.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF424242).copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = "📌 ${prefs.getSummary()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
