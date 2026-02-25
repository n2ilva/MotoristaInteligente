package com.example.motoristainteligente

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import java.util.Calendar
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
        const val NO_OFFERS_ALERT_CHANNEL_ID = "motorista_inteligente_no_offers_alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID_UPCOMING = 2001
        const val ALERT_NOTIFICATION_ID_DECLINING = 2002
        const val ALERT_NOTIFICATION_ID_NO_OFFERS = 2003
        const val ACTION_STOP = "com.example.motoristainteligente.STOP_SERVICE"
        private const val RIDE_DEBOUNCE_MS = 300L // 300ms para agrupar eventos rápidos
        private const val NO_OFFERS_ALERT_WINDOW_MS = 15 * 60 * 1000L

        // Tempo sem ofertas para sugerir que o motorista se mova 1km
        private const val NO_OFFERS_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutos
        // Cooldown entre alertas de "sem ofertas" (não repetir em menos de 15 min)
        private const val NO_OFFERS_ALERT_COOLDOWN_MS = 15 * 60 * 1000L
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
    private var isCardVisible = false
    private var isStatusCardVisible = false
    private var onlineSessionStartMs: Long = 0L
    private var lastResolvedCity: String? = null
    private var lastResolvedNeighborhood: String? = null
    private var lastUpcomingAlertKey: String? = null
    private var lastDecliningAlertKey: String? = null

    // Rastreamento de "sem ofertas por 10 min"
    private var lastOfferReceivedAt = 0L   // timestamp da última oferta detectada
    private var lastNoOffersAlertAt = 0L  // timestamp do último alerta enviado
    private var analysisCardOffsetY = 135

    private val analysisStateChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFloatingButtonBorderState()
            updateAnalysisCardBorderState()
            if (isStatusCardVisible) {
                populateStatusCard()
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private val hideCardRunnable = Runnable { hideAnalysisCard() }
    private val hideStatusCardRunnable = Runnable { hideStatusCard() }

    // Atualização periódica do status de demanda na notificação
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            updateNotificationWithStats()
            maybeNotifyPeakEvents()
            maybeNotifyNoOffers()
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
        if (!AnalysisServiceState.isPaused(this)) {
            AnalysisServiceState.setPaused(this, false)
        }
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

        return START_NOT_STICKY
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
            val peakSummary = buildPeakSummaryText()
            manager.notify(
                NOTIFICATION_ID,
                createForegroundNotification(this, CHANNEL_ID, ACTION_STOP, peakSummary)
            )
        } catch (_: Exception) { }
    }

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

    private fun formatClockAfterMinutes(deltaMinutes: Int?): String {
        if (deltaMinutes == null) return "—"
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, deltaMinutes.coerceAtLeast(0))
        return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun buildPeakSummaryText(): String {
        val now = currentTimeInfo()
        val city = resolveDriverCityForPeaks()
        val signal = PauseAdvisor.getCityPeakWindowSignal(
            cityName = city,
            hour = now.hour,
            minute = now.minute,
            dayOfWeek = now.dayOfWeek
        )

        val leftLabel = if (signal.inPeakNow) "Atual" else "Último"
        val leftRange = "${signal.currentOrLastStart}-${signal.currentOrLastEnd}"
        val nextRange = "${signal.nextStart}-${signal.nextEnd}"

        return "$leftLabel: $leftRange | Próximo: $nextRange"
    }

    private fun resolveDriverCityForPeaks(): String? {
        val location = locationHelper.getCurrentLocation()
        if (location != null) {
            try {
                val resolved = resolveRegionFromCoordinates(this, location.latitude, location.longitude)
                val city = resolved?.city?.trim().orEmpty()
                if (city.isNotBlank()) return city
            } catch (_: Exception) {
            }
        }
        return lastResolvedCity ?: marketDataService.getLastMarketInfo()?.regionName
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
                        service = this,
                        channelId = ALERT_CHANNEL_ID,
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
                        service = this,
                        channelId = ALERT_CHANNEL_ID,
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

    private fun maybeNotifyNoOffers() {
        try {
            // Só executa se a sessão online estiver ativa
            if (onlineSessionStartMs == 0L) return
            if (!AnalysisServiceState.isEnabled(this)) return

            val now = System.currentTimeMillis()

            // Na primeira execução após ir online, inicializa o timer (evita alerta imediato)
            if (lastOfferReceivedAt == 0L) {
                lastOfferReceivedAt = now
                return
            }

            val idleMs = now - lastOfferReceivedAt
            if (idleMs < NO_OFFERS_IDLE_TIMEOUT_MS) return  // Ainda dentro dos 10 min

            // Cooldown: não repetir dentro de 15 min
            if (lastNoOffersAlertAt > 0L && now - lastNoOffersAlertAt < NO_OFFERS_ALERT_COOLDOWN_MS) return

            val idleMinutes = idleMs / 60_000
            lastNoOffersAlertAt = now
            sendHighPriorityAlertNotification(
                service = this,
                channelId = NO_OFFERS_ALERT_CHANNEL_ID,
                id = ALERT_NOTIFICATION_ID_NO_OFFERS,
                title = "Sem ofertas há ${idleMinutes} min",
                message = "Tente se deslocar ~1 km para aumentar suas chances de receber corridas."
            )
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
        if (AnalysisServiceState.isPaused(this)) {
            Log.i("FloatingAnalytics", ">>> Análise pausada: corrida ignorada")
            return
        }

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

            // Salvar oferta no Firebase para base de cálculo de demanda
            firestoreManager.saveRideOffer(rideData, city, neighborhood)

            // Pulsar o botão flutuante para indicar nova corrida
            animateFloatingButtonPulse(floatingButton)

            val analysis = processingResult.analysis

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
    fun simulateRide(source: AppSource = AppSource.UNKNOWN) {
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
        val pauseButton = card.findViewById<Button>(R.id.btnPauseAnalysis)
        val pauseHint = card.findViewById<TextView>(R.id.tvPauseStateHint)

        pauseButton.text = if (paused) "Ativar análise" else "Pausar análise"
        val buttonColor = if (paused) 0xFF757575.toInt() else 0xFF4CAF50.toInt()
        pauseButton.backgroundTintList = ColorStateList.valueOf(buttonColor)
        val baseHint = if (paused) {
            "Análise pausada. Toque para voltar a analisar corridas."
        } else {
            "Análise ativa. Toque para pausar sem fechar o app."
        }
        pauseHint.text = "$baseHint\n${buildPeakSummaryText()}"

        pauseButton.setOnClickListener {
            val newPaused = !AnalysisServiceState.isPaused(this)
            AnalysisServiceState.setPaused(this, newPaused)
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
        val valuePerKm = analysis.rideData.ridePrice / totalDistanceKm
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

        val isActive = AnalysisServiceState.isEnabled(this) && !AnalysisServiceState.isPaused(this)
        val borderColor = if (isActive) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val strokeWidthPx = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val mutableDrawable = drawable.mutate() as? GradientDrawable ?: return
        mutableDrawable.setStroke(strokeWidthPx, borderColor)
    }

    private fun updateFloatingButtonBorderState() {
        val button = floatingButton ?: return
        val drawable = button.background as? GradientDrawable ?: return

        val isActive = AnalysisServiceState.isEnabled(this) && !AnalysisServiceState.isPaused(this)
        val borderColor = if (isActive) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val strokeWidthPx = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val mutableDrawable = drawable.mutate() as? GradientDrawable ?: return
        mutableDrawable.setStroke(strokeWidthPx, borderColor)
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
