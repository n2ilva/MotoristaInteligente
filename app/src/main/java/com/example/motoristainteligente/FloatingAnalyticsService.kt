package com.example.motoristainteligente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar

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
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.motoristainteligente.STOP_SERVICE"
        private const val RIDE_DEBOUNCE_MS = 300L // 300ms para agrupar eventos rápidos
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

    private val handler = Handler(Looper.getMainLooper())
    private val hideCardRunnable = Runnable { hideAnalysisCard() }
    private val hideStatusCardRunnable = Runnable { hideStatusCard() }

    // Atualização periódica do status de demanda na notificação
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            updateNotificationWithStats()
            handler.postDelayed(this, 60_000) // A cada 1 min
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
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        showFloatingButton()

        // Iniciar atualização periódica da notificação com stats
        handler.post(notificationUpdateRunnable)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        // Salvar resumo da sessão no Firestore
        try {
            val stats = DemandTracker.getStats()
            if (stats.sessionDurationMin > 1) {
                firestoreManager.saveSessionSummary(stats)
            }
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

    private fun createNotification(): Notification {
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

        val stats = DemandTracker.getStats()
        val contentText = when (stats.trend) {
            DemandTracker.DemandTrend.RISING -> "Demanda subindo"
            DemandTracker.DemandTrend.FALLING -> "Demanda caindo"
            DemandTracker.DemandTrend.STABLE -> "Demanda estável"
        }

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
            val notification = createNotification()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
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
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // Tempo de sessão
        val hours = stats.sessionDurationMin / 60
        val mins = stats.sessionDurationMin % 60
        card.findViewById<TextView>(R.id.tvSessionTime).text =
            if (hours > 0) "${hours}h ${mins}min" else "${mins}min"

        // Demanda: baseada na comparação hora-a-hora
        val demandText: String
        val demandColor: Int
        when {
            stats.ridesPreviousHour == 0 && stats.ridesLastHour == 0 -> {
                demandText = "Sem dados"
                demandColor = 0xFF9E9E9E.toInt()
            }
            stats.ridesPreviousHour == 0 -> {
                demandText = "Coletando dados"
                demandColor = 0xFFFFC107.toInt()
            }
            stats.ridesLastHour > stats.ridesPreviousHour -> {
                demandText = "Demanda subindo"
                demandColor = 0xFF4CAF50.toInt()
            }
            stats.ridesLastHour < stats.ridesPreviousHour -> {
                demandText = "Demanda caindo"
                demandColor = 0xFFF44336.toInt()
            }
            else -> {
                demandText = "Demanda estável"
                demandColor = 0xFFFFC107.toInt()
            }
        }
        card.findViewById<TextView>(R.id.tvDemandLevel).apply {
            text = demandText
            setTextColor(demandColor)
        }

        // Alerta: sem corridas nos últimos 15 minutos
        val layoutAlert = card.findViewById<LinearLayout>(R.id.layoutNoRidesAlert)
        val dividerAlert = card.findViewById<View>(R.id.dividerAfterAlert)
        if (stats.noRidesLast15Min) {
            layoutAlert.visibility = View.VISIBLE
            dividerAlert.visibility = View.VISIBLE
        } else {
            layoutAlert.visibility = View.GONE
            dividerAlert.visibility = View.GONE
        }

        // Corridas por plataforma (ofertas / aceitas)
        val uberLabel = if (stats.acceptedRidesUber > 0)
            "${stats.totalRidesUber} (${stats.acceptedRidesUber} ✓)"
        else "${stats.totalRidesUber}"
        val ninetyNineLabel = if (stats.acceptedRides99 > 0)
            "${stats.totalRides99} (${stats.acceptedRides99} ✓)"
        else "${stats.totalRides99}"
        card.findViewById<TextView>(R.id.tvRidesUber).text = uberLabel
        card.findViewById<TextView>(R.id.tvRides99).text = ninetyNineLabel

        // Preço médio por plataforma
        card.findViewById<TextView>(R.id.tvAvgPriceUber).text =
            if (stats.avgPriceUber > 0) String.format("R$ %.2f", stats.avgPriceUber) else "—"
        card.findViewById<TextView>(R.id.tvAvgPrice99).text =
            if (stats.avgPrice99 > 0) String.format("R$ %.2f", stats.avgPrice99) else "—"

        // Tempo médio por plataforma
        card.findViewById<TextView>(R.id.tvAvgTimeUber).text =
            if (stats.avgTimeUber > 0) String.format("%.0f min", stats.avgTimeUber) else "—"
        card.findViewById<TextView>(R.id.tvAvgTime99).text =
            if (stats.avgTime99 > 0) String.format("%.0f min", stats.avgTime99) else "—"

        // Ofertas por hora por plataforma
        card.findViewById<TextView>(R.id.tvSessionHourly).text = "${stats.offersPerHourUber}"
        card.findViewById<TextView>(R.id.tvAvgPrice).text = "${stats.offersPerHour99}"

        // Footer: recomendação + qualidade das corridas aceitas
        val pauseBg = card.findViewById<FrameLayout>(R.id.pauseBackground)
        val tvPauseAdvice = card.findViewById<TextView>(R.id.tvPauseAdvice)
        val tvPauseReason = card.findViewById<TextView>(R.id.tvPauseReason)
        val tvLocation = card.findViewById<TextView>(R.id.tvLocation)
        val tvPeakNext = card.findViewById<TextView>(R.id.tvPeakNext)

        // Verificar horários de baixa demanda (9h-11:30 e 14:30-16:30)
        val minute = Calendar.getInstance().get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute
        val isLowDemandHour = (timeInMinutes in 540..690) || (timeInMinutes in 870..990)

        // Texto principal do rodapé
        val mainReason = pauseRec.reasons.firstOrNull()?.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Sc}\\s]"), "")?.trim() ?: ""
        if (isLowDemandHour) {
            tvPauseAdvice.text = "GUARDE O CARRO"
            tvPauseReason.text = "Economize gasolina e energia mental"
        } else if (stats.acceptedBelowAverage) {
            tvPauseAdvice.text = "ACEITE CORRIDAS MELHORES"
            tvPauseReason.text = "Suas corridas aceitas estão abaixo da média"
        } else if (pauseRec.shouldPause) {
            tvPauseAdvice.text = "PAUSAR AGORA"
            tvPauseReason.text = mainReason
        } else {
            tvPauseAdvice.text = mainReason.ifEmpty { "Janela favorável" }
            tvPauseReason.text = ""
        }

        // Localização do motorista
        val regionName = marketInfo?.regionName ?: "Desconhecida"
        tvLocation.text = "Localização: $regionName"

        // Próximo pico — usa apenas o texto descritivo do PauseAdvisor
        val nextPeakInfo = PauseAdvisor.getNextPeakInfo(hour)
        tvPeakNext.text = nextPeakInfo

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

        // Botão fechar
        card.findViewById<View>(R.id.btnCloseStatus).setOnClickListener {
            hideStatusCard()
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
