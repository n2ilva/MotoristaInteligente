package com.example.motoristainteligente

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serviço de acessibilidade que monitora os apps Uber Driver e 99 Driver.
 *
 * Captura eventos de:
 * - Notificações de novas corridas
 * - Mudanças de conteúdo na tela (quando o app mostra oferta de corrida)
 *
 * Extrai dados da corrida via regex e envia ao FloatingAnalyticsService
 * para análise em tempo real.
 */
class RideInfoAccessibilityService : AccessibilityService() {

    private data class OfferCandidate(
        val price: Double,
        val distanceKm: Double?,
        val estimatedTimeMin: Int?,
        val pickupDistanceKm: Double?,
        val pickupTimeMin: Int?,
        val userRating: Double?,
        val score: Int
    )

    /**
     * Nó estruturado extraído da árvore de acessibilidade.
     * Preserva o resourceId para classificação semântica.
     */
    private data class SemanticNode(
        val resourceId: String,
        val idSuffix: String,
        val text: String,
        val description: String,
        val depth: Int,
        val traversalIndex: Int
    ) {
        val combinedText: String get() = buildString {
            if (text.isNotBlank()) append(text).append(' ')
            if (description.isNotBlank() && description != text) append(description)
        }.trim()
    }

    /**
     * Categoria semântica de um nó, inferida pelo seu resource ID.
     */
    private enum class NodeCategory {
        PRICE, PICKUP_DISTANCE, PICKUP_TIME, RIDE_DISTANCE, RIDE_TIME,
        ADDRESS, ACTION, UNKNOWN
    }

    /**
     * Resultado da extração estruturada por nós.
     */
    private data class StructuredExtraction(
        val price: Double?,
        val rideDistanceKm: Double?,
        val rideTimeMin: Int?,
        val pickupDistanceKm: Double?,
        val pickupTimeMin: Int?,
        val userRating: Double? = null,
        val confidence: Int,
        val source: String
    )

