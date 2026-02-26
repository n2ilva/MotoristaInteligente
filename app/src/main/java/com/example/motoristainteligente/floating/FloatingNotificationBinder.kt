package com.example.motoristainteligente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat

fun createMainNotificationChannel(service: Service, channelId: String) {
    val channel = NotificationChannel(
        channelId,
        "Motorista Inteligente",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Análise de corridas em tempo real"
    }

    val manager = service.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}

fun createPeakAlertsNotificationChannel(service: Service, channelId: String) {
    val channel = NotificationChannel(
        channelId,
        "Alertas de Pico",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Alertas de pico chegando e pico diminuindo"
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 220, 140, 220)
    }

    val manager = service.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}

fun createNoOffersAlertNotificationChannel(service: Service, channelId: String) {
    val channel = NotificationChannel(
        channelId,
        "Alerta sem ofertas",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Alerta quando ficar 10 minutos sem receber ofertas"
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 300, 180, 300)
    }

    val manager = service.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}

fun createForegroundNotification(service: Service, channelId: String, actionStop: String, contentText: String): Notification {
    val stopIntent = Intent(service, FloatingAnalyticsService::class.java).apply {
        action = actionStop
    }
    val stopPendingIntent = PendingIntent.getService(
        service,
        0,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val openIntent = Intent(service, MainActivity::class.java)
    val openPendingIntent = PendingIntent.getActivity(
        service,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(service, channelId)
        .setContentText(contentText)
        .setSmallIcon(R.drawable.ic_analytics)
        .setContentIntent(openPendingIntent)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPendingIntent)
        .setOngoing(true)
        .build()
}

fun sendPeakAlertNotification(service: Service, channelId: String, id: Int, title: String, message: String) {
    val openIntent = Intent(service, MainActivity::class.java)
    val openPendingIntent = PendingIntent.getActivity(
        service,
        id,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(service, channelId)
        .setSmallIcon(R.drawable.ic_analytics)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setVibrate(longArrayOf(0, 220, 140, 220))
        .setAutoCancel(true)
        .setContentIntent(openPendingIntent)
        .build()

    val manager = service.getSystemService(NotificationManager::class.java)
    manager.notify(id, notification)
}

fun sendHighPriorityAlertNotification(service: Service, channelId: String, id: Int, title: String, message: String) {
    val openIntent = Intent(service, MainActivity::class.java)
    val openPendingIntent = PendingIntent.getActivity(
        service,
        id,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(service, channelId)
        .setSmallIcon(R.drawable.ic_analytics)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .setContentIntent(openPendingIntent)
        .setVibrate(longArrayOf(0, 300, 180, 300))
        .build()

    val manager = service.getSystemService(NotificationManager::class.java)
    manager.notify(id, notification)
}
