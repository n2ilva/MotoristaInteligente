package com.example.motoristainteligente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.location.Geocoder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Serviço flutuante que:
 * - Exibe um botão flutuante arrastável sobre outros apps
 * - Recebe dados de corrida do AccessibilityService
 * - Analisa a corrida e exibe um mini card com resultado
 * - Monitora demanda e recomenda pausas inteligentes
 * - Roda como foreground service com notificação persistente
 */
class FloatingAnalyticsService : Service() {

    companion object {
        var instance: FloatingAnalyticsService? = null
        const val CHANNEL_ID = "motorista_inteligente_channel"
        const val ALERT_CHANNEL_ID = "motorista_inteligente_alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID_UPCOMING = 2001
        const val ALERT_NOTIFICATION_ID_DECLINING = 2002
        const val ACTION_STOP = "com.example.motoristainteligente.STOP_SERVICE"
        private const val RIDE_DEBOUNCE_MS = 300L // 300ms para agrupar eventos rápidos
        private const val NO_OFFERS_ALERT_WINDOW_MS = 15 * 60 * 1000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var locationHelper: LocationHelper
    private lateinit var marketDataService: MarketDataService
    private lateinit var driverPreferences: DriverPreferences
    private lateinit var firestoreManager: FirestoreManager

    private var floatingButton: View? = null
    private var analysisCard: View? = null
    private var statusCard: View? = null
    private var statusCardOverlay: View? = null
    private var isCardVisible = false
    private var isStatusCardVisible = false
    private var onlineSessionStartMs: Long = 0L
    private var lastResolvedCity: String? = null
    private var lastResolvedNeighborhood: String? = null
    private var lastUpcomingAlertKey: String? = null
    private var lastDecliningAlertKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideCardRunnable = Runnable { hideAnalysisCard() }
    private val hideStatusCardRunnable = Runnable { hideStatusCard() }

    // Atualização periódica do status de demanda na notificação
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            updateNotificationWithStats()
            maybeNotifyPeakEvents()
            handler.postDelayed(this, 60_000) // A cada 1 min
        }
    }

    // Atualização periódica de localização do motorista (a cada 15 min)
    private val locationUpdateRunnable = object : Runnable {
        override fun run() {
            updateDriverLocationInFirebase()
            handler.postDelayed(this, 15 * 60 * 1000L) // A cada 15 min
        }
    }

    // ========================
    // Lifecycle
    // ========================

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        locationHelper = LocationHelper(this)
        locationHelper.startLocationUpdates()

        // Carregar preferências do motorista e aplicar no analisador
        driverPreferences = DriverPreferences(this)

        // Inicializar Firebase
        firestoreManager = FirestoreManager(this)
        firestoreManager.signInAnonymously {
            driverPreferences.firestoreManager = firestoreManager
        }

        driverPreferences.applyToAnalyzer()

        // Iniciar rastreamento de demanda
        DemandTracker.startSession()

        // Iniciar serviço de dados de mercado
        marketDataService = MarketDataService(this)
        marketDataService.addListener { marketInfo ->
            // Atualizar PauseAdvisor com dados de mercado por hora
            val hourData = mutableMapOf<Int, PauseAdvisor.MarketHourData>()
            for (h in 0..23) {
                hourData[h] = PauseAdvisor.MarketHourData(
                    hour = h,
                    demandIndex = marketInfo.demandIndex,
                    avgPricePerKm = marketInfo.avgPricePerKm,
                    surgeMultiplier = marketInfo.surgeMultiplier
                )
            }
            PauseAdvisor.updateMarketData(hourData)
        }
        marketDataService.startPeriodicUpdates(locationHelper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AnalysisServiceState.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!AnalysisServiceState.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        AnalysisServiceState.setEnabled(this, true)
        if (onlineSessionStartMs == 0L) {
            onlineSessionStartMs = System.currentTimeMillis()
        }

        createNotificationChannel()
        createPeakAlertsChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        showFloatingButton()

        // Iniciar atualização periódica da notificação com stats
        handler.post(notificationUpdateRunnable)

        // Iniciar atualização periódica de localização (a cada 15 min)
        handler.post(locationUpdateRunnable)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        // Salvar resumo da sessão no Firestore
        try {
            val stats = DemandTracker.getStats()
            if (stats.sessionDurationMin > 1) {
                firestoreManager.saveSessionSummary(stats)
                firestoreManager.saveDriverDailyDemandAnalytics(
                    demandStats = stats,
                    city = lastResolvedCity,
                    neighborhood = lastResolvedNeighborhood,
                    onlineStartMs = onlineSessionStartMs,
                    onlineEndMs = System.currentTimeMillis()
                )
            }
        } catch (_: Exception) { }
        // Remover localização do motorista (offline)
        try {
            firestoreManager.removeDriverLocation()
        } catch (_: Exception) { }
        locationHelper.stopLocationUpdates()
        marketDataService.stop()
        removeFloatingButton()
        hideAnalysisCard()
        hideStatusCard()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ========================
    // Notification
    // ========================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Motorista Inteligente",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Análise de corridas em tempo real"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createPeakAlertsChannel() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Alertas de Pico",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alertas de pico chegando e pico diminuindo"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return createNotification("Iniciando análise...")
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, FloatingAnalyticsService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motorista Inteligente")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_analytics)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Atualiza a notificação com stats atualizados de demanda.
     */
    private fun updateNotificationWithStats() {
        try {
            val manager = getSystemService(NotificationManager::class.java)

            if (!firestoreManager.isGoogleUser) {
                manager.notify(NOTIFICATION_ID, createNotification("Login necessário"))
                return
            }

            firestoreManager.loadTodayRideOfferStats { firebaseStats ->
                val status = computeFirebaseDemandStatus(firebaseStats)
                manager.notify(NOTIFICATION_ID, createNotification(status.notificationLabel))
            }
        } catch (_: Exception) { }
    }

    private data class FirebaseDemandStatus(
        val cardLabel: String,
        val notificationLabel: String,
        val color: Int
    )

    private data class CurrentTimeInfo(
        val hour: Int,
        val minute: Int,
        val dayOfWeek: Int
    )

    private fun currentTimeInfo(): CurrentTimeInfo {
        val now = Calendar.getInstance()
        return CurrentTimeInfo(
            hour = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE),
            dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        )
    }

    private fun computeFirebaseDemandStatus(stats: FirestoreManager.RideOfferStats): FirebaseDemandStatus {
        if (stats.totalOffersToday == 0 || stats.offersLast3h == 0) {
            return FirebaseDemandStatus(
                cardLabel = "Neutro",
                notificationLabel = "Neutro",
                color = 0xFFFFC107.toInt()
            )
        }

        val lastHour = stats.offersLast1h
        val previousHourEstimated = ((stats.offersLast3h - lastHour).coerceAtLeast(0) / 2.0)

        return when {
            lastHour > previousHourEstimated * 1.10 -> FirebaseDemandStatus(
                cardLabel = "Alta",
                notificationLabel = "Alta",
                color = 0xFF4CAF50.toInt()
            )
            lastHour < previousHourEstimated * 0.90 -> FirebaseDemandStatus(
                cardLabel = "Baixa",
                notificationLabel = "Baixa",
                color = 0xFFF44336.toInt()
            )
            else -> FirebaseDemandStatus(
                cardLabel = "Neutro",
                notificationLabel = "Neutro",
                color = 0xFFFFC107.toInt()
            )
        }
    }

    private fun maybeNotifyPeakEvents() {
        try {
            val now = currentTimeInfo()

            val city = lastResolvedCity ?: marketDataService.getLastMarketInfo()?.regionName
            val signal = PauseAdvisor.getCityPeakSignal(
                cityName = city,
                hour = now.hour,
                minute = now.minute,
                dayOfWeek = now.dayOfWeek
            )

            if (!signal.supportedCity || city.isNullOrBlank()) return

            val normalizedCity = city.trim()

            val minutesToNextPeak = signal.minutesToNextPeak
            if (!signal.inPeakNow && minutesToNextPeak != null && minutesToNextPeak in 1..20) {
                val key = "${normalizedCity}_${now.dayOfWeek}_${signal.nextPeakLabel ?: ""}"
                if (lastUpcomingAlertKey != key) {
                    sendPeakAlertNotification(
                        id = ALERT_NOTIFICATION_ID_UPCOMING,
                        title = "Pico chegando em ${minutesToNextPeak} min",
                        message = "$normalizedCity: ${signal.nextPeakLabel ?: "horário de pico"}. Hora de ganhar dinheiro."
                    )
                    lastUpcomingAlertKey = key
                }
            }

            val minutesToPeakEnd = signal.minutesToPeakEnd
            if (signal.inPeakNow && minutesToPeakEnd != null && minutesToPeakEnd in 0..10) {
                val key = "${normalizedCity}_${now.dayOfWeek}_${signal.activePeakLabel ?: ""}"
                if (lastDecliningAlertKey != key) {
                    sendPeakAlertNotification(
                        id = ALERT_NOTIFICATION_ID_DECLINING,
                        title = "Pico diminuindo",
                        message = "$normalizedCity: faltam ${minutesToPeakEnd} min para o fim do pico. Considere pausa estratégica."
                    )
                    lastDecliningAlertKey = key
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendPeakAlertNotification(id: Int, title: String, message: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_analytics)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(id, notification)
    }

    /**
     * Atualiza a localização do motorista no Firebase a cada 15 min.
     * Usa Geocoder para resolver cidade/bairro a partir das coordenadas GPS.
     */
    private fun updateDriverLocationInFirebase() {
        val location = locationHelper.getCurrentLocation()
        if (location == null) {
            Log.w("FloatingAnalytics", "GPS indisponível para atualizar localização")
            return
        }

        try {
            val geocoder = Geocoder(this, Locale("pt", "BR"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                val neighborhood = addr.subLocality

                if (!city.isNullOrBlank()) {
                    lastResolvedCity = city
                    lastResolvedNeighborhood = neighborhood
                    firestoreManager.saveDriverLocation(
                        city = city,
                        neighborhood = neighborhood,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    Log.i("FloatingAnalytics", "Localização atualizada: $city / $neighborhood")
                }
            }
        } catch (e: Exception) {
            Log.w("FloatingAnalytics", "Geocoder falhou na atualização periódica", e)
        }
    }

    // ========================
    // Floating Button (Overlay)
    // ========================

    private fun showFloatingButton() {
        if (floatingButton != null) return

        val inflater = LayoutInflater.from(this)
        floatingButton = inflater.inflate(R.layout.layout_floating_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        // Tornar invisível para acessibilidade
        floatingButton?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        setupDraggableWithLongPress(floatingButton!!, params)

        try {
            windowManager.addView(floatingButton, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { }
        }
        floatingButton = null
    }

    /**
     * Configura o botão flutuante com arrastar + toque e toque longo.
     * Toque curto: mostra/oculta card de análise
     * Toque longo: mostra card de status com demanda e pausa
     */
    private fun setupDraggableWithLongPress(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            // Vibrar feedback
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showStatusCard()
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    longPressTriggered = false
                    handler.postDelayed(longPressRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isClick = false
                        handler.removeCallbacks(longPressRunnable)
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isClick && !longPressTriggered) {
                        if (isStatusCardVisible) {
                            hideStatusCard()
                        } else if (isCardVisible) {
                            hideAnalysisCard()
                        } else {
                            // Nenhum card visível: abrir a tela principal do app
                            val intent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ========================
    // Ride Analysis Card
    // ========================

    // Debounce para processar apenas a ÚLTIMA corrida recebida
    private var pendingRideRunnable: Runnable? = null

    /**
     * Chamado pelo AccessibilityService quando uma corrida é detectada.
     * Usa debounce: se múltiplas chamadas chegam em rajada, só a ÚLTIMA é processada.
     */
    fun onRideDetected(rideData: RideData) {
        // Cancelar processamento anterior pendente
        pendingRideRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            Log.i(
                "FloatingAnalytics",
                ">>> Processando corrida (após debounce): ${rideData.appSource.displayName}, R$ ${rideData.ridePrice}, source=${rideData.extractionSource}"
            )

            // Registrar corrida no rastreador de demanda
            DemandTracker.recordRideOffer(rideData)

            // Geocodificar localização atual para obter cidade/bairro
            val location = locationHelper.getCurrentLocation()
            var city: String? = null
            var neighborhood: String? = null
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale("pt", "BR"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                        neighborhood = addr.subLocality
                        if (!city.isNullOrBlank()) {
                            lastResolvedCity = city
                            lastResolvedNeighborhood = neighborhood
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FloatingAnalytics", "Geocoder falhou ao obter cidade/bairro", e)
                }
            }

            if (city.isNullOrBlank()) {
                city = lastResolvedCity ?: marketDataService.getLastMarketInfo()?.regionName
                neighborhood = neighborhood ?: lastResolvedNeighborhood
                if (!city.isNullOrBlank()) {
                    lastResolvedCity = city
                }
            }

            if (city.isNullOrBlank()) {
                Log.w("FloatingAnalytics", "Oferta detectada sem cidade resolvida — demanda regional pode não contabilizar")
            }

            // Salvar oferta no Firebase para base de cálculo de demanda
            firestoreManager.saveRideOffer(rideData, city, neighborhood)

            // Pulsar o botão flutuante para indicar nova corrida
            pulseFloatingButton()

            // Analisar a corrida
            // Prioridade: usar pickup extraído da tela > fallback GPS
            val gpsPickupDistance = locationHelper.estimatePickupDistance()
            val pickupDistance = rideData.pickupDistanceKm ?: gpsPickupDistance
            val isLimitedData = rideData.rawText.startsWith("LIMITED_DATA_NO_PRICE")
            val analysis = if (isLimitedData) {
                RideAnalysis(
                    rideData = rideData,
                    pricePerKm = 0.0,
                    effectivePricePerKm = 0.0,
                    referencePricePerKm = RideAnalyzer.getCurrentReferences()["minPricePerKm"] ?: 1.5,
                    estimatedEarningsPerHour = 0.0,
                    pickupDistanceKm = pickupDistance,
                    score = 50,
                    recommendation = Recommendation.NEUTRAL,
                    reasons = listOf("Dados limitados: Uber/99 não expôs preço/distância acessíveis nesta oferta")
                )
            } else {
                RideAnalyzer.analyze(rideData, pickupDistance)
            }

            Log.i("FloatingAnalytics", ">>> Análise: Rec=${analysis.recommendation}")

            showAnalysisCard(analysis)

            // Atualizar notificação com stats atualizados
            updateNotificationWithStats()

            // Verificar se deve recomendar pausa
            checkPauseRecommendation()
        }
        pendingRideRunnable = runnable
        handler.postDelayed(runnable, RIDE_DEBOUNCE_MS)
    }

    /**
     * Chamado pelo AccessibilityService quando uma corrida é aceita pelo motorista.
     * Atualiza o status card com as contagens de aceitas.
     */
    fun onRideAccepted(appSource: AppSource) {
        handler.post {
            Log.i("FloatingAnalytics", ">>> Corrida ACEITA: ${appSource.displayName}")
            // Atualizar o status card para refletir a aceitação
            populateStatusCard()
            // Atualizar notificação
            updateNotificationWithStats()
        }
    }

    /**
     * Simula uma corrida para testar se o card de análise aparece corretamente.
     * Chamado pelo botão "Testar" na tela inicial.
     */
    fun simulateRide(source: AppSource = AppSource.UBER) {
        val fakeRide = RideData(
            appSource = source,
            ridePrice = 18.50,
            distanceKm = 7.2,
            estimatedTimeMin = 15,
            pickupDistanceKm = 2.3,
            pickupTimeMin = 5,
            pickupAddress = "Av. Paulista, 1000",
            dropoffAddress = "Rua Augusta, 500",
            rawText = "Simulação de teste"
        )
        onRideDetected(fakeRide)
    }

    /**
     * Verifica a recomendação de pausa e atualiza card se urgente.
     */
    private fun checkPauseRecommendation() {
        val stats = DemandTracker.getStats()
        val location = locationHelper.getCurrentLocation()
        val marketInfo = marketDataService.getLastMarketInfo()
        val pauseRec = PauseAdvisor.analyze(stats, location, marketInfo)

        // Se pausa é CRÍTICA, pulsar botão em vermelho e mostrar status card
        if (pauseRec.urgency == PauseAdvisor.PauseUrgency.CRITICAL) {
            pulseFloatingButtonWarning()
            if (!isStatusCardVisible) {
                showStatusCard()
            }
        }
    }

    private fun pulseFloatingButton() {
        floatingButton?.let { btn ->
            btn.animate()
                .scaleX(1.3f).scaleY(1.3f)
                .setDuration(200)
                .withEndAction {
                    btn.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .start()
                }
                .start()
        }
    }

    private fun pulseFloatingButtonWarning() {
        floatingButton?.let { btn ->
            btn.animate()
                .scaleX(1.4f).scaleY(1.4f)
                .alpha(0.5f)
                .setDuration(300)
                .withEndAction {
                    btn.animate()
                        .scaleX(1f).scaleY(1f)
                        .alpha(1f)
                        .setDuration(300)
                        .withEndAction {
                            // Repetir 2 vezes
                            btn.animate()
                                .scaleX(1.4f).scaleY(1.4f)
                                .alpha(0.5f)
                                .setDuration(300)
                                .withEndAction {
                                    btn.animate()
                                        .scaleX(1f).scaleY(1f)
                                        .alpha(1f)
                                        .setDuration(300)
                                        .start()
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    // ========================
    // Status / Demand / Pause Card
    // ========================

    /**
     * Mostra o card de status com demanda, sessão e recomendação de pausa.
     * Ativado por long-press no botão flutuante.
     */
    private fun showStatusCard() {
        hideStatusCard()
        hideAnalysisCard()

        val inflater = LayoutInflater.from(this)
        statusCard = inflater.inflate(R.layout.layout_status_card, null)

        // Tornar invisível para acessibilidade (evitar auto-detecção)
        statusCard?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        populateStatusCard()

        // Overlay transparente fullscreen para fechar ao tocar fora
        statusCardOverlay = View(this).apply {
            setBackgroundColor(0x01000000) // quase transparente mas captura toques
            setOnClickListener { hideStatusCard() }
        }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 135
        }

        try {
            windowManager.addView(statusCardOverlay, overlayParams)
            windowManager.addView(statusCard, params)
            isStatusCardVisible = true

            // Animação de entrada
            statusCard?.alpha = 0f
            statusCard?.animate()
                ?.alpha(1f)
                ?.setDuration(300)
                ?.start()

            // Auto-ocultar após 30 segundos (fallback)
            handler.removeCallbacks(hideStatusCardRunnable)
            handler.postDelayed(hideStatusCardRunnable, 30_000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun populateStatusCard() {
        val card = statusCard ?: return
        val stats = DemandTracker.getStats()
        val location = locationHelper.getCurrentLocation()
        val marketInfo = marketDataService.getLastMarketInfo()
        val pauseRec = PauseAdvisor.analyze(stats, location, marketInfo)
        val now = currentTimeInfo()
        val hour = now.hour

        bindStatusCardCloseAction(card)

        // Tempo de sessão
        val hours = stats.sessionDurationMin / 60
        val mins = stats.sessionDurationMin % 60
        card.findViewById<TextView>(R.id.tvSessionTime).text =
            if (hours > 0) "${hours}h ${mins}min" else "${mins}min"

        val isGoogleLoggedIn = firestoreManager.isGoogleUser
        if (!isGoogleLoggedIn) {
            applyLoggedOutStatusCardState(card)
            return
        }

        // Demanda: baseada na comparação hora-a-hora
        firestoreManager.loadTodayRideOfferStats { firebaseStats ->
            val demandStatus = computeFirebaseDemandStatus(firebaseStats)
            val offersLast1h = firebaseStats.offersLast1h

            card.findViewById<TextView>(R.id.tvDemandLevel).apply {
                text = demandStatus.cardLabel
                setTextColor(demandStatus.color)
            }

            val layoutAlert = card.findViewById<LinearLayout>(R.id.layoutNoRidesAlert)
            val dividerAlert = card.findViewById<View>(R.id.dividerAfterAlert)
            val noRecentOffers = firebaseStats.lastOfferTimestamp <= 0L ||
                (System.currentTimeMillis() - firebaseStats.lastOfferTimestamp) > NO_OFFERS_ALERT_WINDOW_MS
            if (noRecentOffers) {
                layoutAlert.visibility = View.VISIBLE
                dividerAlert.visibility = View.VISIBLE
            } else {
                layoutAlert.visibility = View.GONE
                dividerAlert.visibility = View.GONE
            }

            card.findViewById<TextView>(R.id.tvRidesUber).text = "${firebaseStats.offersUber}"
            card.findViewById<TextView>(R.id.tvRides99).text = "${firebaseStats.offers99}"

            card.findViewById<TextView>(R.id.tvAvgPriceUber).text =
                if (firebaseStats.avgPriceUber > 0) String.format("R$ %.2f", firebaseStats.avgPriceUber) else "—"
            card.findViewById<TextView>(R.id.tvAvgPrice99).text =
                if (firebaseStats.avgPrice99 > 0) String.format("R$ %.2f", firebaseStats.avgPrice99) else "—"

            card.findViewById<TextView>(R.id.tvAvgTimeUber).text =
                if (firebaseStats.avgEstimatedTimeMinUber > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMinUber) else "—"
            card.findViewById<TextView>(R.id.tvAvgTime99).text =
                if (firebaseStats.avgEstimatedTimeMin99 > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMin99) else "—"

            val total = firebaseStats.totalOffersToday.coerceAtLeast(1)
            val uberPerHour = (offersLast1h * (firebaseStats.offersUber.toDouble() / total.toDouble())).roundToInt()
            val ninetyNinePerHour = (offersLast1h * (firebaseStats.offers99.toDouble() / total.toDouble())).roundToInt()
            card.findViewById<TextView>(R.id.tvSessionHourly).text = "${uberPerHour.coerceAtLeast(0)}"
            card.findViewById<TextView>(R.id.tvAvgPrice).text = "${ninetyNinePerHour.coerceAtLeast(0)}"
        }

        // Footer: recomendação + qualidade das corridas aceitas
        val pauseBg = card.findViewById<FrameLayout>(R.id.pauseBackground)
        val tvPauseAdvice = card.findViewById<TextView>(R.id.tvPauseAdvice)
        val tvPauseReason = card.findViewById<TextView>(R.id.tvPauseReason)
        val tvLocation = card.findViewById<TextView>(R.id.tvLocation)
        val tvPeakNext = card.findViewById<TextView>(R.id.tvPeakNext)
        val tvPeakTips = card.findViewById<TextView>(R.id.tvPeakTips)

        // Verificar horários de baixa demanda (9h-11:30 e 14:30-16:30)
        val timeInMinutes = hour * 60 + now.minute
        val isLowDemandHour = (timeInMinutes in 540..690) || (timeInMinutes in 870..990)

        val detectedCity = lastResolvedCity ?: marketInfo?.regionName
        val cityGuidance = PauseAdvisor.getCityPeakGuidance(
            cityName = detectedCity,
            hour = hour,
            minute = now.minute,
            dayOfWeek = now.dayOfWeek
        )

        // Texto principal do rodapé
        val mainReason = pauseRec.reasons.firstOrNull()?.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Sc}\\s]"), "")?.trim() ?: ""
        if (cityGuidance.shouldPauseNow && !cityGuidance.inPeakNow) {
            tvPauseAdvice.text = "PAUSA ESTRATÉGICA"
            tvPauseReason.text = "Retorne no próximo pico"
        } else if (isLowDemandHour) {
            tvPauseAdvice.text = "GUARDE O CARRO"
            tvPauseReason.text = "Baixa demanda agora"
        } else if (stats.acceptedBelowAverage) {
            tvPauseAdvice.text = "ACEITE CORRIDAS MELHORES"
            tvPauseReason.text = "Selecione melhor por km"
        } else if (pauseRec.shouldPause) {
            tvPauseAdvice.text = "PAUSAR AGORA"
            tvPauseReason.text = mainReason
        } else {
            tvPauseAdvice.text = mainReason.ifEmpty { "Janela favorável" }
            tvPauseReason.text = ""
        }

        tvPauseReason.visibility = if (tvPauseReason.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // Localização do motorista
        val regionName = marketInfo?.regionName ?: "Desconhecida"
        tvLocation.text = regionName

        val compactPeak = cityGuidance.nextPeakText
            .replace("Próximo pico:", "", ignoreCase = true)
            .trim()
        tvPeakNext.text = "Pico: ${compactPeak.ifBlank { "—" }}"

        val compactTip = cityGuidance.tipText
            .substringBefore('.')
            .trim()
            .take(64)
        tvPeakTips.text = compactTip
        tvPeakTips.visibility = if (compactTip.isBlank()) View.GONE else View.VISIBLE

        // Cor do fundo baseado na demanda hora-a-hora
        when {
            isLowDemandHour ->
                pauseBg.setBackgroundResource(R.drawable.bg_card_bad)
            stats.acceptedBelowAverage ->
                pauseBg.setBackgroundResource(R.drawable.bg_card_neutral)
            stats.ridesLastHour < stats.ridesPreviousHour -> // Demanda caindo
                pauseBg.setBackgroundResource(R.drawable.bg_card_bad)
            stats.ridesLastHour > stats.ridesPreviousHour || stats.trend == DemandTracker.DemandTrend.RISING -> // Subindo
                pauseBg.setBackgroundResource(R.drawable.bg_card_good)
            else -> // Estável
                pauseBg.setBackgroundResource(R.drawable.bg_card_neutral)
        }

    }

    private fun bindStatusCardCloseAction(card: View) {
        card.findViewById<View>(R.id.btnCloseStatus).setOnClickListener {
            hideStatusCard()
        }
    }

    private fun applyLoggedOutStatusCardState(card: View) {
        card.findViewById<TextView>(R.id.tvDemandLevel).apply {
            text = "Login necessário"
            setTextColor(0xFFFFC107.toInt())
        }

        card.findViewById<LinearLayout>(R.id.layoutNoRidesAlert).visibility = View.GONE
        card.findViewById<View>(R.id.dividerAfterAlert).visibility = View.GONE

        card.findViewById<TextView>(R.id.tvRidesUber).text = "—"
        card.findViewById<TextView>(R.id.tvRides99).text = "—"
        card.findViewById<TextView>(R.id.tvAvgPriceUber).text = "—"
        card.findViewById<TextView>(R.id.tvAvgPrice99).text = "—"
        card.findViewById<TextView>(R.id.tvAvgTimeUber).text = "—"
        card.findViewById<TextView>(R.id.tvAvgTime99).text = "—"
        card.findViewById<TextView>(R.id.tvSessionHourly).text = "—"
        card.findViewById<TextView>(R.id.tvAvgPrice).text = "—"

        val pauseBg = card.findViewById<FrameLayout>(R.id.pauseBackground)
        val tvPauseAdvice = card.findViewById<TextView>(R.id.tvPauseAdvice)
        val tvPauseReason = card.findViewById<TextView>(R.id.tvPauseReason)
        val tvLocation = card.findViewById<TextView>(R.id.tvLocation)
        val tvPeakNext = card.findViewById<TextView>(R.id.tvPeakNext)
        val tvPeakTips = card.findViewById<TextView>(R.id.tvPeakTips)

        tvPauseAdvice.text = "ENTRE COM GOOGLE"
        tvPauseReason.text = "Faça login para liberar a análise"
        tvLocation.text = "—"
        tvPeakNext.text = "Pico: login"
        tvPeakTips.text = ""
        tvPeakTips.visibility = View.GONE
        pauseBg.setBackgroundResource(R.drawable.bg_card_neutral)
    }

    private fun hideStatusCard() {
        handler.removeCallbacks(hideStatusCardRunnable)
        // Remover overlay
        statusCardOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        statusCardOverlay = null
        statusCard?.let { card ->
            card.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    try {
                        windowManager.removeView(card)
                    } catch (_: Exception) { }
                }
                .start()
        }
        statusCard = null
        isStatusCardVisible = false
    }

    private fun showAnalysisCard(analysis: RideAnalysis) {
        val attached = analysisCard?.isAttachedToWindow == true
        Log.i(
            "FloatingAnalytics",
            ">>> showAnalysisCard chamado! isCardVisible=$isCardVisible, analysisCard=${if (analysisCard != null) "NÃO NULO" else "NULO"}, attached=$attached"
        )

        // Se o card já está visível, apenas atualizar os dados sem recriar
        if (isCardVisible && analysisCard != null && attached) {
            Log.i("FloatingAnalytics", ">>> Atualizando card existente (in-place)")
            populateCard(analysis)

            // Garantir visibilidade caso tenha ficado invisível por animação/estado residual
            analysisCard?.visibility = View.VISIBLE
            analysisCard?.translationY = 0f

            // Piscar brevemente para indicar atualização
            analysisCard?.animate()
                ?.alpha(0.5f)
                ?.setDuration(100)
                ?.withEndAction {
                    analysisCard?.animate()
                        ?.alpha(1f)
                        ?.setDuration(150)
                        ?.start()
                }
                ?.start()
            // Resetar timer de auto-ocultar
            handler.removeCallbacks(hideCardRunnable)
            handler.postDelayed(hideCardRunnable, 10000)
            return
        }

        if (analysisCard != null && !attached) {
            Log.w("FloatingAnalytics", ">>> Card existente estava desacoplado da janela, recriando")
            analysisCard = null
            isCardVisible = false
        }

        // Primeira exibição: criar card
        hideAnalysisCard()

        val inflater = LayoutInflater.from(this)
        analysisCard = inflater.inflate(R.layout.layout_ride_analysis_card, null)

        // IMPORTANTE: Tornar o card invisível para acessibilidade
        // Sem isso, o AccessibilityService lê o texto do nosso próprio card
        // e o interpreta como uma nova corrida, criando um loop infinito
        analysisCard?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        populateCard(analysis)

        // Posicionar no TOPO para não bloquear o botão de aceitar (que fica embaixo)
        // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL = toques passam para o app por trás
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 135
        }

        try {
            windowManager.addView(analysisCard, params)
            isCardVisible = true

            Log.i("FloatingAnalytics", ">>> Card adicionado ao WindowManager com sucesso! CARD VISÍVEL!")

            // Animação slide-down de entrada (vem do topo)
            analysisCard?.translationY = -300f
            analysisCard?.alpha = 0.3f
            analysisCard?.animate()
                ?.translationY(0f)
                ?.alpha(1f)
                ?.setDuration(350)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()

            // Auto-ocultar após 10 segundos
            handler.removeCallbacks(hideCardRunnable)
            handler.postDelayed(hideCardRunnable, 10000)
        } catch (e: Exception) {
            Log.e("FloatingAnalytics", "!!! ERRO ao adicionar card ao WindowManager!", e)
            e.printStackTrace()
        }
    }

    private fun populateCard(analysis: RideAnalysis) {
        val card = analysisCard ?: return

        val pickupDistance = analysis.pickupDistanceKm
        val totalDistanceKm = (analysis.rideData.distanceKm + pickupDistance).coerceAtLeast(0.1)
        val pickupTimeMin = (pickupDistance / 30.0) * 60.0
        val totalTimeMin = (analysis.rideData.estimatedTimeMin + pickupTimeMin).coerceAtLeast(1.0)
        val valuePerKm = analysis.rideData.ridePrice / totalDistanceKm
        val valuePerMin = analysis.rideData.ridePrice / totalTimeMin

        Log.i("FloatingAnalytics", ">>> CARD: preço=R$ ${analysis.rideData.ridePrice}, " +
            "corrida=${analysis.rideData.distanceKm}km/${analysis.rideData.estimatedTimeMin}min, " +
            "pickup=${pickupDistance}km, total=${String.format("%.1f", totalDistanceKm)}km, " +
            "R$/km=${String.format("%.2f", valuePerKm)}, source=${analysis.rideData.extractionSource}")

        // Fonte do app - ícone + texto
        card.findViewById<TextView>(R.id.tvAppSource).text = analysis.rideData.appSource.displayName

        // Ícone do app (Uber ou 99)
        val ivAppIcon = card.findViewById<ImageView>(R.id.ivAppIcon)
        val tvAppIconLabel = card.findViewById<TextView>(R.id.tvAppIconLabel)
        when (analysis.rideData.appSource) {
            AppSource.UBER -> {
                ivAppIcon.setImageResource(R.drawable.ic_uber_logo)
                tvAppIconLabel.text = "U"
                tvAppIconLabel.setTextColor(0xFFFFFFFF.toInt())
            }
            AppSource.NINETY_NINE -> {
                ivAppIcon.setImageResource(R.drawable.ic_99_logo)
                tvAppIconLabel.text = "99"
                tvAppIconLabel.setTextColor(0xFF000000.toInt())
                tvAppIconLabel.textSize = 10f
            }
            AppSource.UNKNOWN -> {
                ivAppIcon.setImageResource(R.drawable.ic_analytics)
                tvAppIconLabel.text = "?"
                tvAppIconLabel.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        // Valor por KM (considerando buscar + destino)
        card.findViewById<TextView>(R.id.tvPricePerKm).text =
            String.format("R$ %.2f", valuePerKm)

        // Valor por minuto (tempo total estimado)
        card.findViewById<TextView>(R.id.tvValuePerMin).text =
            String.format("R$ %.2f", valuePerMin)

        // Km Total (buscar + destino)
        card.findViewById<TextView>(R.id.tvTotalDistance).text =
            String.format("%.1f km", totalDistanceKm)

        // Recomendação com cor
        val tvRecommendation = card.findViewById<TextView>(R.id.tvRecommendation)
        when (analysis.recommendation) {
            Recommendation.WORTH_IT -> {
                tvRecommendation.text = "COMPENSA"
                tvRecommendation.setTextColor(0xFF4CAF50.toInt())
            }
            Recommendation.NOT_WORTH_IT -> {
                tvRecommendation.text = "EVITAR"
                tvRecommendation.setTextColor(0xFFF44336.toInt())
            }
            Recommendation.NEUTRAL -> {
                tvRecommendation.text = "NEUTRO"
                tvRecommendation.setTextColor(0xFFFF9800.toInt())
            }
        }

        // Endereços de embarque e destino
        val tvPickup = card.findViewById<TextView>(R.id.tvPickupAddress)
        val tvDropoff = card.findViewById<TextView>(R.id.tvDropoffAddress)
        tvPickup.text = analysis.rideData.pickupAddress.ifBlank { "Endereço não disponível" }
        tvDropoff.text = analysis.rideData.dropoffAddress.ifBlank { "Destino não disponível" }

        // Botão de fechar
        card.findViewById<View>(R.id.btnClose).setOnClickListener {
            hideAnalysisCard()
        }
    }

    private fun hideAnalysisCard() {
        handler.removeCallbacks(hideCardRunnable)
        analysisCard?.let { card ->
            // Animação slide-up de saída (sobe para o topo)
            card.animate()
                .translationY(-300f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    try {
                        windowManager.removeView(card)
                    } catch (_: Exception) { }
                }
                .start()
        }
        analysisCard = null
        isCardVisible = false
    }
}