    companion object {
        private const val TAG = "RideAccessibility"

        // Pacotes monitorados - incluir variações conhecidas
        private val UBER_PACKAGES = setOf(
            "com.ubercab.driver",
            "com.ubercab",
            "com.ubercab.eats",
            "com.uber.driver",
            "com.uber"
        )
        private val NINETY_NINE_PACKAGES = setOf(
            "cc.nineninetaxi.driver",
            "com.nineninetaxi.driver",
            "com.driver.go99",
            "cc.nineninetaxi",
            "com.nineninetaxi",
            "br.com.driver99",
            "com.go99.driver",
            "com.go99",
            "br.com.99",
            "app.99",
            "com.app99.driver",
            "com.app99"
        )
        private val ALL_MONITORED = UBER_PACKAGES + NINETY_NINE_PACKAGES

        // Pacote do nosso próprio app (IGNORAR)
        private const val OWN_PACKAGE = "com.example.motoristainteligente"

        // Padrões de extração de dados
        // Aceita: R$ 15,50 / R$15.50 / R$ 8,00 / R$ 125,90 / R$7.5 / R$ 15
        private val PRICE_PATTERN = Regex("""R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""")
        // Fallback sem R$: 15,50 / 125.90 (2 casas para evitar conflito com km/min)
        private val FALLBACK_PRICE_PATTERN = Regex("""\b(\d{1,4}[,\.]\d{2})\b""")
        // Aceita: 8.2 km / 8,2km / 12.5 Km / 3km
        private val DISTANCE_PATTERN = Regex("""(\d{1,3}[,\.]?\d*)\s*km""", RegexOption.IGNORE_CASE)
        // Aceita: 15 min / 8min / 20 minutos
        private val TIME_PATTERN = Regex("""(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)
        // Nota/rating do usuário (quando exposta na oferta)
        private val USER_RATING_PATTERN = Regex(
            """(?:nota|avalia(?:ç|c)ão|rating|estrelas?)\s*[:]?\s*(\d(?:[\.,]\d{1,2})?)|\b(\d(?:[\.,]\d{1,2}))\s*[★⭐]|[★⭐]\s*(\d(?:[\.,]\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        // Padrões para distância/tempo de PICKUP (ir até o passageiro)
        // Ex: "Buscar em 3 min" / "2,5 km até embarque" / "chegada: 4 min" / "pickup 5 min (2,3 km)"
        private val PICKUP_DISTANCE_PATTERN = Regex(
            """(?:buscar|embarque|pickup|retirada|chegar|chegada|até\s+(?:o\s+)?passageiro|ir\s+até)[^\d]{0,20}(\d{1,3}(?:[,\.]\d+)?)\s*km""",
            RegexOption.IGNORE_CASE
        )
        private val PICKUP_TIME_PATTERN = Regex(
            """(?:buscar|embarque|pickup|retirada|chegar|chegada|até\s+(?:o\s+)?passageiro|ir\s+até)[^\d]{0,20}(\d{1,3})\s*min""",
            RegexOption.IGNORE_CASE
        )
        // Padrão inline: "X min (Y km)" — comum na Uber para exibir pickup
        private val PICKUP_INLINE_PATTERN = Regex(
            """(\d{1,2})\s*min\s*\(?\s*(\d{1,3}(?:[,\.]\d+)?)\s*km\s*\)?""",
            RegexOption.IGNORE_CASE
        )

        // Padrão universal OCR: "Xmin (Y,Zkm)" ou "X minutos (Y.Z km)"
        // 99:   "3min (1,1km)"  / "8min (4,4km)"
        // Uber: "7 minutos (3.0 km) de distância" / "Viagem de 10 minutos (5.6 km)"
        private val ROUTE_PAIR_PATTERN = Regex(
            """(\d{1,3})\s*min(?:utos?)?\s*\(\s*(\d{1,3}(?:[,\.]\d+)?)\s*km\s*\)""",
            RegexOption.IGNORE_CASE
        )

        // Cooldowns separados por tipo de evento
        private var lastDetectedTime = 0L
        private const val NOTIFICATION_COOLDOWN = 1200L     // 1.2s para notificações
        private const val WINDOW_STATE_COOLDOWN = 1500L     // 1.5s para nova tela/popup
        private const val WINDOW_CONTENT_COOLDOWN = 2500L   // 2.5s para mudança de conteúdo

        // Deduplicação por janela de tempo (evita travar em "duplicado eterno")
        private var lastDetectedHash = ""
        private const val DUPLICATE_SUPPRESSION_WINDOW_MS = 4500L

        // Palavras que indicam que o texto é do NOSSO CARD (auto-detecção)
        private val OWN_CARD_MARKERS = listOf(
            "COMPENSA", "NÃO COMPENSA", "NEUTRO",
            "R\$/km", "Ganho/h", "Motorista Inteligente",
            "Score:"
        )

        // Indicadores MÍNIMOS de corrida: ter PREÇO + pelo menos 1 destes
        // O app Uber nem sempre usa palavras como "aceitar" - mostra apenas dados
        private val RIDE_INDICATORS = listOf(
            "km", "min", "destino", "pickup",
            "aceitar", "accept", "viagem", "corrida",
            "novo pedido", "nova viagem", "solicita",
            "trip", "ride", "passageiro", "passenger",
            "recusar", "decline", "ignorar",
            "embarque", "retirada", "entrega",
            "rota", "distância", "ganho", "estimat",
            "drop", "fare", "tarifa", "valor"
        )

        // Sinais fortes de oferta real
        private val ACTION_KEYWORDS = listOf(
            "aceitar", "accept", "recusar", "decline", "ignorar",
            "novo pedido", "nova viagem", "solicitação", "request"
        )

        // Contexto de corrida (origem/destino/passageiro)
        private val CONTEXT_KEYWORDS = listOf(
            "embarque", "destino", "passageiro", "pickup", "dropoff", "origem", "entrega"
        )

        // Ex.: 1-11 min / 2-6 min
        private val MIN_RANGE_PATTERN = Regex("""\b\d{1,2}\s*[-–]\s*\d{1,2}\s*min\b""", RegexOption.IGNORE_CASE)
        // Ex.: 4 min / 12min
        private val MIN_VALUE_PATTERN = Regex("""\b\d{1,3}\s*min\b""", RegexOption.IGNORE_CASE)
        // Ex.: 3 km / 2,5 km / 1.2km
        private val KM_VALUE_PATTERN = Regex("""\b\d{1,3}(?:[\.,]\d+)?\s*km\b""", RegexOption.IGNORE_CASE)

        // Preço mínimo para considerar como corrida real
        private const val MIN_RIDE_PRICE = 3.0
        private const val DEBUG_TEXT_SAMPLE_MAX = 220
        private const val DEBUG_COOLDOWN_LOG_INTERVAL = 2000L
        private const val EVENT_LOG_THROTTLE_MS = 1200L
        private const val DIAGNOSTIC_LOG_THROTTLE_MS = 10000L
        private const val CONTENT_EVENT_CONTEXT_WINDOW_MS = 8000L
        private const val LIMITED_DATA_ALERT_COOLDOWN_MS = 20000L
        private const val AUTO_DEBUG_DUMP_ENABLED = true
        private const val AUTO_DEBUG_DUMP_INTERVAL_MS = 3000L
        private const val AUTO_DEBUG_MAX_CHARS = 5000
        private const val OCR_FALLBACK_ENABLED = true
        private const val OCR_FALLBACK_MIN_INTERVAL_MS = 5000L  // 5s entre tentativas OCR

        // Debounce: atrasar processamento para garantir que só o ÚLTIMO evento seja processado
        private const val DEBOUNCE_DELAY = 500L // 500ms

        var isServiceConnected = false
            private set

        private var lastCooldownDebugAt = 0L
        private var lastLimitedDataAlertAt = 0L
        private val lastStateEventByPackage = mutableMapOf<String, Long>()
        private val lastNotificationEventByPackage = mutableMapOf<String, Long>()
    }

    // Handler para debounce - só processa o último evento
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingRideData: RideData? = null
    private var pendingRunnable: Runnable? = null
    private val lastEventLogAt = mutableMapOf<String, Long>()
    private val lastDiagnosticLogAt = mutableMapOf<String, Long>()
    private var lastAutoDebugDumpAt = 0L
    private var lastOcrFallbackAt = 0L
    private var ninetyNineDisconnected = false  // Suprime OCR quando 99 está desconectado
    private var uberOnlineIdle = false           // Suprime OCR quando Uber está em tela idle
    private var ocrConsecutiveFails = 0           // Backoff progressivo: falhas OCR consecutivas
    private val OCR_BACKOFF_MAX_MS = 60_000L      // Máximo backoff: 60s

    private fun shouldLogEvent(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEventLogAt[key] ?: 0L
        if (now - last < EVENT_LOG_THROTTLE_MS) return false
        lastEventLogAt[key] = now
        return true
    }

    private fun shouldLogDiagnostic(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastDiagnosticLogAt[key] ?: 0L
        if (now - last < DIAGNOSTIC_LOG_THROTTLE_MS) return false
        lastDiagnosticLogAt[key] = now
        return true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        Log.i(TAG, "=== SERVIÇO DE ACESSIBILIDADE CONECTADO ===")
        Log.i(TAG, "Monitorando pacotes Uber: $UBER_PACKAGES")
        Log.i(TAG, "Monitorando pacotes 99: $NINETY_NINE_PACKAGES")
        Log.i(TAG, "FloatingAnalyticsService ativo: ${FloatingAnalyticsService.instance != null}")

        if (FloatingAnalyticsService.instance == null) {
            Log.i(TAG, "Iniciando FloatingAnalyticsService no onServiceConnected")
            ensureFloatingServiceRunning()
        }
    }

    private fun isMonitoredPackage(packageName: String): Boolean {
        if (packageName == OWN_PACKAGE) return false
        if (packageName in ALL_MONITORED) return true

        if (ALL_MONITORED.any { packageName.startsWith(it) }) return true

        val lower = packageName.lowercase()
        val looksLikeUber = lower.contains("uber")
        val looksLikeNineNine = lower.contains("99") || lower.contains("ninenine")
        val looksLikeDriverApp = lower.contains("driver") || lower.contains("taxi") || lower.contains("motorista")

        return (looksLikeUber || looksLikeNineNine) && looksLikeDriverApp
    }

    private fun detectAppSource(packageName: String): AppSource {
        val lower = packageName.lowercase()

        if (packageName in UBER_PACKAGES || UBER_PACKAGES.any { packageName.startsWith(it) } || lower.contains("uber")) {
            return AppSource.UBER
        }
        if (packageName in NINETY_NINE_PACKAGES ||
            NINETY_NINE_PACKAGES.any { packageName.startsWith(it) } ||
            lower.contains("99") ||
            lower.contains("ninenine")
        ) {
            return AppSource.NINETY_NINE
        }
        return AppSource.UNKNOWN
    }

    /**
     * Detecta se o app usa renderização customizada que resulta em árvore de acessibilidade VAZIA.
     * App 99 (com.app99.driver) usa Compose/Canvas sem labels → nenhum texto nos nós.
     * Para esses apps, OCR é a ÚNICA via funcional de extração.
     */
    private fun isEmptyTreeApp(packageName: String): Boolean {
        val source = detectAppSource(packageName)
        if (source != AppSource.NINETY_NINE) return false

        // Verificar se a árvore realmente está vazia (rápido: checar se há algum texto)
        val sampleText = extractTextFromInteractiveWindows(packageName)
        return sampleText.isBlank()
    }

    private fun ensureFloatingServiceRunning() {
        try {
            val intent = Intent(this, FloatingAnalyticsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar FloatingAnalyticsService automaticamente", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        // IGNORAR eventos do nosso próprio app
        if (packageName == OWN_PACKAGE || packageName.startsWith(OWN_PACKAGE)) return

        if (!isMonitoredPackage(packageName)) return

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        val eventLogKey = "$packageName|$eventTypeName"
        if (shouldLogEvent(eventLogKey)) {
            Log.d(TAG, ">>> [$packageName] $eventTypeName")
        }

        // Para notificações, sempre logar o texto
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notifText = event.text?.joinToString(" ") ?: "(vazio)"
            Log.d(TAG, "    Notif texto: ${notifText.take(200)}")
        }

        val eventType = event.eventType
        val now = System.currentTimeMillis()

        // Cooldown diferenciado por tipo de evento
        val cooldown = when (eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> NOTIFICATION_COOLDOWN
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> WINDOW_STATE_COOLDOWN
            else -> WINDOW_CONTENT_COOLDOWN
        }

        if (now - lastDetectedTime < cooldown) {
            if (now - lastCooldownDebugAt > DEBUG_COOLDOWN_LOG_INTERVAL) {
                lastCooldownDebugAt = now
                Log.d(TAG, "Ignorado por cooldown: tipo=${AccessibilityEvent.eventTypeToString(eventType)}, delta=${now - lastDetectedTime}ms, cooldown=${cooldown}ms")
            }
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                lastNotificationEventByPackage[packageName] = now
                handleNotification(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window STATE changed = uma nova tela/janela apareceu (ex: popup de corrida)
                lastStateEventByPackage[packageName] = now
                // STATE_CHANGED indica nova tela/popup — resetar flags de idle e backoff OCR
                if (detectAppSource(packageName) == AppSource.UBER) {
                    uberOnlineIdle = false
                }
                ocrConsecutiveFails = 0
                handleWindowChange(event, packageName, isStateChange = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Eventos de conteúdo/seleção/scroll (99 costuma expor texto aqui)
                val lastState = lastStateEventByPackage[packageName] ?: 0L
                val lastNotif = lastNotificationEventByPackage[packageName] ?: 0L
                val hasRecentContext =
                    (now - lastState) <= CONTENT_EVENT_CONTEXT_WINDOW_MS ||
                        (now - lastNotif) <= CONTENT_EVENT_CONTEXT_WINDOW_MS

                val eventText = event.text?.joinToString(" ")?.trim().orEmpty()
                val strongSignalInEvent = hasStrongRideSignal(eventText)
                val sourceTextPreview = if (!hasRecentContext && !strongSignalInEvent) {
                    extractTextFromEventSource(event)
                } else {
                    ""
                }
                val strongSignalInSource = sourceTextPreview.isNotBlank() && hasStrongRideSignal(sourceTextPreview)
                val strongSignalInNodeTree =
                    !hasRecentContext &&
                        !strongSignalInEvent &&
                        !strongSignalInSource &&
                        hasStrongRideSignalInNodeTree(packageName)

                if (!hasRecentContext && !strongSignalInEvent && !strongSignalInSource && !strongSignalInNodeTree) {
                    val key = "content-no-context|$packageName"
                    if (shouldLogDiagnostic(key)) {
                        Log.d(TAG, "CONTENT sem contexto recente → tentando OCR ($packageName)")
                    }

                    // Sem contexto STATE/NOTIF e sem sinal forte na árvore:
                    // Disparar OCR apenas para apps com árvore vazia (99).
                    // Uber tem árvore funcional — OCR dispara via handleWindowChange se árvore falhar.
                    if (isEmptyTreeApp(packageName)) {
                        if (requestOcrFallbackForOffer(packageName, "content-no-context")) {
                            Log.d(TAG, "OCR disparado para $packageName (content sem contexto)")
                        }
                    }

                    maybeWriteAutoDebugDump(
                        reason = "content-no-context",
                        packageName = packageName,
                        event = event,
                        eventText = eventText,
                        sourceText = sourceTextPreview
                    )
                    return
                }

                if (!hasRecentContext && (strongSignalInEvent || strongSignalInSource || strongSignalInNodeTree)) {
                    val key = "content-no-context-allowed|$packageName"
                    if (shouldLogDiagnostic(key)) {
                        Log.d(TAG, "CONTENT sem contexto permitido por sinal forte de corrida (event/source/tree)")
                    }
                }
                handleWindowChange(event, packageName, isStateChange = false)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de acessibilidade interrompido")
        isServiceConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
        debounceHandler.removeCallbacksAndMessages(null)
        pendingRideData = null
        pendingRunnable = null
        Log.w(TAG, "Serviço de acessibilidade destruído")
    }

    // ========================
    // Handlers de Eventos
    // ========================

    private fun handleNotification(event: AccessibilityEvent, packageName: String) {
        val text = event.text?.joinToString(" ") ?: return
        if (text.isBlank()) return

        // Verificar se é texto do nosso próprio card
        if (isOwnCardText(text)) {
            Log.d(TAG, "Notificação ignorada: texto do próprio app")
            return
        }

        // Rastrear estado de conexão do 99 para suprimir OCR quando desconectado
        if (detectAppSource(packageName) == AppSource.NINETY_NINE) {
            if (text.contains("Desconectado", ignoreCase = true) ||
                text.contains("Você está offline", ignoreCase = true)) {
                ninetyNineDisconnected = true
                Log.d(TAG, "99 marcado como DESCONECTADO — OCR suprimido")
                return
            } else if (text.contains("corrida", ignoreCase = true) ||
                       text.contains("Conectado", ignoreCase = true) ||
                       text.contains("online", ignoreCase = true)) {
                ninetyNineDisconnected = false
            }
        }

        // Notificação da Uber reseta idle (pode ser notif de corrida)
        if (detectAppSource(packageName) == AppSource.UBER) {
            uberOnlineIdle = false
            ocrConsecutiveFails = 0
        }

        // Notificação da 99 do tipo "Toque para selecionar uma X corrida(s)..."
        // → sinal de que há corrida disponível, mas o texto da notif não tem dados
        // → disparar OCR imediatamente para capturar a tela da 99
        val is99RideSignal = detectAppSource(packageName) == AppSource.NINETY_NINE &&
            text.contains("corrida", ignoreCase = true)

        if (is99RideSignal && !isLikelyRideOffer(text, isStateChange = true)) {
            Log.i(TAG, "=== NOTIFICAÇÃO 99 com sinal de corrida (sem dados) → OCR ===")
            Log.i(TAG, "Texto: ${text.take(300)}")
            requestOcrFallbackForOffer(packageName, "99-notif-ride-signal")
            return
        }

        if (!isLikelyRideOffer(text, isStateChange = true)) {
            Log.d(TAG, "Notificação ignorada por baixa confiança de corrida")
            return
        }

        Log.i(TAG, "=== NOTIFICAÇÃO de $packageName ===")
        Log.i(TAG, "Texto: ${text.take(300)}")
        // Notificações têm prioridade - são mais confiáveis que window changes
        tryParseRideData(text, packageName, isNotification = true, extractionSource = "notification")
    }

    private fun handleWindowChange(event: AccessibilityEvent, packageName: String, isStateChange: Boolean) {
        val eventText = event.text?.joinToString(" ")?.trim().orEmpty()

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao acessar rootInActiveWindow: ${e.message}")
            null
        }

        if (rootNode == null) {
            if (eventText.isNotBlank()) {
                val hasAnyPriceInEvent = PRICE_PATTERN.containsMatchIn(eventText) || FALLBACK_PRICE_PATTERN.containsMatchIn(eventText)
                val indicatorCountInEvent = RIDE_INDICATORS.count { eventText.contains(it, ignoreCase = true) }
                Log.d(
                    TAG,
                    "rootInActiveWindow nulo, usando apenas event.text (hasPrice=$hasAnyPriceInEvent, indicators=$indicatorCountInEvent): ${eventText.take(DEBUG_TEXT_SAMPLE_MAX)}"
                )
                if (hasAnyPriceInEvent && (isStateChange || indicatorCountInEvent >= 1)) {
                    tryParseRideData(
                        text = eventText,
                        packageName = packageName,
                        isNotification = false,
                        extractionSource = "event-text"
                    )
                }
            } else {
                Log.d(TAG, "rootInActiveWindow nulo e event.text vazio")
            }
            return
        }

        // Verificar se a janela ativa é realmente do app monitorado
        // rootInActiveWindow pode retornar a janela do nosso overlay
        val rootPackage = rootNode.packageName?.toString() ?: ""
        if (rootPackage == OWN_PACKAGE || rootPackage.startsWith(OWN_PACKAGE)) {
            try { rootNode.recycle() } catch (_: Exception) { }
            Log.d(TAG, "Ignorado: rootInActiveWindow pertence ao próprio app")
            return
        }

        // Evitar contaminação entre apps (ex.: evento Uber lendo árvore da 99)
        val rootLooksMismatched =
            rootPackage.isNotBlank() &&
                rootPackage != packageName &&
                !rootPackage.startsWith(packageName) &&
                !packageName.startsWith(rootPackage)

        if (rootLooksMismatched) {
            Log.d(TAG, "Root package divergente do evento. event=$packageName root=$rootPackage. Ignorando root e usando fallback.")
            try { rootNode.recycle() } catch (_: Exception) { }

            val sourceFallback = extractTextFromEventSource(event)
            val mismatchSource: String
            val finalTextMismatch: String
            if (sourceFallback.isNotBlank()) {
                Log.d(TAG, "Fallback event.source usado após mismatch (${sourceFallback.length} chars)")
                mismatchSource = "event-source"
                finalTextMismatch = sourceFallback
            } else {
                val windowsFallback = extractTextFromInteractiveWindows(packageName)
                if (windowsFallback.isBlank()) {
                    Log.d(TAG, "Ignorado: mismatch + sem texto em source/windows")
                    return
                }
                Log.d(TAG, "Fallback windows usado após mismatch (${windowsFallback.length} chars)")
                mismatchSource = "windows"
                finalTextMismatch = windowsFallback
            }

            processCandidateText(finalTextMismatch, packageName, isStateChange, mismatchSource)
            return
        }

        val allText = extractAllText(rootNode)
        try { rootNode.recycle() } catch (_: Exception) { }

        val combinedText = buildString {
            if (eventText.isNotBlank()) append(eventText).append(' ')
            if (allText.isNotBlank()) append(allText)
        }.trim()

        val (finalText, extractionSource) = if (combinedText.isBlank()) {
            val sourceFallback = extractTextFromEventSource(event)
            if (sourceFallback.isNotBlank()) {
                Log.d(TAG, "Fallback event.source usado (${sourceFallback.length} chars)")
                sourceFallback to "event-source"
            } else {
                val windowsFallback = extractTextFromInteractiveWindows(packageName)
                if (windowsFallback.isBlank()) {
                    // Texto vazio em todos os fallbacks — OCR para qualquer app
                    if (requestOcrFallbackForOffer(packageName, "empty-text-all-sources")) {
                        Log.d(TAG, "Texto vazio em todas as fontes para $packageName → OCR disparado")
                    } else {
                        Log.d(TAG, "Ignorado: texto vazio + OCR em cooldown ($packageName)")
                    }
                    return
                }
                Log.d(TAG, "Fallback windows usado (${windowsFallback.length} chars)")
                windowsFallback to "windows"
            }
        } else {
            combinedText to "node-tree"
        }

        val recoveredText = recoverFromForeignIdLeakIfNeeded(finalText, packageName)
        if (recoveredText.isBlank()) {
            Log.d(TAG, "Ignorado: conteúdo contaminado por IDs de outro app e sem recuperação")
            return
        }

        processCandidateText(recoveredText, packageName, isStateChange, extractionSource)
    }

    private fun recoverFromForeignIdLeakIfNeeded(text: String, packageName: String): String {
        if (!looksLikeForeignIdLeak(text, packageName)) return text

        Log.d(TAG, "Detectado vazamento de IDs de outro app. Tentando recuperar texto pelo pacote correto: $packageName")
        val recovered = extractTextFromInteractiveWindows(packageName)

        if (recovered.isBlank()) return ""
        if (looksLikeForeignIdLeak(recovered, packageName)) return ""
        return recovered
    }

    private fun looksLikeForeignIdLeak(text: String, packageName: String): Boolean {
        val lower = text.lowercase()
        val eventSource = detectAppSource(packageName)
        val hasUberIds = lower.contains("com.ubercab.driver:id") || lower.contains("com.uber.driver:id")
        val has99Ids = lower.contains("com.app99.driver:id") || lower.contains("com.nineninetaxi.driver:id")

        return when (eventSource) {
            AppSource.UBER -> has99Ids
            AppSource.NINETY_NINE -> hasUberIds
            AppSource.UNKNOWN -> false
        }
    }

    private fun processCandidateText(
        finalText: String,
        packageName: String,
        isStateChange: Boolean,
        rootPackage: String,
        allowOcrFallback: Boolean = true
    ) {
        if (finalText.isBlank()) {
            Log.d(TAG, "Ignorado: texto final vazio")
            return
        }

        // Muitos eventos de Uber/99 trazem apenas IDs de layout (sem conteúdo útil de corrida).
        // Ex.: com.ubercab.driver:id/rootView ...
        if (looksLikeStructuralIdOnlyText(finalText)) {
            return
        }

        // Verificar se é texto do nosso próprio card (loop de auto-detecção)
        if (isOwnCardText(finalText)) {
            return
        }

        val hasPrice = PRICE_PATTERN.containsMatchIn(finalText) || FALLBACK_PRICE_PATTERN.containsMatchIn(finalText)
        val hasKmToken = KM_VALUE_PATTERN.containsMatchIn(finalText)
        val hasMinToken = MIN_VALUE_PATTERN.containsMatchIn(finalText) || MIN_RANGE_PATTERN.containsMatchIn(finalText)

        // NOVA ABORDAGEM (solicitada): só processar quando tiver R$ + KM + MIN na tela
        if (!(hasPrice && hasKmToken && hasMinToken)) {
            // Fallback via keyword-search e node-search NÃO se aplica quando fonte é OCR
            // (a árvore de acessibilidade já está vazia, buscar nós não vai encontrar nada)
            if (rootPackage != "ocr-fallback") {
                val keywordFallback = extractTextByKeywordSearch(packageName)
                if (keywordFallback.isNotBlank()) {
                    val hasPriceInFallback = PRICE_PATTERN.containsMatchIn(keywordFallback) || FALLBACK_PRICE_PATTERN.containsMatchIn(keywordFallback)
                    val hasKmInFallback = KM_VALUE_PATTERN.containsMatchIn(keywordFallback)
                    val hasMinInFallback = MIN_VALUE_PATTERN.containsMatchIn(keywordFallback) || MIN_RANGE_PATTERN.containsMatchIn(keywordFallback)
                    if (hasPriceInFallback && hasKmInFallback && hasMinInFallback) {
                        Log.d(TAG, "Fallback keyword-search encontrou R$ + KM + MIN (${keywordFallback.length} chars)")
                        processCandidateText(keywordFallback, packageName, isStateChange, "keyword-search")
                        return
                    }
                }

                val nodeOfferFallback = extractOfferTextFromNodes(packageName)
                if (nodeOfferFallback.isNotBlank()) {
                    val hasPriceInNode = PRICE_PATTERN.containsMatchIn(nodeOfferFallback) || FALLBACK_PRICE_PATTERN.containsMatchIn(nodeOfferFallback)
                    val hasKmInNode = KM_VALUE_PATTERN.containsMatchIn(nodeOfferFallback)
                    val hasMinInNode = MIN_VALUE_PATTERN.containsMatchIn(nodeOfferFallback) || MIN_RANGE_PATTERN.containsMatchIn(nodeOfferFallback)
                    if (hasPriceInNode && hasKmInNode && hasMinInNode) {
                        Log.d(TAG, "Fallback node-search encontrou R$ + KM + MIN (${nodeOfferFallback.length} chars)")
                        processCandidateText(nodeOfferFallback, packageName, isStateChange, "node-search")
                        return
                    }
                }
            }

            val key = "missing-core-tokens|$packageName|${if (isStateChange) "STATE" else "CONTENT"}"
            if (shouldLogDiagnostic(key)) {
                Log.d(
                    TAG,
                    "Ignorado sem tokens obrigatórios (R$+KM+MIN). hasPrice=$hasPrice, hasKm=$hasKmToken, hasMin=$hasMinToken"
                )
            }

            if (allowOcrFallback && rootPackage != "ocr-fallback") {
                requestOcrFallbackForOffer(packageName, "missing-core-tokens")
            }

            maybeWriteAutoDebugDump(
                reason = "missing-core-tokens",
                packageName = packageName,
                event = null,
                eventText = finalText,
                sourceText = ""
            )
            return
        }

        if (!isLikelyRideOffer(finalText, isStateChange)) {
            val key = "low-confidence|$packageName|${if (isStateChange) "STATE" else "CONTENT"}"
            if (shouldLogDiagnostic(key)) {
                Log.d(TAG, "Ignorado por baixa confiança (${if (isStateChange) "STATE" else "CONTENT"}): ${finalText.take(DEBUG_TEXT_SAMPLE_MAX)}")
            }
            return
        }

        val indicatorCount = RIDE_INDICATORS.count { finalText.contains(it, ignoreCase = true) }

        Log.i(TAG, "=== ${if (isStateChange) "STATE" else "CONTENT"}_CHANGED de $packageName ===")
        Log.i(TAG, "Indicadores encontrados: $indicatorCount, Pacote da janela: $rootPackage, source=${normalizeExtractionSource(rootPackage)}")
        Log.i(TAG, "Texto (300 chars): ${finalText.take(300)}")
        tryParseRideData(
            text = finalText,
            packageName = packageName,
            isNotification = false,
            extractionSource = normalizeExtractionSource(rootPackage)
        )
    }

    private fun normalizeExtractionSource(rawSource: String): String {
        return when (rawSource) {
            "notification" -> "notification"
            "event-text" -> "event-text"
            "ocr-fallback" -> "ocr"
            "keyword-search" -> "keyword-search"
            "node-search" -> "node-search"
            "event-source" -> "event-source"
            "windows" -> "windows"
            else -> "node-tree"
        }
    }

    private fun looksLikeStructuralIdOnlyText(text: String): Boolean {
        val compact = text.trim()
        if (compact.isBlank()) return true

        val tokens = compact.split(Regex("\\s+"))
        if (tokens.isEmpty()) return true

        val idLikeCount = tokens.count {
            it.contains(":id/") || it.startsWith("android:id/")
        }

        val hasUsefulRideMarkers =
            PRICE_PATTERN.containsMatchIn(compact) ||
                FALLBACK_PRICE_PATTERN.containsMatchIn(compact) ||
                KM_VALUE_PATTERN.containsMatchIn(compact) ||
                MIN_VALUE_PATTERN.containsMatchIn(compact) ||
                MIN_RANGE_PATTERN.containsMatchIn(compact) ||
                compact.contains("aceitar", ignoreCase = true) ||
                compact.contains("accept", ignoreCase = true)

        // Se for majoritariamente IDs e sem marcadores reais de corrida, é ruído estrutural.
        return idLikeCount >= 3 && !hasUsefulRideMarkers
    }

    private fun extractTextByKeywordSearch(expectedPackage: String): String {
        return try {
            val allWindows = windows ?: return ""
            val sb = StringBuilder()
            val keywordQueries = listOf("R$", "km", "min", "aceitar", "accept", "corrida", "viagem")
            val expectedSource = detectAppSource(expectedPackage)

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)

                val canUseWindow =
                    rootPkg.isNotBlank() &&
                    rootPkg != OWN_PACKAGE &&
                    (rootPkg == expectedPackage ||
                        rootPkg.startsWith(expectedPackage) ||
                        expectedPackage.startsWith(rootPkg) ||
                        (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource))

                if (!canUseWindow) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }

                for (query in keywordQueries) {
                    val nodes = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null } ?: continue
                    for (node in nodes) {
                        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }

                        val parent = try { node.parent } catch (_: Exception) { null }
                        parent?.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        parent?.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }

                        try { parent?.recycle() } catch (_: Exception) { }
                        try { node.recycle() } catch (_: Exception) { }
                    }
                }

                try { root.recycle() } catch (_: Exception) { }
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractTextFromEventSource(event: AccessibilityEvent): String {
        val source = try { event.source } catch (_: Exception) { null } ?: return ""
        return try {
            val sb = StringBuilder()
            extractTextRecursive(source, sb, 0)
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        } finally {
            try { source.recycle() } catch (_: Exception) { }
        }
    }

    private fun extractTextFromInteractiveWindows(expectedPackage: String): String {
        return try {
            val sb = StringBuilder()
            val allWindows = windows ?: return ""
            val expectedSource = detectAppSource(expectedPackage)

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)

                val shouldUseWindow =
                    rootPkg.isNotBlank() &&
                    rootPkg != OWN_PACKAGE &&
                    (rootPkg == expectedPackage ||
                        rootPkg.startsWith(expectedPackage) ||
                        expectedPackage.startsWith(rootPkg) ||
                        (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource))

                if (!shouldUseWindow) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }

                extractTextRecursive(root, sb, 0)
                try { root.recycle() } catch (_: Exception) { }
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isLikelyRideOffer(text: String, isStateChange: Boolean): Boolean {
        val hasPrice = PRICE_PATTERN.containsMatchIn(text) || FALLBACK_PRICE_PATTERN.containsMatchIn(text)
        if (!hasPrice) return false

        val actionCount = ACTION_KEYWORDS.count { text.contains(it, ignoreCase = true) }
        val contextCount = CONTEXT_KEYWORDS.count { text.contains(it, ignoreCase = true) }
        val hasKm = KM_VALUE_PATTERN.containsMatchIn(text)
        val hasMin = MIN_VALUE_PATTERN.containsMatchIn(text)
        val hasMinRange = MIN_RANGE_PATTERN.containsMatchIn(text)
        val minRangeCount = MIN_RANGE_PATTERN.findAll(text).count()
        val hasExplicitCurrency = text.contains("R$", ignoreCase = true)
        val hasPlusPrice = Regex("""\+\s*R\$\s*\d""").containsMatchIn(text)

        val confidence =
            (if (actionCount > 0) 3 else 0) +
            (if (contextCount > 0) 2 else 0) +
            (if (hasKm) 2 else 0) +
            (if (hasMin) 1 else 0) +
            (if (hasMinRange) 1 else 0) +
            (if (hasExplicitCurrency) 1 else 0) +
            (if (hasPlusPrice) 1 else 0) +
            (if (minRangeCount >= 2) 1 else 0)

        // Regra de atalho para padrão comum da Uber: +R$ + faixas de tempo (ex.: 1-11 min, 1-4 min)
        val uberLikeOfferPattern = hasPrice && hasMinRange && (minRangeCount >= 2 || hasKm)

        // Regra:
        // - Se tiver ação explícita (aceitar/recusar etc), aceitar.
        // - Se bater padrão típico de oferta da Uber, aceitar.
        // - Senão, usar limiar de confiança moderado.
        val accepted = actionCount > 0 || uberLikeOfferPattern || confidence >= if (isStateChange) 3 else 4

        if (!accepted) {
            val key = "confidence-detail|${if (isStateChange) "STATE" else "CONTENT"}|$actionCount|$contextCount|$hasKm|$hasMin|$hasMinRange|$minRangeCount|$hasExplicitCurrency|$hasPlusPrice|$confidence"
            if (shouldLogDiagnostic(key)) {
                Log.d(
                    TAG,
                    "Baixa confiança: action=$actionCount, context=$contextCount, km=$hasKm, min=$hasMin, range=$hasMinRange, ranges=$minRangeCount, moeda=$hasExplicitCurrency, plusPrice=$hasPlusPrice, score=$confidence"
                )
            }
        }
        return accepted
    }

    private fun hasStrongRideSignalInNodeTree(expectedPackage: String): Boolean {
        val fromNodes = extractOfferTextFromNodes(expectedPackage)
        if (fromNodes.isNotBlank() && hasStrongRideSignal(fromNodes)) {
            return true
        }

        val fromWindows = extractTextFromInteractiveWindows(expectedPackage)
        if (fromWindows.isNotBlank() && hasStrongRideSignal(fromWindows)) {
            return true
        }

        return false
    }

    private fun extractOfferTextFromNodes(expectedPackage: String): String {
        return try {
            val allWindows = windows ?: return ""
            val expectedSource = detectAppSource(expectedPackage)
            val sb = StringBuilder()

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)

                val canUseWindow =
                    rootPkg.isNotBlank() &&
                        rootPkg != OWN_PACKAGE &&
                        (rootPkg == expectedPackage ||
                            rootPkg.startsWith(expectedPackage) ||
                            expectedPackage.startsWith(rootPkg) ||
                            (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource))

                if (!canUseWindow) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }

                val queries = listOf("R$", "km", "min")
                for (query in queries) {
                    val nodes = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null } ?: continue
                    for (node in nodes) {
                        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        node.hintText?.let { if (it.isNotBlank()) sb.append(it).append(' ') }

                        val parent = try { node.parent } catch (_: Exception) { null }
                        parent?.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        parent?.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }

                        try { parent?.recycle() } catch (_: Exception) { }
                        try { node.recycle() } catch (_: Exception) { }
                    }
                }

                try { root.recycle() } catch (_: Exception) { }
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun hasStrongRideSignal(text: String): Boolean {
        if (text.isBlank()) return false

        val hasPrice = PRICE_PATTERN.containsMatchIn(text) || FALLBACK_PRICE_PATTERN.containsMatchIn(text)
        val hasKm = KM_VALUE_PATTERN.containsMatchIn(text)
        val hasMin = MIN_VALUE_PATTERN.containsMatchIn(text) || MIN_RANGE_PATTERN.containsMatchIn(text)
        val hasAction = ACTION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        val hasContext = CONTEXT_KEYWORDS.any { text.contains(it, ignoreCase = true) }

        if (hasPrice && hasKm && hasMin) return true
        if (hasPrice && hasAction) return true
        if (hasPrice && hasContext && (hasKm || hasMin)) return true

        return false
    }

    /**
     * Detecta se o texto contém marcadores do nosso próprio card de análise.
     * Evita o loop infinito de auto-detecção.
     */
    private fun isOwnCardText(text: String): Boolean {
        val matchCount = OWN_CARD_MARKERS.count { text.contains(it, ignoreCase = true) }
        return matchCount >= 2  // Se 2+ marcadores do nosso card estão presentes, é auto-detecção
    }

    // ========================
    // Extração de Texto
    // ========================

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        extractTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun extractTextRecursive(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int
    ) {
        if (depth > 20) return // Prevenir recursão infinita

        node.text?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }

        // Campos extras que alguns apps (incluindo Uber/99) usam em vez de text/contentDescription
        node.hintText?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }
        node.stateDescription?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }
        node.tooltipText?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }
        node.paneTitle?.let {
            if (it.isNotBlank()) {
                sb.append(it).append(" ")
            }
        }
        // NOTA: viewIdResourceName NÃO é incluído aqui para evitar poluição no texto.
        // Resource IDs são usados na extração ESTRUTURADA (extractSemanticNodes) como labels.

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            extractTextRecursive(child, sb, depth + 1)
            try { child.recycle() } catch (_: Exception) { }
        }
    }

    // ========================
    // Parsing de Dados
    // ========================

    private fun tryParseRideData(
        text: String,
        packageName: String,
        isNotification: Boolean,
        extractionSource: String
    ) {
        val appSource = detectAppSource(packageName)

        // ========== PASSO 1: Tentar extração ESTRUTURADA por nós (mais precisa) ==========
        val structured = if (!isNotification) {
            tryStructuredExtraction(packageName)
        } else null

        if (structured != null && structured.price != null && structured.confidence >= 3) {
            Log.i(TAG, ">>> Extração ESTRUTURADA bem-sucedida (conf=${structured.confidence}, source=${structured.source})")
            buildAndEmitRideData(
                appSource = appSource,
                packageName = packageName,
                price = structured.price,
                rideDistanceKm = structured.rideDistanceKm,
                rideTimeMin = structured.rideTimeMin,
                pickupDistanceKm = structured.pickupDistanceKm,
                pickupTimeMin = structured.pickupTimeMin,
                userRating = null,
                extractionSource = "structured-${structured.source}",
                isNotification = false,
                text = text
            )
            return
        }

        // ========== PASSO 2: OCR — formato "Xmin (Ykm)" route pairs (99 e Uber) ==========
        // Ambos os apps mostram pickup e corrida como pares "min (km)"
        val ocrParsed = if (extractionSource == "ocr") {
            parseOcrRoutePairs(text)
        } else null

        if (ocrParsed != null && ocrParsed.price != null) {
            Log.i(TAG, ">>> Parse OCR route pairs: preço=R$ ${ocrParsed.price}, rideKm=${ocrParsed.rideDistanceKm}, rideMin=${ocrParsed.rideTimeMin}, pickupKm=${ocrParsed.pickupDistanceKm ?: "?"}, pickupMin=${ocrParsed.pickupTimeMin ?: "?"}, rating=${ocrParsed.userRating ?: "?"}")
            buildAndEmitRideData(
                appSource = appSource,
                packageName = packageName,
                price = ocrParsed.price,
                rideDistanceKm = ocrParsed.rideDistanceKm,
                rideTimeMin = ocrParsed.rideTimeMin,
                pickupDistanceKm = ocrParsed.pickupDistanceKm,
                pickupTimeMin = ocrParsed.pickupTimeMin,
                userRating = ocrParsed.userRating,
                extractionSource = "ocr-route-pairs",
                isNotification = isNotification,
                text = text
            )
            return
        }

        // OCR tem pares min(km) mas sem preço R$ — continua para PASSO 3 para buscar preço
        if (ocrParsed != null && ocrParsed.rideDistanceKm != null) {
            Log.d(TAG, "OCR: pares encontrados mas sem preço R$ — tentando regex para preço")
            // Continua para PASSO 3 mas usa dados do OCR como fallback
        }

        // ========== PASSO 3: Fallback para parsing por REGEX com desambiguação posicional ==========
        val primaryPriceMatches = PRICE_PATTERN.findAll(text)
            .filter { it.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { p -> p >= MIN_RIDE_PRICE } == true }
            .toList()
        val priceMatches = if (primaryPriceMatches.isNotEmpty()) {
            primaryPriceMatches
        } else {
            FALLBACK_PRICE_PATTERN.findAll(text)
                .filter { it.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { p -> p >= MIN_RIDE_PRICE } == true }
                .toList()
        }

        if (priceMatches.isEmpty()) {
            Log.d(TAG, "Nenhum preço encontrado no texto de $packageName")
            return
        }

        // Posição do preço no texto (usado para desambiguação pickup vs corrida)
        val pricePosition = priceMatches.first().range.first

        val bestCandidate = selectBestOfferCandidate(text, priceMatches) ?: return
        val price = bestCandidate.price

        if (price < MIN_RIDE_PRICE) {
            Log.d(TAG, "Preço muito baixo para ser corrida: R$ $price (mínimo: R$ $MIN_RIDE_PRICE)")
            return
        }

        // Desambiguar distâncias/tempos usando posição relativa ao preço
        val disambiguated = disambiguateByPosition(text, pricePosition)

        // Prioridade: OCR route pairs > candidato contextual > desambiguação posicional > global > estimativa
        val distance = ocrParsed?.rideDistanceKm
            ?: bestCandidate.distanceKm
            ?: disambiguated.rideDistanceKm
            ?: parseRideDistanceFromText(text, pricePosition)
            ?: estimateDistance(price)

        val time = ocrParsed?.rideTimeMin
            ?: bestCandidate.estimatedTimeMin
            ?: disambiguated.rideTimeMin
            ?: parseRideTimeFromText(text, pricePosition)
            ?: estimateTime(distance)

        val pickupKm = ocrParsed?.pickupDistanceKm
            ?: bestCandidate.pickupDistanceKm
            ?: disambiguated.pickupDistanceKm
        val pickupMin = ocrParsed?.pickupTimeMin
            ?: bestCandidate.pickupTimeMin
            ?: disambiguated.pickupTimeMin

        Log.i(TAG, ">>> Regex parsing: preço=R$ $price, rideKm=$distance, rideMin=$time, pickupKm=${pickupKm ?: "?"}, pickupMin=${pickupMin ?: "?"}")

        buildAndEmitRideData(
            appSource = appSource,
            packageName = packageName,
            price = price,
            rideDistanceKm = distance,
            rideTimeMin = time,
            pickupDistanceKm = pickupKm,
            pickupTimeMin = pickupMin,
            userRating = bestCandidate.userRating,
            extractionSource = extractionSource,
            isNotification = isNotification,
            text = text
        )
    }

    /**
     * Constrói RideData e envia ao FloatingAnalyticsService.
     */
    private fun buildAndEmitRideData(
        appSource: AppSource,
        packageName: String,
        price: Double,
        rideDistanceKm: Double?,
        rideTimeMin: Int?,
        pickupDistanceKm: Double?,
        pickupTimeMin: Int?,
        userRating: Double?,
        extractionSource: String,
        isNotification: Boolean,
        text: String
    ) {
        val distance = rideDistanceKm ?: estimateDistance(price)
        val time = rideTimeMin ?: estimateTime(distance)

        // DEDUPLICAÇÃO
        val contentHash = "${appSource}_${price}_${extractionSource}_${distance}_${time}"
        val now = System.currentTimeMillis()
        if (contentHash == lastDetectedHash && now - lastDetectedTime < DUPLICATE_SUPPRESSION_WINDOW_MS) {
            Log.d(TAG, "Duplicado ignorado (${now - lastDetectedTime}ms): R$ $price")
            return
        }
        lastDetectedHash = contentHash

        val addresses = extractAddresses(text)

        val rideData = RideData(
            appSource = appSource,
            ridePrice = price,
            distanceKm = distance,
            estimatedTimeMin = time,
            pickupDistanceKm = pickupDistanceKm,
            pickupTimeMin = pickupTimeMin,
            userRating = userRating,
            extractionSource = extractionSource,
            pickupAddress = addresses.first,
            dropoffAddress = addresses.second,
            rawText = text.take(500)
        )

        lastDetectedTime = now

        val distLabel = if (rideDistanceKm != null) "extraído" else "estimado"
        val timeLabel = if (rideTimeMin != null) "extraído" else "estimado"

        Log.i(TAG, "=========================================")
        Log.i(TAG, "CORRIDA DETECTADA!")
        Log.i(TAG, "  Fonte: ${if (isNotification) "NOTIFICAÇÃO" else "TELA"}")
        Log.i(TAG, "  Source: $extractionSource")
        Log.i(TAG, "  App: ${appSource.displayName} ($packageName)")
        Log.i(TAG, "  Preço: R$ $price")
        Log.i(TAG, "  Dist corrida: ${distance}km ($distLabel)")
        Log.i(TAG, "  Dist pickup: ${pickupDistanceKm?.let { "${it}km (extraído)" } ?: "— (GPS)"}")
        Log.i(TAG, "  Tempo corrida: ${time}min ($timeLabel)")
        Log.i(TAG, "  Tempo pickup: ${pickupTimeMin?.let { "${it}min (extraído)" } ?: "—"}")
        Log.i(TAG, "  Nota: ${userRating?.let { String.format("%.1f", it) } ?: "—"}")
        Log.i(TAG, "  Embarque: ${addresses.first.ifEmpty { "—" }}")
        Log.i(TAG, "  Destino: ${addresses.second.ifEmpty { "—" }}")
        Log.i(TAG, "=========================================")

        queueRideDataForFloatingService(rideData, "Corrida enviada ao FloatingAnalyticsService")
    }

    private fun queueRideDataForFloatingService(rideData: RideData, successMessage: String) {
        pendingRideData = rideData
        pendingRunnable?.let { debounceHandler.removeCallbacks(it) }

        val runnable = Runnable {
            val data = pendingRideData ?: return@Runnable
            pendingRideData = null

            val service = FloatingAnalyticsService.instance
            if (service != null) {
                service.onRideDetected(data)
                Log.i(TAG, ">>> $successMessage (após debounce)")
            } else {
                Log.w(TAG, "FloatingAnalyticsService.instance é NULL. Tentando iniciar serviço automaticamente...")
                ensureFloatingServiceRunning()

                debounceHandler.postDelayed({
                    val retryService = FloatingAnalyticsService.instance
                    if (retryService != null) {
                        retryService.onRideDetected(data)
                        Log.i(TAG, ">>> $successMessage (após retry automático)")
                    } else {
                        Log.e(TAG, "!!! Falha no retry: FloatingAnalyticsService ainda NULL")
                    }
                }, 1000L)
            }
        }
        pendingRunnable = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_DELAY)
        Log.d(TAG, "Corrida agendada para envio (debounce ${DEBOUNCE_DELAY}ms)")
    }

    private fun selectBestOfferCandidate(text: String, matches: List<MatchResult>): OfferCandidate? {
        val candidates = matches.mapNotNull { match ->
            val rawPrice = match.groupValues.getOrNull(1)?.replace(",", ".") ?: return@mapNotNull null
            val parsedPrice = rawPrice.toDoubleOrNull() ?: return@mapNotNull null

            // Contexto expandido: ±250 chars ao redor do preço (antes era 120 — insuficiente)
            val index = match.range.first
            val start = (index - 250).coerceAtLeast(0)
            val end = (match.range.last + 250).coerceAtMost(text.length - 1)
            val context = text.substring(start, end + 1)

            // Texto APÓS o preço — mais provável de ser dados da corrida (destino)
            val afterPrice = text.substring(match.range.last + 1, text.length.coerceAtMost(match.range.last + 300))
            // Texto ANTES do preço — mais provável de ser pickup
            val beforePrice = text.substring((index - 200).coerceAtLeast(0), index)

            // Distância/tempo da CORRIDA: priorizar texto DEPOIS do preço
            val distAfter = parseFirstKmValue(afterPrice)
            val distBefore = parseFirstKmValue(beforePrice)
            val distContext = parseFirstKmValue(context)
            val rideDistanceKm = distAfter ?: distContext

            val timeAfter = parseFirstMinValue(afterPrice)
            val timeBefore = parseFirstMinValue(beforePrice)
            val timeContext = parseFirstMinValue(context)
            val rideTimeMin = timeAfter ?: timeContext

            // Pickup: usar texto ANTES do preço
            val pickupKm = distBefore.takeIf { distAfter != null && it != distAfter }
                ?: parsePickupDistanceFromText(text)
            val pickupMin = timeBefore.takeIf { timeAfter != null && it != timeAfter }
                ?: parsePickupTimeFromText(text)

            val rating = parseUserRatingFromText(context) ?: parseUserRatingFromText(text)

            val hasPlusPrice = Regex("""\+\s*R\$\s*\d""").containsMatchIn(context)
            val hasAction = ACTION_KEYWORDS.any { context.contains(it, ignoreCase = true) }
            val hasContext = CONTEXT_KEYWORDS.any { context.contains(it, ignoreCase = true) }

            val score =
                (if (rideDistanceKm != null) 3 else 0) +
                (if (rideTimeMin != null) 3 else 0) +
                (if (hasAction) 2 else 0) +
                (if (hasContext) 2 else 0) +
                (if (hasPlusPrice) 2 else 0) +
                (if (rating != null) 1 else 0) +
                (if (pickupKm != null) 1 else 0)

            OfferCandidate(
                price = parsedPrice,
                distanceKm = rideDistanceKm,
                estimatedTimeMin = rideTimeMin,
                pickupDistanceKm = pickupKm,
                pickupTimeMin = pickupMin,
                userRating = rating,
                score = score
            )
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.score < 4) {
            val key = "offer-candidate-low-score"
            if (shouldLogDiagnostic(key)) {
                Log.d(TAG, "Oferta rejeitada por baixa pontuação contextual (score=${best.score})")
            }
            return null
        }

        return best
    }

    // ========================
    // Extração Estruturada por Nós
    // ========================

    /**
     * Extrai dados da corrida de forma estruturada usando resource IDs dos nós de acessibilidade.
     * Resource IDs como "fare_amount", "pickup_eta", "trip_distance" são labels semânticos
     * que identificam EXATAMENTE o que cada valor representa.
     */
    private fun tryStructuredExtraction(packageName: String): StructuredExtraction? {
        val nodes = extractSemanticNodes(packageName)
        if (nodes.isEmpty()) return null

        var price: Double? = null
        var rideDistanceKm: Double? = null
        var rideTimeMin: Int? = null
        var pickupDistanceKm: Double? = null
        var pickupTimeMin: Int? = null
        var confidence = 0
        var priceNodeIndex = -1

        // Passo 1: Classificar nós por resource ID
        for ((idx, node) in nodes.withIndex()) {
            val category = classifyNodeCategory(node.idSuffix)
            val text = node.combinedText
            if (text.isBlank()) continue

            when (category) {
                NodeCategory.PRICE -> {
                    if (price == null) {
                        val priceMatch = PRICE_PATTERN.find(text)
                            ?: FALLBACK_PRICE_PATTERN.find(text)
                        val v = priceMatch?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (v != null && v >= MIN_RIDE_PRICE) {
                            price = v
                            priceNodeIndex = idx
                            confidence += 2
                        }
                    }
                }
                NodeCategory.RIDE_DISTANCE -> {
                    if (rideDistanceKm == null) {
                        val v = DISTANCE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (v != null && v in 0.2..300.0) {
                            rideDistanceKm = v
                            confidence += 2
                        }
                    }
                }
                NodeCategory.RIDE_TIME -> {
                    if (rideTimeMin == null) {
                        val v = TIME_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (v != null && v in 1..300) {
                            rideTimeMin = v
                            confidence += 2
                        }
                    }
                }
                NodeCategory.PICKUP_DISTANCE -> {
                    if (pickupDistanceKm == null) {
                        val v = DISTANCE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                            ?.replace(",", ".")?.toDoubleOrNull()
                        if (v != null && v in 0.1..50.0) {
                            pickupDistanceKm = v
                            confidence += 2
                        }
                    }
                }
                NodeCategory.PICKUP_TIME -> {
                    if (pickupTimeMin == null) {
                        val v = TIME_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: run {
                                // Tentar extrair de range como "1-11 min"
                                val range = MIN_RANGE_PATTERN.find(text)
                                range?.let {
                                    Regex("""\d{1,3}""").findAll(it.value)
                                        .mapNotNull { m -> m.value.toIntOrNull() }
                                        .maxOrNull()
                                }
                            }
                        if (v != null && v in 1..120) {
                            pickupTimeMin = v
                            confidence += 2
                        }
                    }
                }
                else -> { /* UNKNOWN, ADDRESS, ACTION — ignorar no parsing de dados */ }
            }
        }

        // Passo 2: Se temos preço mas faltam dist/tempo, usar posição dos nós
        // Nós ANTES do preço → provável pickup. Nós DEPOIS → provável corrida.
        if (price != null && priceNodeIndex >= 0 && (rideDistanceKm == null || rideTimeMin == null)) {
            for ((idx, node) in nodes.withIndex()) {
                val category = classifyNodeCategory(node.idSuffix)
                if (category != NodeCategory.UNKNOWN) continue
                val text = node.combinedText
                if (text.isBlank()) continue

                val kmVal = DISTANCE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                    ?.replace(",", ".")?.toDoubleOrNull()
                val minVal = TIME_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: MIN_RANGE_PATTERN.find(text)?.let { range ->
                        Regex("""\d{1,3}""").findAll(range.value)
                            .mapNotNull { m -> m.value.toIntOrNull() }
                            .maxOrNull()
                    }

                if (idx < priceNodeIndex) {
                    // Nó ANTES do preço → provável pickup
                    if (kmVal != null && pickupDistanceKm == null && kmVal in 0.1..50.0) {
                        pickupDistanceKm = kmVal
                        confidence += 1
                    }
                    if (minVal != null && pickupTimeMin == null && minVal in 1..120) {
                        pickupTimeMin = minVal
                        confidence += 1
                    }
                } else if (idx > priceNodeIndex) {
                    // Nó DEPOIS do preço → provável corrida
                    if (kmVal != null && rideDistanceKm == null && kmVal in 0.2..300.0) {
                        rideDistanceKm = kmVal
                        confidence += 1
                    }
                    if (minVal != null && rideTimeMin == null && minVal in 1..300) {
                        rideTimeMin = minVal
                        confidence += 1
                    }
                }
            }
        }

        if (price == null && confidence < 2) return null

        Log.d(TAG, "Structured extraction: price=$price, rideKm=$rideDistanceKm, rideMin=$rideTimeMin, " +
                "pickupKm=$pickupDistanceKm, pickupMin=$pickupTimeMin, conf=$confidence, nodes=${nodes.size}")

        return StructuredExtraction(
            price = price,
            rideDistanceKm = rideDistanceKm,
            rideTimeMin = rideTimeMin,
            pickupDistanceKm = pickupDistanceKm,
            pickupTimeMin = pickupTimeMin,
            confidence = confidence,
            source = "node-semantic"
        )
    }

    /**
     * Extrai nós semânticos (com resource ID) da árvore de acessibilidade.
     */
    private fun extractSemanticNodes(packageName: String): List<SemanticNode> {
        val result = mutableListOf<SemanticNode>()
        var traversalIndex = 0

        fun collectNodes(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 15) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val rid = node.viewIdResourceName.orEmpty()

            if (text.isNotBlank() || desc.isNotBlank() || rid.isNotBlank()) {
                val suffix = rid.substringAfterLast("/", "")
                result.add(
                    SemanticNode(
                        resourceId = rid,
                        idSuffix = suffix,
                        text = text,
                        description = desc,
                        depth = depth,
                        traversalIndex = traversalIndex++
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                collectNodes(child, depth + 1)
                try { child?.recycle() } catch (_: Exception) { }
            }
        }

        try {
            val allWindows = windows ?: return emptyList()
            val expectedSource = detectAppSource(packageName)

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)

                val canUseWindow =
                    rootPkg.isNotBlank() &&
                        rootPkg != OWN_PACKAGE &&
                        (rootPkg == packageName ||
                            rootPkg.startsWith(packageName) ||
                            packageName.startsWith(rootPkg) ||
                            (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource))

                if (canUseWindow) {
                    collectNodes(root, 0)
                }

                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao extrair nós semânticos: ${e.message}")
        }

        return result
    }

    /**
     * Classifica a categoria semântica de um nó pelo sufixo do resource ID.
     * Ex: "fare_amount" → PRICE, "pickup_eta" → PICKUP_TIME
     */
    private fun classifyNodeCategory(idSuffix: String): NodeCategory {
        if (idSuffix.isBlank()) return NodeCategory.UNKNOWN

        val lower = idSuffix.lowercase()

        // PRICE: fare, price, amount, valor, tarifa, earnings, ganho, cost
        if (Regex("(?:fare|price|amount|valor|tarifa|earning|ganho|cost|surge|promo)").containsMatchIn(lower)) {
            return NodeCategory.PRICE
        }

        // PICKUP patterns (antes de distance/time genéricos para evitar falso positivo)
        if (Regex("(?:pickup_dist|eta_dist|arrival_dist|buscar_dist)").containsMatchIn(lower)) {
            return NodeCategory.PICKUP_DISTANCE
        }
        if (Regex("(?:pickup_eta|pickup_time|arrival|eta_time|eta_min|chegada|buscar_time|time_to_pickup)").containsMatchIn(lower)) {
            return NodeCategory.PICKUP_TIME
        }

        // RIDE distance/time
        if (Regex("(?:trip_dist|ride_dist|route_dist|trip_length)").containsMatchIn(lower)) {
            return NodeCategory.RIDE_DISTANCE
        }
        // "distance" genérico sem "pickup" → provavelmente corrida
        if (lower.contains("distance") && !lower.contains("pickup") && !lower.contains("eta")) {
            return NodeCategory.RIDE_DISTANCE
        }
        if (Regex("(?:trip_time|ride_time|trip_duration|duration|ride_eta|trip_eta|estimated_time)").containsMatchIn(lower)) {
            return NodeCategory.RIDE_TIME
        }

        // ADDRESS
        if (Regex("(?:address|location|origin|destination|destino|pickup_loc|dropoff|endereco)").containsMatchIn(lower)) {
            return NodeCategory.ADDRESS
        }

        // ACTION
        if (Regex("(?:accept|decline|reject|cancel|aceitar|recusar|ignorar|pular|skip)").containsMatchIn(lower)) {
            return NodeCategory.ACTION
        }

        return NodeCategory.UNKNOWN
    }

    // ========================
    // Desambiguação Posicional
    // ========================

    /**
     * Parser específico para texto OCR da 99.
     * A 99 mostra dados no formato:
     *   (X min Y,Z km) Endereço de Pickup
     *   Viagem de W minutos (V.U km)    ← Uber
     *   Endereço de Destino
     *
     * A PRIMEIRA ocorrência é o pickup, a SEGUNDA é a corrida.
     * Funciona para AMBOS os apps (99 e Uber).
     */
    private fun parseOcrRoutePairs(text: String): StructuredExtraction? {
        val routePairs = ROUTE_PAIR_PATTERN.findAll(text).toList()
        if (routePairs.size < 2) {
            Log.d(TAG, "OCR route pairs: encontrou ${routePairs.size} par(es) min(km), precisa de pelo menos 2")
            return null
        }

        // Primeiro par = pickup, segundo = corrida
        val pickupMatch = routePairs[0]
        val rideMatch = routePairs[1]

        val pickupMin = pickupMatch.groupValues[1].toIntOrNull()
        val pickupKm = pickupMatch.groupValues[2].replace(",", ".").toDoubleOrNull()

        val rideMin = rideMatch.groupValues[1].toIntOrNull()
        val rideKm = rideMatch.groupValues[2].replace(",", ".").toDoubleOrNull()

        // Buscar preço no texto (filtrando R$0,00 e valores baixos)
        val priceMatch = PRICE_PATTERN.findAll(text)
            .mapNotNull { m ->
                val v = m.groupValues[1].replace(",", ".").toDoubleOrNull()
                v?.takeIf { it >= MIN_RIDE_PRICE }
            }
            .firstOrNull()
            ?: FALLBACK_PRICE_PATTERN.findAll(text)
                .mapNotNull { m ->
                    val v = m.groupValues[1].replace(",", ".").toDoubleOrNull()
                    v?.takeIf { it >= MIN_RIDE_PRICE }
                }
                .firstOrNull()

        // Buscar rating do passageiro (★ 4,83 / 4,91 ★ / nota: 4.9)
        val ratingMatch = USER_RATING_PATTERN.find(text)
        val rating = ratingMatch?.let {
            (it.groupValues[1].takeIf { g -> g.isNotEmpty() }
                ?: it.groupValues[2].takeIf { g -> g.isNotEmpty() }
                ?: it.groupValues.getOrNull(3)?.takeIf { g -> g.isNotEmpty() })
                ?.replace(",", ".")?.toDoubleOrNull()
        }

        var confidence = 0
        if (rideKm != null) confidence += 2
        if (rideMin != null) confidence += 2
        if (pickupKm != null) confidence++
        if (pickupMin != null) confidence++
        if (priceMatch != null) confidence++
        if (rating != null) confidence++

        Log.d(TAG, "OCR route pairs: pickup=($pickupMin min, $pickupKm km), ride=($rideMin min, $rideKm km), price=$priceMatch, rating=$rating, conf=$confidence")

        if (confidence < 4) return null // precisa de dados mínimos

        return StructuredExtraction(
            price = priceMatch,
            rideDistanceKm = rideKm,
            rideTimeMin = rideMin,
            pickupDistanceKm = pickupKm,
            pickupTimeMin = pickupMin,
            userRating = rating,
            confidence = confidence,
            source = "ocr-route-pairs"
        )
    }

    /**
     * Desambigua múltiplos valores km/min no texto usando posição relativa ao preço.
     * Valores ANTES do preço → pickup. Valores DEPOIS do preço → corrida.
     */
    private fun disambiguateByPosition(text: String, pricePosition: Int): StructuredExtraction {
        val beforePrice = if (pricePosition > 0) text.substring(0, pricePosition) else ""
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val pickupKm = parseFirstKmValue(beforePrice)
        val pickupMin = parseFirstMinValue(beforePrice)
        val rideKm = parseFirstKmValue(afterPrice)
        val rideMin = parseFirstMinValue(afterPrice)

        var confidence = 0
        if (rideKm != null) confidence++
        if (rideMin != null) confidence++
        if (pickupKm != null) confidence++
        if (pickupMin != null) confidence++

        return StructuredExtraction(
            price = null,
            rideDistanceKm = rideKm,
            rideTimeMin = rideMin,
            pickupDistanceKm = pickupKm,
            pickupTimeMin = pickupMin,
            confidence = confidence,
            source = "positional"
        )
    }

    /**
     * Extrai distância da CORRIDA (não pickup) do texto, priorizando valores após o preço.
     */
    private fun parseRideDistanceFromText(text: String, pricePosition: Int): Double? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text
        val afterMatch = DISTANCE_PATTERN.find(afterPrice)
        if (afterMatch != null) {
            val v = afterMatch.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null && v in 0.2..300.0) return v
        }

        // Se não achou após o preço, pegar todos e retornar o MAIOR (provável corrida vs pickup)
        val allMatches = DISTANCE_PATTERN.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
            .filter { it in 0.2..300.0 }
            .toList()

        return if (allMatches.size >= 2) {
            allMatches.maxOrNull()
        } else {
            allMatches.firstOrNull()
        }
    }

    /**
     * Extrai tempo da CORRIDA (não pickup) do texto, priorizando valores após o preço.
     */
    private fun parseRideTimeFromText(text: String, pricePosition: Int): Int? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val rangeMatch = MIN_RANGE_PATTERN.find(afterPrice)
        if (rangeMatch != null) {
            val max = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val simpleMatch = TIME_PATTERN.find(afterPrice)
        val v = simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (v != null && v in 1..300) return v

        val allMatches = TIME_PATTERN.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 1..300 }
            .toList()

        return if (allMatches.size >= 2) {
            allMatches.maxOrNull()
        } else {
            allMatches.firstOrNull()
        }
    }

    /**
     * Extrai o PRIMEIRO valor em km de um fragmento de texto.
     */
    private fun parseFirstKmValue(text: String): Double? {
        val match = DISTANCE_PATTERN.find(text) ?: return null
        val v = match.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        return v.takeIf { it in 0.1..300.0 }
    }

    /**
     * Extrai o PRIMEIRO valor em min de um fragmento de texto.
     * Inclui suporte a ranges como "1-11 min" (retorna o máximo).
     */
    private fun parseFirstMinValue(text: String): Int? {
        val rangeMatch = MIN_RANGE_PATTERN.find(text)
        val simpleMatch = TIME_PATTERN.find(text)

        // Se range aparece antes ou é a única opção, usar o range (max)
        if (rangeMatch != null && (simpleMatch == null || rangeMatch.range.first <= simpleMatch.range.first)) {
            val max = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val v = simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return v.takeIf { it in 1..300 }
    }

    private fun parseDistanceFromText(text: String): Double? {
        return DISTANCE_PATTERN.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.2..300.0 }
    }

    private fun parseMinutesFromText(text: String): Int? {
        val rangeMatch = MIN_RANGE_PATTERN.find(text)
        if (rangeMatch != null) {
            val values = Regex("""\d{1,3}""").findAll(rangeMatch.value)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            val max = values.maxOrNull()
            if (max != null) return max.coerceIn(1, 300)
        }

        val minMatch = MIN_VALUE_PATTERN.find(text)
        val parsed = minMatch
            ?.value
            ?.let { Regex("""\d{1,3}""").find(it)?.value }
            ?.toIntOrNull()

        return parsed?.coerceIn(1, 300)
    }

    private fun parsePickupDistanceFromText(text: String): Double? {
        // 1. Padrão explícito: "buscar/embarque/pickup... X km"
        val explicit = PICKUP_DISTANCE_PATTERN.find(text)
        if (explicit != null) {
            val v = explicit.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null && v in 0.1..50.0) return v
        }
        // 2. Padrão inline: "X min (Y km)" — se aparece ANTES do preço, provavelmente é pickup
        val priceIdx = PRICE_PATTERN.find(text)?.range?.first ?: text.length
        val inlineMatches = PICKUP_INLINE_PATTERN.findAll(text).toList()
        for (m in inlineMatches) {
            if (m.range.first < priceIdx) {
                val km = m.groupValues.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()
                if (km != null && km in 0.1..50.0) return km
            }
        }
        return null
    }

    private fun parsePickupTimeFromText(text: String): Int? {
        // 1. Padrão explícito: "buscar/embarque/pickup... X min"
        val explicit = PICKUP_TIME_PATTERN.find(text)
        if (explicit != null) {
            val v = explicit.groupValues.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 1..120) return v
        }
        // 2. Padrão inline antes do preço
        val priceIdx = PRICE_PATTERN.find(text)?.range?.first ?: text.length
        val inlineMatches = PICKUP_INLINE_PATTERN.findAll(text).toList()
        for (m in inlineMatches) {
            if (m.range.first < priceIdx) {
                val min = m.groupValues.getOrNull(1)?.toIntOrNull()
                if (min != null && min in 1..120) return min
            }
        }
        return null
    }

    private fun parseUserRatingFromText(text: String): Double? {
        val match = USER_RATING_PATTERN.find(text) ?: return null
        val raw = match.groupValues.getOrNull(1).orEmpty().ifBlank {
            match.groupValues.getOrNull(2).orEmpty()
        }
        val rating = raw.replace(",", ".").toDoubleOrNull() ?: return null
        return rating.takeIf { it in 1.0..5.0 }
    }

    private fun requestOcrFallbackForOffer(packageName: String, triggerReason: String): Boolean {
        if (!OCR_FALLBACK_ENABLED) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        // Suprimir OCR quando 99 está desconectado (não há corrida na tela)
        if (detectAppSource(packageName) == AppSource.NINETY_NINE && ninetyNineDisconnected) {
            return false
        }

        // Suprimir OCR quando Uber está em tela idle ("Você está online")
        if (detectAppSource(packageName) == AppSource.UBER && uberOnlineIdle) {
            return false
        }

        val now = System.currentTimeMillis()
        // Backoff progressivo: cooldown base * 2^falhas consecutivas (max 60s)
        val effectiveCooldown = if (ocrConsecutiveFails > 0) {
            (OCR_FALLBACK_MIN_INTERVAL_MS * (1L shl ocrConsecutiveFails.coerceAtMost(4)))
                .coerceAtMost(OCR_BACKOFF_MAX_MS)
        } else {
            OCR_FALLBACK_MIN_INTERVAL_MS
        }
        if (now - lastOcrFallbackAt < effectiveCooldown) return false
        lastOcrFallbackAt = now
        Log.d(TAG, "OCR disparado para $packageName (trigger=$triggerReason)")

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                            screenshot.hardwareBuffer.close()

                            if (bitmap == null) {
                                Log.d(TAG, "OCR fallback: screenshot bitmap nulo")
                                return
                            }

                            val inputImage = InputImage.fromBitmap(bitmap, 0)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                            recognizer.process(inputImage)
                                .addOnSuccessListener { visionText ->
                                    val ocrText = visionText.text.orEmpty().trim()
                                    if (ocrText.isBlank()) {
                                        Log.d(TAG, "OCR fallback: texto vazio")
                                        return@addOnSuccessListener
                                    }

                                    Log.d(TAG, "OCR capturou ${ocrText.length} chars: ${ocrText.take(200).replace('\n', ' ')}")

                                    if (hasStrongRideSignal(ocrText)) {
                                        ocrConsecutiveFails = 0  // Reset backoff
                                        uberOnlineIdle = false   // Não está mais idle
                                        Log.i(TAG, "OCR fallback encontrou sinal forte de corrida (${ocrText.length} chars), trigger=$triggerReason")
                                        processCandidateText(
                                            finalText = ocrText,
                                            packageName = packageName,
                                            isStateChange = false,
                                            rootPackage = "ocr-fallback",
                                            allowOcrFallback = false
                                        )
                                    } else {
                                        Log.d(TAG, "OCR sem sinal forte — texto não contém price+km+min/action")
                                        ocrConsecutiveFails++

                                        // Detectar tela idle da Uber para suprimir OCR futuro
                                        if (detectAppSource(packageName) == AppSource.UBER) {
                                            val lower = ocrText.lowercase()
                                            if (lower.contains("você está online") ||
                                                lower.contains("voce esta online") ||
                                                lower.contains("procurando viagens") ||
                                                lower.contains("procurando corridas")) {
                                                uberOnlineIdle = true
                                                Log.d(TAG, "Uber marcado como IDLE — OCR suprimido até próximo STATE_CHANGED")
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.d(TAG, "OCR fallback falhou: ${e.message}")
                                }
                        } catch (e: Exception) {
                            Log.d(TAG, "OCR fallback erro no processamento: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "OCR fallback screenshot falhou. code=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.d(TAG, "OCR fallback indisponível: ${e.message}")
        }
        return true
    }

    private fun maybeWriteAutoDebugDump(
        reason: String,
        packageName: String,
        event: AccessibilityEvent?,
        eventText: String,
        sourceText: String
    ) {
        if (!AUTO_DEBUG_DUMP_ENABLED) return
        // Dump para AMBOS os apps (Uber e 99) para diagnóstico
        if (detectAppSource(packageName) == AppSource.UNKNOWN) return

        val now = System.currentTimeMillis()
        if (now - lastAutoDebugDumpAt < AUTO_DEBUG_DUMP_INTERVAL_MS) return
        lastAutoDebugDumpAt = now

        try {
            val dumpDir = File(filesDir, "debug_dumps")
            if (!dumpDir.exists()) dumpDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
            val appTag = when (detectAppSource(packageName)) {
                AppSource.UBER -> "uber"
                AppSource.NINETY_NINE -> "99"
                else -> "unknown"
            }
            val file = File(dumpDir, "${appTag}_${reason}_$timestamp.txt")

            val eventType = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "N/A"
            val contentChange = event?.contentChangeTypes ?: 0
            val windowsText = extractTextFromInteractiveWindows(packageName).take(AUTO_DEBUG_MAX_CHARS)
            val nodeOfferText = extractOfferTextFromNodes(packageName).take(AUTO_DEBUG_MAX_CHARS)
            val relevantNodes = buildRelevantNodesSnapshot(packageName, maxNodes = 60)

            // Inventário de janelas (sem filtro de pacote)
            val windowInventory = buildWindowInventory()
            // Dump completo de TODOS os nós (não apenas os "úteis")
            val allNodesSnapshot = buildAllNodesSnapshot(packageName, maxNodes = 100)

            val payload = buildString {
                appendLine("timestamp=$timestamp")
                appendLine("reason=$reason")
                appendLine("package=$packageName")
                appendLine("eventType=$eventType")
                appendLine("contentChangeTypes=$contentChange")
                appendLine()
                appendLine("[eventText]")
                appendLine(eventText.take(AUTO_DEBUG_MAX_CHARS))
                appendLine()
                appendLine("[sourceText]")
                appendLine(sourceText.take(AUTO_DEBUG_MAX_CHARS))
                appendLine()
                appendLine("[nodeOfferText]")
                appendLine(nodeOfferText)
                appendLine()
                appendLine("[windowsText]")
                appendLine(windowsText)
                appendLine()
                appendLine("[relevantNodes]")
                appendLine(relevantNodes)
                appendLine()
                appendLine("[windowInventory]")
                appendLine(windowInventory)
                appendLine()
                appendLine("[allNodes]")
                appendLine(allNodesSnapshot)
            }

            file.writeText(payload)
            Log.i(TAG, "AUTO_DEBUG_DUMP salvo: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar AUTO_DEBUG_DUMP: ${e.message}")
        }
    }

    private fun buildRelevantNodesSnapshot(expectedPackage: String, maxNodes: Int): String {
        return try {
            val allWindows = windows ?: return ""
            val expectedSource = detectAppSource(expectedPackage)
            val lines = mutableListOf<String>()

            fun collect(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 10 || lines.size >= maxNodes) return

                val rid = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val merged = listOf(text, desc).filter { it.isNotBlank() }.joinToString(" | ")

                val looksUseful =
                    merged.isNotBlank() &&
                        (
                            PRICE_PATTERN.containsMatchIn(merged) ||
                                FALLBACK_PRICE_PATTERN.containsMatchIn(merged) ||
                                KM_VALUE_PATTERN.containsMatchIn(merged) ||
                                MIN_VALUE_PATTERN.containsMatchIn(merged) ||
                                MIN_RANGE_PATTERN.containsMatchIn(merged) ||
                                merged.contains("R$", ignoreCase = true) ||
                                merged.contains("km", ignoreCase = true) ||
                                merged.contains("min", ignoreCase = true) ||
                                merged.contains("aceitar", ignoreCase = true) ||
                                merged.contains("corrida", ignoreCase = true) ||
                                merged.contains("99", ignoreCase = true) ||
                                merged.contains("viagem", ignoreCase = true) ||
                                merged.contains("destino", ignoreCase = true)
                        )

                if (looksUseful) {
                    lines.add("d=$depth rid=$rid class=${node.className ?: ""} text=$text desc=$desc")
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    collect(child, depth + 1)
                    try { child?.recycle() } catch (_: Exception) { }
                    if (lines.size >= maxNodes) break
                }
            }

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)
                val shouldUseWindow =
                    rootPkg.isNotBlank() &&
                        rootPkg != OWN_PACKAGE &&
                        (rootPkg == expectedPackage ||
                            rootPkg.startsWith(expectedPackage) ||
                            expectedPackage.startsWith(rootPkg) ||
                            (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource))

                if (shouldUseWindow) {
                    collect(root, 0)
                }

                try { root.recycle() } catch (_: Exception) { }
                if (lines.size >= maxNodes) break
            }

            lines.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Inventário de TODAS as janelas disponíveis (sem filtro de pacote).
     * Mostra qual pacote cada janela pertence, se tem root, etc.
     */
    private fun buildWindowInventory(): String {
        return try {
            val allWindows = windows ?: return "(windows null)"
            val lines = mutableListOf<String>()
            for ((idx, window) in allWindows.withIndex()) {
                val root = try { window.root } catch (_: Exception) { null }
                val rootPkg = root?.packageName?.toString() ?: "(null)"
                val childCount = root?.childCount ?: 0
                val rootText = root?.text?.toString()?.take(50) ?: ""
                val rootDesc = root?.contentDescription?.toString()?.take(50) ?: ""
                val windowType = try { window.type } catch (_: Exception) { -1 }
                val windowLayer = try { window.layer } catch (_: Exception) { -1 }
                lines.add("win[$idx] pkg=$rootPkg type=$windowType layer=$windowLayer children=$childCount text=$rootText desc=$rootDesc")
                try { root?.recycle() } catch (_: Exception) { }
            }
            if (lines.isEmpty()) "(nenhuma janela)"
            else lines.joinToString("\n")
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    /**
     * Snapshot de TODOS os nós com texto (sem filtro semântico).
     * Para diagnóstico quando os nós filtrados retornam vazio.
     */
    private fun buildAllNodesSnapshot(expectedPackage: String, maxNodes: Int): String {
        return try {
            val allWindows = windows ?: return ""
            val lines = mutableListOf<String>()

            fun collect(node: AccessibilityNodeInfo?, depth: Int, windowPkg: String) {
                if (node == null || depth > 12 || lines.size >= maxNodes) return

                val rid = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val cls = node.className?.toString().orEmpty()

                // Capturar QUALQUER nó com texto ou desc (sem filtro)
                if (text.isNotBlank() || desc.isNotBlank()) {
                    lines.add("d=$depth pkg=$windowPkg rid=$rid cls=$cls t=${text.take(80)} cd=${desc.take(80)}")
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    collect(child, depth + 1, windowPkg)
                    try { child?.recycle() } catch (_: Exception) { }
                    if (lines.size >= maxNodes) break
                }
            }

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()

                // Não filtrar por pacote — capturar TODOS para diagnóstico
                if (rootPkg != OWN_PACKAGE) {
                    collect(root, 0, rootPkg)
                }

                try { root.recycle() } catch (_: Exception) { }
                if (lines.size >= maxNodes) break
            }

            if (lines.isEmpty()) "(nenhum nó com texto)"
            else lines.joinToString("\n")
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    /**
     * Estima distância baseada no preço quando não disponível no texto.
     * Usa referência aproximada: R$ 1,50/km
     */
    private fun estimateDistance(price: Double): Double {
        return (price / 1.50).coerceIn(1.0, 50.0)
    }

    /**
     * Estima tempo baseado na distância: ~3 min/km em área urbana.
     */
    private fun estimateTime(distanceKm: Double): Int {
        return (distanceKm * 3).toInt().coerceAtLeast(5)
    }

    /**
     * Tenta extrair endereços de embarque e destino do texto.
     */
    private fun extractAddresses(text: String): Pair<String, String> {
        // 1. Formato OCR universal: "Xmin (Ykm)" — extrair endereços entre os pares
        val routePairs = ROUTE_PAIR_PATTERN.findAll(text).toList()
        if (routePairs.size >= 2) {
            // Endereço pickup: texto entre fim do 1º par e início do 2º par
            val betweenPairs = text.substring(routePairs[0].range.last + 1, routePairs[1].range.first)
            val pickup = betweenPairs.lines()
                .map { it.trim() }
                .filter { it.length > 3 }
                .filterNot { it.contains("distância", ignoreCase = true) || it.contains("distancia", ignoreCase = true) || it.contains("viagem", ignoreCase = true) }
                .firstOrNull() ?: ""

            // Endereço destino: texto após o 2º par
            val afterSecond = text.substring(routePairs[1].range.last + 1)
            val dropoff = afterSecond.lines()
                .map { it.trim() }
                .filter { it.length > 3 }
                .filterNot { it.contains("aceitar", ignoreCase = true) || it.contains("recusar", ignoreCase = true) || it.contains("ignorar", ignoreCase = true) }
                .firstOrNull() ?: ""

            if (pickup.isNotBlank() && dropoff.isNotBlank()) {
                return Pair(pickup, dropoff)
            }
        }

        // 2. Padrões textuais (Uber e genérico)
        val pickupPatterns = listOf(
            Regex("""(?:embarque|buscar|de|retirada)[:\s]+([^,\n]{3,50})""", RegexOption.IGNORE_CASE),
            Regex("""(?:origem)[:\s]+([^,\n]{3,50})""", RegexOption.IGNORE_CASE)
        )
        val dropoffPatterns = listOf(
            Regex("""(?:destino|para|até|entrega)[:\s]+([^,\n]{3,50})""", RegexOption.IGNORE_CASE),
            Regex("""(?:deixar)[:\s]+([^,\n]{3,50})""", RegexOption.IGNORE_CASE)
        )

        var pickup = ""
        var dropoff = ""

        for (pattern in pickupPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                pickup = match.groupValues[1].trim()
                break
            }
        }

        for (pattern in dropoffPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                dropoff = match.groupValues[1].trim()
                break
            }
        }

        return Pair(pickup, dropoff)
    }
}
