package com.example.motoristainteligente

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.Normalizer
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
        const val ALERT_CHANNEL_ID = "motorista_inteligente_alerts_v2"
        const val NO_OFFERS_ALERT_CHANNEL_ID = "motorista_inteligente_no_offers_alerts_v2"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID_UPCOMING = 2001
        const val ALERT_NOTIFICATION_ID_DECLINING = 2002
        const val ALERT_NOTIFICATION_ID_NO_OFFERS = 2003
        const val ALERT_NOTIFICATION_ID_STRATEGIC_PAUSE = 2004
        const val ACTION_STOP = "com.example.motoristainteligente.STOP_SERVICE"
        private const val RIDE_DEBOUNCE_MS = 300L // 300ms para agrupar eventos rápidos
        private const val FIREBASE_OFFER_SAVE_DELAY_MS = 5_000L
        private const val NO_OFFERS_ALERT_WINDOW_MS = 15 * 60 * 1000L

        // Tempo sem ofertas para sugerir que o motorista se mova 1km
        private const val NO_OFFERS_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutos
        // Cooldown entre alertas de "sem ofertas" (não repetir em menos de 15 min)
        private const val NO_OFFERS_ALERT_COOLDOWN_MS = 15 * 60 * 1000L
        private const val INTERNET_DEMAND_ALERT_COOLDOWN_MS = 15 * 60 * 1000L
        private const val LONG_ONLINE_ALERT_INITIAL_MS = 2 * 60 * 60 * 1000L
        private const val LONG_ONLINE_ALERT_COOLDOWN_MS = 60 * 60 * 1000L
        private const val FLOATING_DRAG_SAFE_MARGIN_DP = 18
    }

    private lateinit var windowManager: WindowManager
    private lateinit var analysisStatePrefs: SharedPreferences
    private lateinit var locationHelper: LocationHelper
    private lateinit var marketDataService: MarketDataService
    private lateinit var driverPreferences: DriverPreferences
    private lateinit var firestoreManager: FirestoreManager

    private var floatingButton: View? = null
    private var analysisCard: View? = null
    private var statusCard: View? = null
    private var statusCardOverlay: View? = null
    private var dragCloseTarget: View? = null
    private var isDragCloseTargetHighlighted = false
    private var isFloatingButtonMagnetized = false
    private var isCardVisible = false
    private var isStatusCardVisible = false
    private var onlineSessionStartMs: Long = 0L
    private var lastResolvedCity: String? = null
    private var lastResolvedNeighborhood: String? = null
    private var lastInternetDemandAlertAt = 0L
    private var lastLongOnlineAlertAt = 0L
    private var internetDemandCheckInFlight = false

    // Rastreamento de "sem ofertas por 10 min"
    private var lastOfferReceivedAt = 0L   // timestamp da última oferta detectada
    private var lastNoOffersAlertAt = 0L  // timestamp do último alerta enviado
    private var analysisCardOffsetY = 135

    private val analysisStateChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFloatingButtonBorderState()
            updateAnalysisCardBorderState()
            if (AnalysisServiceState.isEnabled(this)) {
                if (::firestoreManager.isInitialized) {
                    updateDriverLocationInFirebase()
                }
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private val hideCardRunnable = Runnable { hideAnalysisCard() }
    private val hideStatusCardRunnable = Runnable { hideStatusCard() }

    // Atualização periódica do status de demanda na notificação
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            updateNotificationWithStats()
            maybeNotifyInternetDemandNearby()
            maybeNotifyNoOffers()
            maybeNotifyLongOnline()
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
        analysisStatePrefs = getSharedPreferences("analysis_service_state", MODE_PRIVATE)
        analysisStatePrefs.registerOnSharedPreferenceChangeListener(analysisStateChangeListener)
        locationHelper = LocationHelper(this)
        locationHelper.startLocationUpdates()

        // Carregar preferências do motorista e aplicar no analisador
        driverPreferences = DriverPreferences(this)

        // Inicializar Firebase
        firestoreManager = FirestoreManager(this)
        firestoreManager.signInAnonymously {
            driverPreferences.firestoreManager = firestoreManager
            if (firestoreManager.isGoogleUser) {
                firestoreManager.loadPreferences(driverPreferences) {
                    driverPreferences.applyToAnalyzer()
                }
            } else {
                driverPreferences.applyToAnalyzer()
            }
        }

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
            // Reinicia timers para evitar alerta imediato ao iniciar sessão
            lastOfferReceivedAt = 0L
            lastNoOffersAlertAt = 0L
        }

        createMainNotificationChannel(this, CHANNEL_ID)
        createPeakAlertsNotificationChannel(this, ALERT_CHANNEL_ID)
        createNoOffersAlertNotificationChannel(this, NO_OFFERS_ALERT_CHANNEL_ID)
        startForeground(
            NOTIFICATION_ID,
            createForegroundNotification(this, CHANNEL_ID, ACTION_STOP, "Iniciando análise..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        showFloatingButton()

        // Iniciar atualização periódica da notificação com stats
        handler.post(notificationUpdateRunnable)

        // Iniciar atualização periódica de localização (a cada 15 min)
        handler.post(locationUpdateRunnable)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (AnalysisServiceState.isEnabled(this)) {
            try {
                val restartIntent = Intent(applicationContext, FloatingAnalyticsService::class.java)
                startForegroundService(restartIntent)
                Log.w("FloatingAnalytics", "Serviço removido da task; auto-recuperação acionada")
            } catch (e: Exception) {
                Log.w("FloatingAnalytics", "Falha ao tentar auto-recuperar serviço", e)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        analysisStatePrefs.unregisterOnSharedPreferenceChangeListener(analysisStateChangeListener)
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
        hideDragCloseTarget()
        hideAnalysisCard()
        hideStatusCard()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ========================
    // Notification
    // ========================

    /**
     * Atualiza a notificação com stats atualizados de demanda.
     */
    private fun updateNotificationWithStats() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val peakSummary = buildLiveDemandSummaryText()
            manager.notify(
                NOTIFICATION_ID,
                createForegroundNotification(this, CHANNEL_ID, ACTION_STOP, peakSummary)
            )
        } catch (_: Exception) { }
    }

    private fun buildLiveDemandSummaryText(): String {
        val marketInfo = marketDataService.getLastMarketInfo()
        if (marketInfo == null) return "Monitorando demanda em tempo real..."

        val label = when {
            marketInfo.demandIndex >= 0.75 -> "Alta"
            marketInfo.demandIndex >= 0.5 -> "Moderada"
            else -> "Baixa"
        }

        val region = marketInfo.regionName.takeIf { it.isNotBlank() } ?: "região atual"
        return "Demanda $label • $region"
    }

    private fun normalizeRegionName(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun maybeNotifyInternetDemandNearby() {
        try {
            if (onlineSessionStartMs == 0L) return
            if (!AnalysisServiceState.isEnabled(this)) return
            if (AnalysisServiceState.isPaused(this)) return
            if (internetDemandCheckInFlight) return

            val now = System.currentTimeMillis()
            if (lastInternetDemandAlertAt > 0L && now - lastInternetDemandAlertAt < INTERNET_DEMAND_ALERT_COOLDOWN_MS) return

            val location = locationHelper.getCurrentLocation()
            val resolved = if (location != null) {
                try {
                    resolveRegionFromCoordinates(this, location.latitude, location.longitude)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val cityName = (resolved?.city ?: lastResolvedCity)?.trim().orEmpty()
            if (cityName.isBlank()) return

            val neighborhoodName = (resolved?.neighborhood ?: lastResolvedNeighborhood)?.trim().orEmpty()

            internetDemandCheckInFlight = true
            firestoreManager.loadCityDemandMini { cityList ->
                try {
                    val city = cityList.firstOrNull {
                        normalizeRegionName(it.city) == normalizeRegionName(cityName)
                    } ?: return@loadCityDemandMini

                    val selectedNeighborhood = city.neighborhoods.firstOrNull {
                        normalizeRegionName(it.neighborhood) == normalizeRegionName(neighborhoodName)
                    }

                    val offersLast15m = selectedNeighborhood?.offersLast15m ?: city.offersLast15m
                    val trend = selectedNeighborhood?.demandPeakTrend ?: city.demandPeakTrend
                    val locationLabel = selectedNeighborhood?.neighborhood?.takeIf { it.isNotBlank() }
                        ?.let { "$it, ${city.city}" }
                        ?: city.city

                    val isHighDemand = offersLast15m >= 8 || (offersLast15m >= 5 && trend == FirestoreManager.DemandPeakTrend.RISING)
                    if (!isHighDemand) return@loadCityDemandMini

                    val trendLabel = when (trend) {
                        FirestoreManager.DemandPeakTrend.RISING -> "em alta"
                        FirestoreManager.DemandPeakTrend.FALLING -> "reduzindo"
                        FirestoreManager.DemandPeakTrend.STABLE -> "estável"
                    }

                    lastInternetDemandAlertAt = System.currentTimeMillis()
                    sendPeakAlertNotification(
                        service = this,
                        channelId = ALERT_CHANNEL_ID,
                        id = ALERT_NOTIFICATION_ID_UPCOMING,
                        title = "Demanda alta perto de você",
                        message = "$locationLabel: $offersLast15m ofertas recentes (tendência $trendLabel)."
                    )
                } finally {
                    internetDemandCheckInFlight = false
                }
            }
        } catch (_: Exception) {
            internetDemandCheckInFlight = false
        }
    }

    private fun maybeNotifyLongOnline() {
        try {
            if (onlineSessionStartMs == 0L) return
            if (!AnalysisServiceState.isEnabled(this)) return
            if (AnalysisServiceState.isPaused(this)) return

            val now = System.currentTimeMillis()
            val elapsed = now - onlineSessionStartMs
            if (elapsed < LONG_ONLINE_ALERT_INITIAL_MS) return
            if (lastLongOnlineAlertAt > 0L && now - lastLongOnlineAlertAt < LONG_ONLINE_ALERT_COOLDOWN_MS) return

            val hours = elapsed / (60 * 60 * 1000L)
            val minutes = (elapsed % (60 * 60 * 1000L)) / (60 * 1000L)

            lastLongOnlineAlertAt = now
            sendPeakAlertNotification(
                service = this,
                channelId = ALERT_CHANNEL_ID,
                id = ALERT_NOTIFICATION_ID_STRATEGIC_PAUSE,
                title = "Muito tempo online",
                message = "Você está online há ${hours}h ${minutes}min. Faça uma pausa curta para manter o rendimento."
            )
        } catch (_: Exception) {
        }
    }

    private fun maybeNotifyNoOffers() {
        try {
            // Só executa se a sessão online estiver ativa
            if (onlineSessionStartMs == 0L) return
            if (!AnalysisServiceState.isEnabled(this)) return
            if (AnalysisServiceState.isPaused(this)) return

            val now = System.currentTimeMillis()

            // Durante corrida em andamento, não faz sentido alertar ausência de ofertas.
            if (RideInfoOcrService.isTripInProgress()) {
                lastOfferReceivedAt = now
                return
            }

            // Na primeira execução após ir online, inicializa o timer (evita alerta imediato)
            if (lastOfferReceivedAt == 0L) {
                lastOfferReceivedAt = now
                return
            }

            val idleMs = now - lastOfferReceivedAt
            if (idleMs < NO_OFFERS_IDLE_TIMEOUT_MS) return  // Ainda dentro dos 10 min

            // Cooldown: não repetir dentro de 15 min
            if (lastNoOffersAlertAt > 0L && now - lastNoOffersAlertAt < NO_OFFERS_ALERT_COOLDOWN_MS) return

            lastNoOffersAlertAt = now
            sendHighPriorityAlertNotification(
                service = this,
                channelId = NO_OFFERS_ALERT_CHANNEL_ID,
                id = ALERT_NOTIFICATION_ID_NO_OFFERS,
                title = "Sem demanda há 10 min",
                message = "Você está online sem ofertas. Faça uma pausa estratégica de 10-15 min ou se desloque ~1 km para melhorar a chance de corridas."
            )
        } catch (_: Exception) {
        }
    }

    private fun clearDemandAlertNotifications() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(ALERT_NOTIFICATION_ID_UPCOMING)
            manager.cancel(ALERT_NOTIFICATION_ID_DECLINING)
            manager.cancel(ALERT_NOTIFICATION_ID_NO_OFFERS)
            manager.cancel(ALERT_NOTIFICATION_ID_STRATEGIC_PAUSE)
        } catch (_: Exception) {
        }
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
            val resolved = resolveRegionFromCoordinates(this, location.latitude, location.longitude)
            val city = resolved?.city
            val neighborhood = resolved?.neighborhood

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
        updateFloatingButtonBorderState()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(FLOATING_DRAG_SAFE_MARGIN_DP)
            y = 300
        }

        // Tornar invisível para acessibilidade
        floatingButton?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        setupDraggable(floatingButton!!, params)

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
        hideDragCloseTarget()
    }

    /**
     * Configura o botão flutuante com arrastar + toque.
     * Toque curto: mostra/oculta card de análise
     */
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isClick = false
                        isDragging = true
                        showDragCloseTarget()
                    }
                    val safeMargin = dpToPx(FLOATING_DRAG_SAFE_MARGIN_DP)
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val viewWidth = view.width.takeIf { it > 0 } ?: dpToPx(76)
                    val viewHeight = view.height.takeIf { it > 0 } ?: dpToPx(76)

                    val minX = safeMargin
                    val maxX = (screenWidth - viewWidth - safeMargin).coerceAtLeast(minX)
                    val minY = safeMargin
                    val maxY = (screenHeight - viewHeight - safeMargin).coerceAtLeast(minY)

                    params.x = (initialX + dx.toInt()).coerceIn(minX, maxX)
                    params.y = (initialY + dy.toInt()).coerceIn(minY, maxY)

                    if (isDragging) {
                        val isOverCloseTarget = isPointOverDragCloseTarget(event.rawX, event.rawY)
                        updateDragCloseTargetHighlight(isOverCloseTarget)
                        updateFloatingButtonMagnetState(view, isOverCloseTarget)
                    }

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val droppedOnClose = isDragging && isPointOverDragCloseTarget(event.rawX, event.rawY)
                    updateFloatingButtonMagnetState(view, false)
                    hideDragCloseTarget()

                    if (droppedOnClose) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        stopAnalysisFromDragCloseTarget(view)
                        return@setOnTouchListener true
                    }

                    if (isClick) {
                        if (isCardVisible) {
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

    private fun updateFloatingButtonMagnetState(buttonView: View, isOverCloseTarget: Boolean) {
        if (isFloatingButtonMagnetized == isOverCloseTarget) return
        isFloatingButtonMagnetized = isOverCloseTarget

        if (isOverCloseTarget) {
            buttonView.animate()
                .scaleX(0.86f)
                .scaleY(0.86f)
                .alpha(0.82f)
                .setDuration(120)
                .start()
        } else {
            buttonView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(120)
                .start()
        }
    }

    private fun showDragCloseTarget() {
        if (dragCloseTarget != null) return

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(dpToPx(88), dpToPx(88))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22F44336)
                setStroke(dpToPx(2), 0xAAF44336.toInt())
            }
            alpha = 0.85f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val closeText = TextView(this).apply {
            text = "✕"
            textSize = 30f
            setTextColor(0xFFF44336.toInt())
            gravity = Gravity.CENTER
        }

        container.addView(
            closeText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            dpToPx(88),
            dpToPx(88),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(36)
        }

        try {
            windowManager.addView(container, params)
            dragCloseTarget = container
            isDragCloseTargetHighlighted = false
        } catch (_: Exception) {
        }
    }

    private fun updateDragCloseTargetHighlight(isOver: Boolean) {
        if (isDragCloseTargetHighlighted == isOver) return
        isDragCloseTargetHighlighted = isOver

        val target = dragCloseTarget as? FrameLayout ?: return
        val bg = target.background as? GradientDrawable ?: return

        if (isOver) {
            bg.setColor(0x44F44336)
            bg.setStroke(dpToPx(3), 0xFFF44336.toInt())
        } else {
            bg.setColor(0x22F44336)
            bg.setStroke(dpToPx(2), 0xAAF44336.toInt())
        }
    }

    private fun isPointOverDragCloseTarget(rawX: Float, rawY: Float): Boolean {
        val target = dragCloseTarget ?: return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + target.width
        val bottom = top + target.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun hideDragCloseTarget() {
        dragCloseTarget?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        dragCloseTarget = null
        isDragCloseTargetHighlighted = false
        isFloatingButtonMagnetized = false
    }

    private fun stopAnalysisFromDragCloseTarget(buttonView: View) {
        buttonView.animate()
            .scaleX(0.35f)
            .scaleY(0.35f)
            .alpha(0f)
            .setDuration(140)
            .withEndAction {
                AnalysisServiceState.setEnabled(this, false)
                stopSelf()
            }
            .start()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

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

        // Registrar recebimento de oferta — zera o contador de "sem ofertas"
        lastOfferReceivedAt = System.currentTimeMillis()

        val runnable = Runnable {
            Log.i(
                "FloatingAnalytics",
                ">>> Processando corrida (após debounce): ${rideData.appSource.displayName}, R$ ${rideData.ridePrice}, source=${rideData.extractionSource}"
            )

            // Registrar corrida no rastreador de demanda
            DemandTracker.recordRideOffer(rideData)

            val location = locationHelper.getCurrentLocation()
            val processingResult = try {
                processRideDetection(
                    context = RideDetectionContext(
                        rideData = rideData,
                        currentLocation = location,
                        lastResolvedCity = lastResolvedCity,
                        lastResolvedNeighborhood = lastResolvedNeighborhood,
                        fallbackRegionName = marketDataService.getLastMarketInfo()?.regionName,
                        gpsPickupDistanceKm = locationHelper.estimatePickupDistance(),
                        minPricePerKmReference = RideAnalyzer.getCurrentReferences()["minPricePerKm"] ?: 1.5
                    ),
                    resolveRegion = { latitude, longitude ->
                        resolveRegionFromCoordinates(this, latitude, longitude)
                    }
                )
            } catch (e: Exception) {
                Log.w("FloatingAnalytics", "Falha ao processar localização da corrida", e)
                val pickupDistance = rideData.pickupDistanceKm ?: locationHelper.estimatePickupDistance()
                RideDetectionResult(
                    analysis = RideAnalyzer.analyze(rideData, pickupDistance),
                    city = lastResolvedCity ?: marketDataService.getLastMarketInfo()?.regionName,
                    neighborhood = lastResolvedNeighborhood
                )
            }

            var city = processingResult.city
            var neighborhood = processingResult.neighborhood

            if (city.isNullOrBlank() && location != null) {
                try {
                    val resolvedByCurrentLocation = resolveRegionFromCoordinates(this, location.latitude, location.longitude)
                    city = resolvedByCurrentLocation?.city ?: city
                    neighborhood = resolvedByCurrentLocation?.neighborhood ?: neighborhood
                    if (!city.isNullOrBlank()) {
                        Log.i("FloatingAnalytics", "Fallback de localização atual aplicado na oferta: $city / $neighborhood")
                    }
                } catch (e: Exception) {
                    Log.w("FloatingAnalytics", "Falha no fallback de localização atual da oferta", e)
                }
            }

            if (!city.isNullOrBlank()) {
                lastResolvedCity = city
                lastResolvedNeighborhood = neighborhood
            }

            if (city.isNullOrBlank()) {
                Log.w("FloatingAnalytics", "Oferta detectada sem cidade resolvida — demanda regional pode não contabilizar")
            }

            // Pulsar o botão flutuante para indicar nova corrida
            animateFloatingButtonPulse(floatingButton)

            val analysis = processingResult.analysis

            Log.i("FloatingAnalytics", ">>> Análise: Rec=${analysis.recommendation}")

            showAnalysisCard(analysis)

            scheduleOfferSaveToFirebase(
                rideData = rideData,
                city = city,
                neighborhood = neighborhood,
                gpsLatitude = location?.latitude,
                gpsLongitude = location?.longitude
            )

            // Atualizar notificação com stats atualizados
            updateNotificationWithStats()

        }
        pendingRideRunnable = runnable
        handler.postDelayed(runnable, RIDE_DEBOUNCE_MS)
    }

    private fun scheduleOfferSaveToFirebase(
        rideData: RideData,
        city: String?,
        neighborhood: String?,
        gpsLatitude: Double?,
        gpsLongitude: Double?
    ) {
        val saveRunnable = Runnable {
            firestoreManager.saveRideOffer(
                rideData = rideData,
                city = city,
                neighborhood = neighborhood,
                gpsLatitude = gpsLatitude,
                gpsLongitude = gpsLongitude
            )
        }
        handler.postDelayed(saveRunnable, FIREBASE_OFFER_SAVE_DELAY_MS)
    }

    /**
     * Chamado pelo AccessibilityService quando uma corrida é aceita pelo motorista.
     * Atualiza o status card com as contagens de aceitas.
     */
    fun onRideAccepted(appSource: AppSource) {
        handler.post {
            Log.i("FloatingAnalytics", ">>> Corrida ACEITA: ${appSource.displayName}")
            try {
                firestoreManager.markLatestRideOfferAsAccepted(appSource)
            } catch (e: Exception) {
                Log.w("FloatingAnalytics", "Falha ao marcar oferta aceita no Firestore", e)
            }
            // Atualizar notificação
            updateNotificationWithStats()
        }
    }

    /**
     * Simula uma corrida para testar se o card de análise aparece corretamente.
     * Chamado pelo botão "Testar" na tela inicial.
     */
    fun simulateRide(source: AppSource = AppSource.UNKNOWN) {
        val fakeRide = RideData(
            appSource = source,
            ridePrice = 18.50f,
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
        val action = evaluatePauseUiAction(
            demandStats = DemandTracker.getStats(),
            location = locationHelper.getCurrentLocation(),
            marketInfo = marketDataService.getLastMarketInfo(),
            isStatusCardVisible = isStatusCardVisible
        )

        if (action.shouldPulseWarning) {
            animateFloatingButtonWarningPulse(floatingButton)
        }
        if (action.shouldShowStatusCard) {
            showStatusCard()
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

        bindStatusCardCloseAction(card) { hideStatusCard() }
        val sessionMin = DemandTracker.getStats().sessionDurationMin.toInt()
        bindStatusCardSessionTime(card, sessionMin)

        val paused = AnalysisServiceState.isPaused(this)
        val activateButton = card.findViewById<ImageButton>(R.id.btnActivateAnalysis)
        val pauseButton = card.findViewById<ImageButton>(R.id.btnPauseAnalysis)

        val enabledColor = 0xFF4CAF50.toInt()
        val disabledColor = 0xFF757575.toInt()
        val strokeActive = 0xFF81C784.toInt()
        val strokeInactive = 0xFF9E9E9E.toInt()

        fun buildPillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(fillColor)
                setStroke(dpToPx(1), strokeColor)
            }
        }

        activateButton.setImageResource(R.drawable.ic_power_settings_new_24)
        activateButton.setColorFilter(0xFFFFFFFF.toInt())
        activateButton.background = buildPillDrawable(
            fillColor = if (paused) enabledColor else disabledColor,
            strokeColor = if (paused) strokeActive else strokeInactive
        )
        activateButton.isEnabled = paused
        activateButton.contentDescription = if (paused) "Ativar análise" else "Análise ativa"

        pauseButton.setImageResource(if (paused) R.drawable.ic_play_arrow_24 else R.drawable.ic_pause_24)
        pauseButton.setColorFilter(0xFFFFFFFF.toInt())
        pauseButton.background = buildPillDrawable(
            fillColor = if (paused) disabledColor else enabledColor,
            strokeColor = if (paused) strokeInactive else strokeActive
        )
        pauseButton.isEnabled = true
        pauseButton.contentDescription = if (paused) "Retomar análise" else "Pausar análise"

        activateButton.setOnClickListener {
            AnalysisServiceState.setPaused(this, false)
            populateStatusCard()
            updateFloatingButtonBorderState()
            updateAnalysisCardBorderState()
            updateNotificationWithStats()
        }

        pauseButton.setOnClickListener {
            AnalysisServiceState.setPaused(this, !AnalysisServiceState.isPaused(this))
            populateStatusCard()
            updateFloatingButtonBorderState()
            updateAnalysisCardBorderState()
            updateNotificationWithStats()
        }

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

        // Se o card já está visível e anexado, apenas atualizar os dados sem recriar
        if (analysisCard != null && attached) {
            Log.i("FloatingAnalytics", ">>> Atualizando card existente (in-place)")
            populateCard(analysis)

            // Cancelar qualquer animação em andamento e garantir visibilidade
            analysisCard?.animate()?.cancel()
            analysisCard?.visibility = View.VISIBLE
            analysisCard?.alpha = 1f
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
            isCardVisible = true
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
            y = analysisCardOffsetY
        }

        analysisCard?.let { setupAnalysisCardVerticalDrag(it, params) }

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
        updateAnalysisCardBorderState()

        val pickupDistance = analysis.pickupDistanceKm
        val totalDistanceKm = (analysis.rideData.distanceKm + pickupDistance).coerceAtLeast(0.1)
        val valuePerKm = analysis.rideData.ridePrice.toDouble() / totalDistanceKm
        val valuePerHour = analysis.estimatedEarningsPerHour

        Log.i("FloatingAnalytics", ">>> CARD: preço=R$ ${analysis.rideData.ridePrice}, " +
            "totalKm=${String.format("%.1f", totalDistanceKm)}km, " +
            "R$/km=${String.format("%.2f", valuePerKm)}, R$/h=${String.format("%.2f", valuePerHour)}, source=${analysis.rideData.extractionSource}")

        bindRideAnalysisCard(card, analysis) {
            hideAnalysisCard()
        }
    }

    private fun updateAnalysisCardBorderState() {
        val card = analysisCard ?: return
        val drawable = card.background as? GradientDrawable ?: return

        val isActive = AnalysisServiceState.isEnabled(this)
        val borderColor = if (isActive) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val strokeWidthPx = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val mutableDrawable = drawable.mutate() as? GradientDrawable ?: return
        mutableDrawable.setStroke(strokeWidthPx, borderColor)
    }

    private fun updateFloatingButtonBorderState() {
        val button = floatingButton ?: return

        val isActive = AnalysisServiceState.isEnabled(this)
        val backgroundRes = if (isActive) {
            R.drawable.bg_floating_button_active
        } else {
            R.drawable.bg_floating_button_inactive
        }

        button.background = ContextCompat.getDrawable(this, backgroundRes)
        val drawable = button.background as? GradientDrawable ?: return
        val borderColor = if (isActive) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val fillColor = if (isActive) 0xFF1E1E1E.toInt() else 0xFF2A1A1A.toInt()
        val strokeWidthPx = (3 * resources.displayMetrics.density).toInt().coerceAtLeast(2)

        val mutableDrawable = drawable.mutate() as? GradientDrawable ?: return
        mutableDrawable.setColor(fillColor)
        mutableDrawable.setStroke(strokeWidthPx, borderColor)
        button.alpha = if (isActive) 1f else 0.92f
        button.invalidate()
    }

    private fun hideAnalysisCard() {
        handler.removeCallbacks(hideCardRunnable)
        analysisCard?.let { card ->
            // Cancelar qualquer animação em andamento para evitar race conditions
            card.animate().cancel()
            // Remover imediatamente do WindowManager
            try {
                if (card.isAttachedToWindow) {
                    windowManager.removeView(card)
                }
            } catch (_: Exception) { }
        }
        analysisCard = null
        isCardVisible = false
    }

    private fun setupAnalysisCardVerticalDrag(card: View, params: WindowManager.LayoutParams) {
        val closeButton = card.findViewById<View>(R.id.btnClose)
        var initialY = 0
        var initialTouchY = 0f
        var dragging = false

        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isPointInsideView(card, closeButton, event.rawX, event.rawY)) {
                        return@setOnTouchListener false
                    }
                    initialY = params.y
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!dragging && kotlin.math.abs(dy) > 8) dragging = true
                    if (dragging) {
                        val screenHeight = resources.displayMetrics.heightPixels
                        val maxY = (screenHeight - card.height).coerceAtLeast(140)
                        params.y = (initialY + dy).coerceIn(80, maxY)
                        analysisCardOffsetY = params.y
                        try {
                            windowManager.updateViewLayout(card, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> dragging

                else -> false
            }
        }
    }

    private fun isPointInsideView(parent: View, child: View?, rawX: Float, rawY: Float): Boolean {
        child ?: return false
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val left = parentLocation[0] + child.left
        val top = parentLocation[1] + child.top
        val right = left + child.width
        val bottom = top + child.height

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }
}
