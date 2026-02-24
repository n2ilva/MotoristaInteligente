package com.example.motoristainteligente

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.graphics.Rect
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
        // Aceita: 15 min / 8min / 20 minutos / 1-3 nmin (OCR)
        private val TIME_PATTERN = Regex("""(\d{1,3})\s*(?:n?\s*min(?:utos?)?)""", RegexOption.IGNORE_CASE)
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
        private const val NOTIFICATION_COOLDOWN = 1000L     // 1s para notificações
        private const val WINDOW_STATE_COOLDOWN = 1000L     // 1s para nova tela/popup
        private const val WINDOW_CONTENT_COOLDOWN = 800L    // 0.8s para mudança de conteúdo (leitura contínua)

        // Deduplicação por janela de tempo (evita travar em "duplicado eterno")
        private var lastDetectedHash = ""
        private const val DUPLICATE_SUPPRESSION_WINDOW_MS = 4500L

        // Palavras que indicam que o texto é do NOSSO CARD (auto-detecção)
        private val OWN_CARD_MARKERS = listOf(
            "COMPENSA", "NÃO COMPENSA", "NEUTRO",
            "R\$/km", "Ganho/h", "Motorista Inteligente",
            "Score:"
        )
        private val OWN_CARD_NOISE_TOKENS = listOf(
            "r\$/km", "r\$km", "r\$/min", "km total",
            "endereço não disponível", "endereco não disponível",
            "destino não disponível", "destino nao disponível", "destino nao disponivel",
            "motorista inteligente", "compensa", "evitar", "neutro", "score"
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

        // Ex.: 1-11 min / 2-6 min / 1-3 nmin
        private val MIN_RANGE_PATTERN = Regex("""\b\d{1,2}\s*[-–]\s*\d{1,2}\s*(?:n?\s*min(?:utos?)?)\b""", RegexOption.IGNORE_CASE)
        // Ex.: 4 min / 12min / 20 minutos / 3 nmin
        private val MIN_VALUE_PATTERN = Regex("""\b\d{1,3}\s*(?:n?\s*min(?:utos?)?)\b""", RegexOption.IGNORE_CASE)
        // Ex.: 3 km / 2,5 km / 1.2km
        private val KM_VALUE_PATTERN = Regex("""\b\d{1,3}(?:[\.,]\d+)?\s*km\b""", RegexOption.IGNORE_CASE)

        // ========================
        // Padrões de detecção por TEXTO DA TELA (não por pacote)
        // ========================

        // UBER headers:
        //   "UberX - Exclusivo - R$ XX - 4,93 (274) - Verificado"
        //   "UberX - R$ XX - 4,93 (274)"
        //   "UberX - R$ XX - 4,93 (274) - Verificado"
        //   "UberX . Adolescentes - R$ XX - 4,93 (274)"
        private val UBER_CARD_PATTERN = Regex(
            """UberX\s*(?:[.\-]\s*(?:Exclusivo|Adolescentes)\s*)?[.\-]\s*R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        private val UBER_CARD_END_KEYWORDS = listOf("selecionar", "aceitar")

        // Uber body: "Viagem de 1 h 30 (50 km)" — formato com horas para viagens longas
        private val UBER_HOUR_ROUTE_PATTERN = Regex(
            """(\d{1,2})\s*h\s*(\d{1,2})?\s*\(\s*(\d{1,3}(?:[,\.]\d+)?)\s*km\s*\)""",
            RegexOption.IGNORE_CASE
        )

        // 99 headers:
        //   "Corrida Longa - R$ XX - R$ YY - Preço x1,3 - R$1,29 - 4,93 . 789 corridas - CPF e Cartão verif."
        //   "Corrida Longa - Negocia - R$ XX - 4,93 . 789 corridas - Perfil Premium"
        // Grupo 1 = preço da corrida, Grupo 2 = média por km (opcional)
        private val NINETY_NINE_CORRIDA_LONGA_PATTERN = Regex(
            """Corrida\s+Longa\s*(?:-\s*Negocia\s*)?-\s*R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)(?:\s*-\s*R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?))?""",
            RegexOption.IGNORE_CASE
        )
        // 99: "Negocia - R$ XX - 4,83 . 287 corridas Perfil Premium"
        private val NINETY_NINE_NEGOCIA_PATTERN = Regex(
            """Negocia\s*[-\s]+R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        // 99: "Prioritário - Pop Expresso - R$ XX - R$ YY"
        // Grupo 1 = preço da corrida, Grupo 2 = média por km (opcional)
        private val NINETY_NINE_PRIORITARIO_PATTERN = Regex(
            """Priorit[áa]rio\s*-\s*Pop\s+Expresso\s*[-\s]+R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)(?:\s*-\s*R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?))?""",
            RegexOption.IGNORE_CASE
        )
        // 99: "Aceitar por R$ XX" (botão, pode não existir)
        private val NINETY_NINE_ACCEPT_PATTERN = Regex(
            """Aceitar\s+por\s+R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        // Rating do passageiro no header do card
        // Uber: "4,93 (274)" ou "4,93 (274) - Verificado"
        private val UBER_HEADER_RATING_PATTERN = Regex(
            """(\d[,\.]\d{1,2})\s*\(\s*\d+\s*\)""",
            RegexOption.IGNORE_CASE
        )
        // 99: "4,83 . 287 corridas" ou "4,93 . 789 corridas"
        private val NINETY_NINE_HEADER_RATING_PATTERN = Regex(
            """(\d[,\.]\d{1,2})\s*[.·]\s*\d+\s*corridas?""",
            RegexOption.IGNORE_CASE
        )

        // Preço mínimo para considerar como corrida real
        private const val MIN_RIDE_PRICE = 3.0
        private const val DEBUG_TEXT_SAMPLE_MAX = 220
        private const val DEBUG_COOLDOWN_LOG_INTERVAL = 2000L
        private const val EVENT_LOG_THROTTLE_MS = 1200L
        private const val DIAGNOSTIC_LOG_THROTTLE_MS = 10000L
        private const val LIMITED_DATA_ALERT_COOLDOWN_MS = 20000L
        private const val AUTO_DEBUG_DUMP_ENABLED = true
        private const val AUTO_DEBUG_DUMP_INTERVAL_MS = 3000L
        private const val AUTO_DEBUG_MAX_CHARS = 5000
        private const val OCR_FALLBACK_ENABLED = true
        private const val OCR_FALLBACK_MIN_INTERVAL_MS = 1200L  // 1.2s entre tentativas OCR

        // Filtro: ignorar nós de acessibilidade no topo da tela (earnings card Uber)
        private const val TOP_SCREEN_FILTER_FRACTION = 0.15  // 15% superior da tela

        // Dedup persistente para node-price-only: mesmo preço repetido = earnings card, não corrida
        private const val NODE_PRICE_ONLY_MAX_REPEATS = 2
        private const val NODE_PRICE_ONLY_DEDUP_WINDOW_MS = 60_000L  // 60s
        private const val NODE_PRICE_ONLY_SUPPRESSION_HOLD_MS = 45_000L
        private const val NODE_PRICE_ONLY_SUPPRESSION_LOG_THROTTLE_MS = 10_000L

        // Debounce: reduzir latência sem perder estabilidade
        private const val DEBOUNCE_DELAY = 250L // 250ms

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
    // Dedup persistente para node-price-only (earnings card Uber)
    private var lastNodePriceOnlyValue = 0.0
    private var nodePriceOnlyRepeatCount = 0
    private var lastNodePriceOnlyAt = 0L
    private var suppressedNodePriceOnlyValue = Double.NaN
    private var suppressedNodePriceOnlyPackage: String? = null
    private var suppressedNodePriceOnlyUntil = 0L
    private var lastNodePriceOnlySuppressionLogAt = 0L
    // Removido: supressão de leitura por estado idle/desconectado
    // O app agora lê a tela continuamente quando ativo, pronto para capturar corridas a qualquer momento

    // Healthcheck do FloatingAnalyticsService
    private var healthCheckRunnable: Runnable? = null
    private val HEALTH_CHECK_INTERVAL_MS = 30_000L  // Verificar a cada 30s

    // OCR agressivo quando árvore vazia para apps de corrida
    private var lastEmptyTreeOcrAt = 0L
    private val EMPTY_TREE_OCR_COOLDOWN_MS = 700L  // Mais responsivo para corridas consecutivas
    private var ocrRetryPending = false
    private var pendingOcrRetryRunnable: Runnable? = null

    // ========================
    // Detecção de Aceitação de Corrida
    // ========================
    // Após uma oferta ser detectada, monitorar por sinais de aceitação
    // (tela mudou para "a caminho", "corrida aceita", etc.)
    private var lastOfferAppSource: AppSource? = null
    private var lastOfferTimestamp = 0L
    private var lastOfferAlreadyAccepted = false
    private val ACCEPTANCE_DETECTION_WINDOW_MS = 30_000L  // Janela de 30s pós-oferta

    // Padrões que indicam que o motorista ACEITOU a corrida
    // Aparecem quando a tela muda do popup de oferta para o modo "em corrida"
    private val ACCEPTANCE_SIGNALS = listOf(
        // Português
        "a caminho", "indo buscar", "navegando", "em andamento",
        "corrida aceita", "viagem aceita", "aceita com sucesso",
        "ir até o passageiro", "buscar passageiro",
        "iniciar viagem", "iniciar corrida",
        "chegar ao passageiro", "chegando",
        "rota iniciada", "navegação iniciada",
        "iniciar navegação", "navegar",
        "dirigir até", "ir ao embarque",
        // Inglês (Uber)
        "heading to", "navigating to", "navigate to",
        "trip accepted", "ride accepted",
        "start trip", "start navigation",
        "picking up", "on the way",
        "arriving", "drive to pickup"
    )

    // Sinais de que a oferta foi RECUSADA / expirou (para limpar o estado)
    private val REJECTION_SIGNALS = listOf(
        "corrida perdida", "oferta expirou", "tempo esgotado",
        "trip missed", "offer expired", "timed out",
        "próxima corrida", "next trip",
        "você está online", "procurando viagens",
        "procurando corridas", "corrida cancelada",
        "viagem cancelada", "trip cancelled", "ride cancelled"
    )

    // Padrões de texto de botão/view que indicam aceitação por CLIQUE
    // Uber: botão "Aceitar" / "Accept"
    private val UBER_ACCEPT_CLICK_PATTERNS = listOf(
        "aceitar", "accept", "confirmar viagem", "confirm trip"
    )
    // 99: clique no card de informações da corrida (textos que aparecem no card clicável)
    private val NINETY_NINE_CARD_CLICK_PATTERNS = listOf(
        "aceitar", "accept",
        // O card da 99 mostra preço — se clicou num elemento com R$ durante oferta, é aceite
        "r\\$", "reais"
    )

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
        Log.i(TAG, "Modo: leitura de tela inteira (detecção por padrões de texto)")
        Log.i(TAG, "FloatingAnalyticsService ativo: ${FloatingAnalyticsService.instance != null}")
        // Iniciar healthcheck periódico do FloatingAnalyticsService
        startFloatingServiceHealthCheck()
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
     * Detecta a fonte do app (Uber ou 99) analisando o CONTEÚDO DO TEXTO na tela,
     * sem depender do nome do pacote. Usa os padrões específicos de cada app.
     *
     * Uber: "UberX - Exclusivo - R$ XX" / "UberX - R$ XX" / "UberX . Adolescentes - R$ XX"
     * 99: "Corrida Longa - R$ XX" / "Negocia - R$ XX" / "Prioritário - Pop Expresso - R$ XX"
     */
    private fun detectAppSourceFromScreenText(text: String): AppSource {
        // Verificar padrões da Uber
        if (UBER_CARD_PATTERN.containsMatchIn(text)) {
            return AppSource.UBER
        }

        // Verificar padrões da 99
        if (NINETY_NINE_CORRIDA_LONGA_PATTERN.containsMatchIn(text) ||
            NINETY_NINE_NEGOCIA_PATTERN.containsMatchIn(text) ||
            NINETY_NINE_PRIORITARIO_PATTERN.containsMatchIn(text)) {
            return AppSource.NINETY_NINE
        }

        return AppSource.UNKNOWN
    }

    /**
     * Verifica se o texto da tela contém um card de corrida reconhecível (Uber ou 99).
     */
    private fun isRecognizedRideCard(text: String): Boolean {
        return detectAppSourceFromScreenText(text) != AppSource.UNKNOWN
    }

    /**
     * Extrai texto de TODAS as janelas visíveis na tela, sem filtrar por pacote.
     * Usado para ler a tela inteira do celular.
     */
    private fun extractAllScreenText(): String {
        return try {
            val sb = StringBuilder()
            val allWindows = windows ?: return ""

            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val rootPkg = root.packageName?.toString().orEmpty()

                // Ignorar apenas o nosso próprio app
                if (rootPkg == OWN_PACKAGE || rootPkg.startsWith(OWN_PACKAGE)) {
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

    /**
     * Detecta se o app usa renderização customizada que resulta em árvore de acessibilidade VAZIA.
     * App 99 (com.app99.driver) usa Compose/Canvas sem labels → nenhum texto nos nós.
     * App Uber (com.ubercab.driver) pode usar React Native que também resulta em árvore vazia.
     * Para esses apps, OCR é a ÚNICA via funcional de extração.
     */
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

        // Não filtrar por pacote — ler a tela inteira e detectar Uber/99 pelo conteúdo

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

        // ========== DETECÇÃO DE ACEITAÇÃO ==========
        // Verificar sinais de aceitação ANTES do cooldown (não pode perder esses eventos)
        if (lastOfferAppSource != null && !lastOfferAlreadyAccepted) {
            val timeSinceOffer = now - lastOfferTimestamp
            if (timeSinceOffer <= ACCEPTANCE_DETECTION_WINDOW_MS) {
                // === DETECÇÃO POR CLIQUE (TYPE_VIEW_CLICKED) ===
                if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    handleAcceptanceClick(event, packageName, now)
                }

                // === DETECÇÃO POR MUDANÇA DE TELA (sinais textuais) ===
                val quickText = event.text?.joinToString(" ")?.trim().orEmpty()
                if (quickText.isNotBlank()) {
                    checkForAcceptanceSignal(quickText, packageName, now)
                }
            } else {
                // Janela expirou — oferta provavelmente foi ignorada/expirou
                if (shouldLogDiagnostic("offer-expired|$packageName")) {
                    Log.d(TAG, "Oferta expirada sem aceitação detectada (${timeSinceOffer}ms)")
                }
                clearOfferState()
            }
        }

        // TYPE_VIEW_CLICKED não precisa de mais processamento além da aceitação
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) return

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
                handleWindowChange(event, packageName, isStateChange = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Sempre processar eventos de conteúdo — leitura contínua da tela
                // O app precisa estar pronto para capturar corridas Uber/99 a qualquer momento
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
        healthCheckRunnable?.let { debounceHandler.removeCallbacks(it) }
        healthCheckRunnable = null
        pendingRideData = null
        pendingRunnable = null
        Log.w(TAG, "Serviço de acessibilidade destruído")
    }

    /**
     * Verificação periódica se o FloatingAnalyticsService está vivo.
     * Se ele morreu, tenta reiniciar automaticamente.
     */
    private fun startFloatingServiceHealthCheck() {
        healthCheckRunnable?.let { debounceHandler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                if (!isServiceConnected) return

                val instance = FloatingAnalyticsService.instance
                if (instance == null) {
                    Log.w(TAG, "HEALTHCHECK: FloatingAnalyticsService morto — reiniciando")
                    ensureFloatingServiceRunning()
                }
                debounceHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        healthCheckRunnable = runnable
        debounceHandler.postDelayed(runnable, HEALTH_CHECK_INTERVAL_MS)
        Log.d(TAG, "Healthcheck do FloatingAnalyticsService iniciado (intervalo: ${HEALTH_CHECK_INTERVAL_MS}ms)")
    }

    // ========================
    // Detecção de Aceitação — Métodos
    // ========================

    /**
     * Verifica se o texto na tela contém sinais de aceitação ou rejeição de corrida.
     * Chamado durante a janela de detecção após uma oferta ser mostrada.
     */
    private fun checkForAcceptanceSignal(text: String, packageName: String, timestamp: Long) {
        if (lastOfferAlreadyAccepted) return
        val lower = text.lowercase()

        // Verificar sinais de REJEIÇÃO primeiro (expirou, perdida, etc.)
        val rejectionMatch = REJECTION_SIGNALS.any { lower.contains(it) }
        if (rejectionMatch) {
            Log.d(TAG, "✗ Sinal de REJEIÇÃO detectado: '$text' → oferta descartada")
            clearOfferState()
            return
        }

        // Verificar sinais de ACEITAÇÃO
        val acceptanceMatch = ACCEPTANCE_SIGNALS.any { lower.contains(it) }
        if (acceptanceMatch) {
            markOfferAsAccepted("TELA", text)
        }
    }

    /**
     * Trata TYPE_VIEW_CLICKED durante a janela de aceitação.
     * Uber: detecta clique no botão "Aceitar" / "Accept"
     * 99: detecta clique no card de informações da corrida
     */
    private fun handleAcceptanceClick(event: AccessibilityEvent, packageName: String, timestamp: Long) {
        if (lastOfferAlreadyAccepted) return
        val appSource = lastOfferAppSource ?: return

        // Texto do elemento clicado
        val clickedText = buildString {
            event.text?.joinToString(" ")?.let { append(it) }
            event.contentDescription?.let {
                if (isNotBlank()) append(" ")
                append(it)
            }
        }.trim().lowercase()

        if (clickedText.isBlank()) {
            // Tentar extrair texto do source node
            val sourceText = try {
                event.source?.text?.toString()?.lowercase().orEmpty()
            } catch (_: Exception) { "" }
            if (sourceText.isBlank()) return
            checkClickTextForAcceptance(sourceText, appSource, packageName)
            return
        }

        checkClickTextForAcceptance(clickedText, appSource, packageName)
    }

    /**
     * Verifica se o texto de um clique corresponde a um padrão de aceitação.
     */
    private fun checkClickTextForAcceptance(clickedText: String, appSource: AppSource, packageName: String) {
        val patterns = when (appSource) {
            AppSource.UBER -> UBER_ACCEPT_CLICK_PATTERNS
            AppSource.NINETY_NINE -> NINETY_NINE_CARD_CLICK_PATTERNS
            else -> return
        }

        val matched = patterns.any { pattern ->
            if (pattern.contains("\\")) {
                // Tratar como regex simples
                try { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(clickedText) }
                catch (_: Exception) { false }
            } else {
                clickedText.contains(pattern)
            }
        }

        if (matched) {
            Log.d(TAG, "✓ Clique de ACEITAÇÃO detectado em ${appSource.displayName}: '$clickedText'")
            markOfferAsAccepted("CLIQUE", clickedText)
        } else {
            Log.d(TAG, "Clique ignorado (sem padrão de aceitação): '$clickedText' em ${appSource.displayName}")
        }
    }

    /**
     * Marca a oferta pendente como aceita e notifica os serviços.
     */
    private fun markOfferAsAccepted(detectionMethod: String, signalText: String) {
        val appSource = lastOfferAppSource ?: return
        lastOfferAlreadyAccepted = true

        // Marcar no DemandTracker
        val marked = DemandTracker.markLastOfferAsAccepted(appSource)
        Log.i(TAG, "=========================================")
        Log.i(TAG, "✓ CORRIDA ACEITA DETECTADA!")
        Log.i(TAG, "  Método: $detectionMethod")
        Log.i(TAG, "  App: ${appSource.displayName}")
        Log.i(TAG, "  Sinal: '${signalText.take(80)}'")
        Log.i(TAG, "  Marcada no tracker: $marked")
        Log.i(TAG, "=========================================")

        // Notificar FloatingAnalyticsService para atualizar o status card
        FloatingAnalyticsService.instance?.onRideAccepted(appSource)
    }

    /**
     * Limpa o estado de rastreamento de oferta pendente.
     */
    private fun clearOfferState() {
        lastOfferAppSource = null
        lastOfferTimestamp = 0L
        lastOfferAlreadyAccepted = false
    }

    /**
     * Registra que uma nova oferta foi mostrada, iniciando a janela de detecção de aceitação.
     */
    private fun registerOfferForAcceptanceTracking(appSource: AppSource) {
        lastOfferAppSource = appSource
        lastOfferTimestamp = System.currentTimeMillis()
        lastOfferAlreadyAccepted = false
        Log.d(TAG, "Oferta registrada para rastreamento de aceitação: ${appSource.displayName}")
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

        // Notificação da Uber do tipo "Nova viagem", "Novo pedido", "trip request" etc.
        // → sinal de que há corrida disponível, mas o texto da notif pode não ter dados completos
        // → disparar OCR se a notificação não tem preço+km+min
        val isUberRideSignal = detectAppSource(packageName) == AppSource.UBER &&
            (text.contains("viagem", ignoreCase = true) ||
             text.contains("corrida", ignoreCase = true) ||
             text.contains("pedido", ignoreCase = true) ||
             text.contains("trip", ignoreCase = true) ||
             text.contains("request", ignoreCase = true) ||
             text.contains("ride", ignoreCase = true) ||
             text.contains("delivery", ignoreCase = true) ||
             text.contains("entrega", ignoreCase = true))

        if (isUberRideSignal && !isLikelyRideOffer(text, isStateChange = true)) {
            Log.i(TAG, "=== NOTIFICAÇÃO UBER com sinal de corrida (sem dados) → OCR ===")
            Log.i(TAG, "Texto: ${text.take(300)}")
            requestOcrFallbackForOffer(packageName, "uber-notif-ride-signal")
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
            // Sem root, tentar ler toda a tela
            val allScreenText = extractAllScreenText()
            val textToCheck = if (allScreenText.isNotBlank()) allScreenText
                else if (eventText.isNotBlank()) eventText
                else ""

            if (textToCheck.isNotBlank() && isRecognizedRideCard(textToCheck)) {
                Log.d(TAG, "rootInActiveWindow nulo, usando tela inteira (${textToCheck.length} chars)")
                tryParseRideData(
                    text = textToCheck,
                    packageName = packageName,
                    isNotification = false,
                    extractionSource = "all-screen"
                )
            } else if (textToCheck.isBlank()) {
                Log.d(TAG, "rootInActiveWindow nulo e tela vazia")
            }
            return
        }

        // Ignorar se rootInActiveWindow é do nosso próprio overlay
        val rootPackage = rootNode.packageName?.toString() ?: ""
        if (rootPackage == OWN_PACKAGE || rootPackage.startsWith(OWN_PACKAGE)) {
            try { rootNode.recycle() } catch (_: Exception) { }
            Log.d(TAG, "Ignorado: rootInActiveWindow pertence ao próprio app")
            return
        }

        // Extrair texto de TODA a tela (todas as janelas), não só do app do evento
        val allText = extractAllText(rootNode)
        try { rootNode.recycle() } catch (_: Exception) { }

        // Complementar com texto de todas as janelas visíveis
        val screenText = extractAllScreenText()

        // ===== DETECÇÃO DE ÁRVORE VAZIA PARA APP DE CORRIDA =====
        // Se o evento vem de Uber/99, mas a árvore de acessibilidade está vazia
        // (Canvas/React Native sem labels) → OCR agressivo como única saída
        val isRideApp = detectAppSource(packageName) != AppSource.UNKNOWN
        val treeIsEmpty = allText.isBlank() && screenText.let { txt ->
            // Se o screenText só contém texto do SystemUI (status bar), considerar vazio
            !PRICE_PATTERN.containsMatchIn(txt) &&
            !FALLBACK_PRICE_PATTERN.containsMatchIn(txt) &&
            !KM_VALUE_PATTERN.containsMatchIn(txt)
        }

        if (isRideApp && treeIsEmpty) {
            val now = System.currentTimeMillis()

            if (now - lastEmptyTreeOcrAt >= EMPTY_TREE_OCR_COOLDOWN_MS) {
                lastEmptyTreeOcrAt = now
                Log.i(TAG, "Árvore vazia para ${packageName} — OCR agressivo (cooldown ${EMPTY_TREE_OCR_COOLDOWN_MS}ms)")
                requestOcrFallbackForOffer(packageName, "empty-tree-ride-app")

                // Agendar retry do OCR após 1.5s se o primeiro não encontrou corrida
                if (!ocrRetryPending) {
                    ocrRetryPending = true
                    pendingOcrRetryRunnable = Runnable {
                        ocrRetryPending = false
                        val retryNow = System.currentTimeMillis()
                        if (retryNow - lastEmptyTreeOcrAt >= EMPTY_TREE_OCR_COOLDOWN_MS) {
                            lastEmptyTreeOcrAt = retryNow
                            Log.d(TAG, "OCR retry para ${packageName} (árvore vazia)")
                            requestOcrFallbackForOffer(packageName, "empty-tree-retry")
                        }
                    }
                    debounceHandler.postDelayed(pendingOcrRetryRunnable!!, EMPTY_TREE_OCR_COOLDOWN_MS)
                }
            }
            return  // Não processar texto do status bar como candidato
        }

        val combinedText = buildString {
            if (eventText.isNotBlank()) append(eventText).append(' ')
            if (allText.isNotBlank()) append(allText).append(' ')
            if (screenText.isNotBlank()) append(screenText)
        }.trim()

        val (finalText, extractionSource) = if (combinedText.isBlank()) {
            val sourceFallback = extractTextFromEventSource(event)
            if (sourceFallback.isNotBlank()) {
                Log.d(TAG, "Fallback event.source usado (${sourceFallback.length} chars)")
                sourceFallback to "event-source"
            } else {
                // Texto vazio em todos os fallbacks — OCR para qualquer app
                if (requestOcrFallbackForOffer(packageName, "empty-text-all-sources")) {
                    Log.d(TAG, "Texto vazio em todas as fontes → OCR disparado")
                } else {
                    Log.d(TAG, "Ignorado: texto vazio + OCR em cooldown")
                }
                return
            }
        } else {
            combinedText to "all-screen"
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
        val sanitizedText = sanitizeTextForRideParsing(finalText)

        if (sanitizedText.isBlank()) {
            Log.d(TAG, "Ignorado: texto final vazio")
            return
        }

        // Muitos eventos de Uber/99 trazem apenas IDs de layout (sem conteúdo útil de corrida).
        // Ex.: com.ubercab.driver:id/rootView ...
        if (looksLikeStructuralIdOnlyText(sanitizedText)) {
            return
        }

        // Verificar se é texto do nosso próprio card (loop de auto-detecção)
        if (isOwnCardText(sanitizedText)) {
            return
        }

        // ===== FAST PATH: Verificar se o texto contém um card de corrida reconhecível =====
        // Se o texto bater nos padrões específicos de Uber ou 99, processar direto
        val detectedFromText = detectAppSourceFromScreenText(sanitizedText)
        if (detectedFromText != AppSource.UNKNOWN) {
            Log.i(TAG, "=== CARD DE CORRIDA DETECTADO: ${detectedFromText.displayName} ===")
            Log.i(TAG, "Texto (300 chars): ${sanitizedText.take(300)}")
            tryParseRideData(
                text = sanitizedText,
                packageName = packageName,
                isNotification = false,
                extractionSource = normalizeExtractionSource(rootPackage)
            )
            return
        }

        // ===== Se não bateu nos padrões específicos, usar detecção genérica =====
        val hasPrice = PRICE_PATTERN.containsMatchIn(sanitizedText) || FALLBACK_PRICE_PATTERN.containsMatchIn(sanitizedText)
        val hasKmToken = KM_VALUE_PATTERN.containsMatchIn(sanitizedText)
        val hasMinToken = MIN_VALUE_PATTERN.containsMatchIn(sanitizedText) || MIN_RANGE_PATTERN.containsMatchIn(sanitizedText)

        if (!(hasPrice && hasKmToken && hasMinToken)) {
            // Fallback via keyword-search e node-search NÃO se aplica quando fonte é OCR
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

                    // NOVO: Se o nó tem PREÇO mas falta km/min, emitir com dados estimados
                    // Isso é comum no Uber/99 cuja árvore de acessibilidade expõe só o preço
                    if (hasPriceInNode) {
                        val priceMatch = PRICE_PATTERN.find(nodeOfferFallback)
                            ?: FALLBACK_PRICE_PATTERN.find(nodeOfferFallback)
                        if (priceMatch != null) {
                            val priceStr = priceMatch.groupValues[1].replace(",", ".")
                            val price = priceStr.toDoubleOrNull()
                            if (price != null && price >= MIN_RIDE_PRICE) {
                                // === DEDUP PERSISTENTE: earnings card Uber repete o mesmo preço continuamente ===
                                val nowDedup = System.currentTimeMillis()
                                val isSuppressedPrice =
                                    packageName == suppressedNodePriceOnlyPackage &&
                                        nowDedup < suppressedNodePriceOnlyUntil &&
                                        kotlin.math.abs(price - suppressedNodePriceOnlyValue) < 0.01
                                if (isSuppressedPrice) {
                                    return
                                }

                                if (price == lastNodePriceOnlyValue && nowDedup - lastNodePriceOnlyAt < NODE_PRICE_ONLY_DEDUP_WINDOW_MS) {
                                    nodePriceOnlyRepeatCount++
                                } else {
                                    // Preço diferente ou janela expirou — resetar
                                    lastNodePriceOnlyValue = price
                                    nodePriceOnlyRepeatCount = 1
                                }
                                lastNodePriceOnlyAt = nowDedup

                                if (nodePriceOnlyRepeatCount > NODE_PRICE_ONLY_MAX_REPEATS) {
                                    suppressedNodePriceOnlyValue = price
                                    suppressedNodePriceOnlyPackage = packageName
                                    suppressedNodePriceOnlyUntil = nowDedup + NODE_PRICE_ONLY_SUPPRESSION_HOLD_MS

                                    if (nowDedup - lastNodePriceOnlySuppressionLogAt >= NODE_PRICE_ONLY_SUPPRESSION_LOG_THROTTLE_MS) {
                                        Log.w(
                                            TAG,
                                            "node-price-only SUPRIMIDO: R$ $price repetiu $nodePriceOnlyRepeatCount vezes (quarentena ${NODE_PRICE_ONLY_SUPPRESSION_HOLD_MS}ms, provável earnings card)"
                                        )
                                        lastNodePriceOnlySuppressionLogAt = nowDedup
                                    }
                                    return
                                }

                                val detectedApp = detectAppSourceFromScreenText(nodeOfferFallback)
                                    .takeIf { it != AppSource.UNKNOWN }
                                    ?: detectAppSource(packageName)
                                if (detectedApp != AppSource.UNKNOWN) {
                                    Log.i(TAG, ">>> Emitindo corrida com preço do nó (R$ $price), km/min serão estimados [$packageName]")
                                    buildAndEmitRideData(
                                        appSource = detectedApp,
                                        packageName = packageName,
                                        price = price,
                                        rideDistanceKm = null, // será estimado
                                        rideTimeMin = null,     // será estimado
                                        pickupDistanceKm = null,
                                        pickupTimeMin = null,
                                        userRating = null,
                                        extractionSource = "node-price-only",
                                        isNotification = false,
                                        text = nodeOfferFallback
                                    )
                                    // Ainda disparar OCR para tentar complementar na próxima detecção
                                    if (allowOcrFallback) {
                                        requestOcrFallbackForOffer(packageName, "node-price-only-complement")
                                    }
                                    return
                                }
                            }
                        }
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
                eventText = sanitizedText,
                sourceText = ""
            )
            return
        }

        if (!isLikelyRideOffer(sanitizedText, isStateChange)) {
            val key = "low-confidence|$packageName|${if (isStateChange) "STATE" else "CONTENT"}"
            if (shouldLogDiagnostic(key)) {
                Log.d(TAG, "Ignorado por baixa confiança (${if (isStateChange) "STATE" else "CONTENT"}): ${sanitizedText.take(DEBUG_TEXT_SAMPLE_MAX)}")
            }
            return
        }

        val indicatorCount = RIDE_INDICATORS.count { sanitizedText.contains(it, ignoreCase = true) }

        Log.i(TAG, "=== ${if (isStateChange) "STATE" else "CONTENT"}_CHANGED de $packageName ===")
        Log.i(TAG, "Indicadores encontrados: $indicatorCount, Pacote da janela: $rootPackage, source=${normalizeExtractionSource(rootPackage)}")
        Log.i(TAG, "Texto (300 chars): ${sanitizedText.take(300)}")
        tryParseRideData(
            text = sanitizedText,
            packageName = packageName,
            isNotification = false,
            extractionSource = normalizeExtractionSource(rootPackage)
        )
    }

    private fun normalizeExtractionSource(rawSource: String): String {
        return when (rawSource) {
            "notification" -> "notification"
            "event-text" -> "event-text"
            "all-screen" -> "all-screen"
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
        // === FAST PATH: Se bater nos padrões específicos de Uber ou 99, ACEITAR direto ===
        if (isRecognizedRideCard(text)) return true

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

                // Obter altura da tela para filtrar nós no topo (earnings card)
                val screenHeight = resources.displayMetrics.heightPixels
                val topThreshold = (screenHeight * TOP_SCREEN_FILTER_FRACTION).toInt()

                val queries = listOf("R$", "km", "min")
                for (query in queries) {
                    val nodes = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null } ?: continue
                    for (node in nodes) {
                        // Filtrar nós no topo da tela (earnings card Uber mostra ganhos acumulados)
                        val nodeBounds = Rect()
                        node.getBoundsInScreen(nodeBounds)
                        if (query == "R$" && nodeBounds.top < topThreshold && nodeBounds.bottom < topThreshold) {
                            Log.d(TAG, "Node R$ ignorado por estar no topo da tela (top=${nodeBounds.top}, threshold=$topThreshold): ${node.text}")
                            try { node.recycle() } catch (_: Exception) { }
                            continue
                        }

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

        // Se bater nos padrões específicos de Uber ou 99, é sinal forte
        if (isRecognizedRideCard(text)) return true

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

    private fun sanitizeTextForRideParsing(text: String): String {
        if (text.isBlank()) return ""

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val cleanedLines = lines.filterNot { line ->
            val lower = line.lowercase()
            OWN_CARD_NOISE_TOKENS.any { lower.contains(it) }
        }

        val cleaned = if (cleanedLines.isNotEmpty()) cleanedLines.joinToString("\n") else text
        return cleaned.replace(Regex("\\s+"), " ").trim()
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

    /**
     * Extrai o preço diretamente dos padrões de card específicos de cada app.
     *
     * Uber: "UberX - Exclusivo - R$ XX" / "UberX - R$ XX" / "UberX . Adolescentes - R$ XX"
     * 99: "Corrida Longa - R$ XX" / "Negocia - R$ XX" / "Prioritário - Pop Expresso - R$ XX"
     */
    private fun parseCardPrice(text: String, appSource: AppSource): Double? {
        return when (appSource) {
            AppSource.UBER -> {
                val match = UBER_CARD_PATTERN.find(text)
                val strict = match?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                if (strict != null && strict >= MIN_RIDE_PRICE) return strict

                // OCR da Uber às vezes remove os hifens do header.
                // Ex.: "UberX Exclusivo ... R$ 5,84 Verificado"
                val fallbackUber = Regex(
                    """UberX[\s\S]{0,120}?R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
                    RegexOption.IGNORE_CASE
                ).find(text)
                fallbackUber?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            }
            AppSource.NINETY_NINE -> {
                // Tentar "Corrida Longa [-Negocia] - R$ XX [- R$ YY]" (XX é o preço da corrida)
                val corridaLonga = NINETY_NINE_CORRIDA_LONGA_PATTERN.find(text)
                if (corridaLonga != null) {
                    return corridaLonga.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                }
                // Tentar "Negocia - R$ XX"
                val negocia = NINETY_NINE_NEGOCIA_PATTERN.find(text)
                if (negocia != null) {
                    return negocia.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                }
                // Tentar "Prioritário - Pop Expresso - R$ XX [- R$ YY]"
                val prioritario = NINETY_NINE_PRIORITARIO_PATTERN.find(text)
                if (prioritario != null) {
                    return prioritario.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                }
                // Tentar "Aceitar por R$ XX"
                val aceitar = NINETY_NINE_ACCEPT_PATTERN.find(text)
                aceitar?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            }
            else -> null
        }
    }

    /**
     * Para a 99, extrai a média por km quando disponível.
     * Fontes:
     *   "Corrida Longa - R$ XX - R$ YY" (YY = média por km)
     *   "Prioritário - Pop Expresso - R$ XX - R$ YY" (YY = média por km)
     */
    private fun parse99AvgPerKm(text: String): Double? {
        // Primeiro tenta Corrida Longa
        val corridaLonga = NINETY_NINE_CORRIDA_LONGA_PATTERN.find(text)
        if (corridaLonga != null) {
            val avg = corridaLonga.groupValues.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()
            if (avg != null) return avg
        }
        // Depois tenta Prioritário - Pop Expresso
        val prioritario = NINETY_NINE_PRIORITARIO_PATTERN.find(text)
        if (prioritario != null) {
            val avg = prioritario.groupValues.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()
            if (avg != null) return avg
        }
        return null
    }

    /**
     * Extrai o rating do passageiro do header do card.
     * Uber: "4,93 (274)" — rating seguido de (número de corridas)
     * 99: "4,83 . 287 corridas" — rating seguido de ". NNN corridas"
     */
    private fun parseHeaderRating(text: String, appSource: AppSource): Double? {
        val pattern = when (appSource) {
            AppSource.UBER -> UBER_HEADER_RATING_PATTERN
            AppSource.NINETY_NINE -> NINETY_NINE_HEADER_RATING_PATTERN
            else -> return null
        }
        val match = pattern.find(text) ?: return null
        val rating = match.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        return rating.takeIf { it in 1.0..5.0 }
    }

    private fun tryParseRideData(
        text: String,
        packageName: String,
        isNotification: Boolean,
        extractionSource: String
    ) {
        // Detectar app pela CONTEÚDO do texto (não pelo pacote)
        val textAppSource = detectAppSourceFromScreenText(text)
        // Fallback: se não detectou pelo texto, tentar pelo pacote
        val appSource = if (textAppSource != AppSource.UNKNOWN) textAppSource else detectAppSource(packageName)

        // Se não reconheceu como Uber nem 99, ignorar
        if (appSource == AppSource.UNKNOWN) {
            Log.d(TAG, "Texto não reconhecido como Uber ou 99 — ignorando")
            return
        }

        // ========== PASSO 0: Extração por padrões específicos de card Uber/99 ==========
        val cardPrice = parseCardPrice(text, appSource)
        if (cardPrice != null) {
            Log.i(TAG, ">>> Preço extraído do card ${appSource.displayName}: R$ $cardPrice")
        }

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

        // ========== PASSO 2: route pairs "Xmin (Ykm)" (99 e Uber) ==========
        // Ambos os apps mostram pickup e corrida como pares "min (km)".
        // Mesmo quando source != "ocr", o texto combinado pode conter esse padrão.
        val ocrParsed = parseOcrRoutePairs(text)

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
        // Se já temos o preço do card específico, usar como prioridade
        if (cardPrice != null && cardPrice >= MIN_RIDE_PRICE) {
            val pricePosition = PRICE_PATTERN.find(text)?.range?.first ?: 0
            val disambiguated = disambiguateByPosition(text, pricePosition)

            val distance = ocrParsed?.rideDistanceKm
                ?: disambiguated.rideDistanceKm
                ?: parseRideDistanceFromText(text, pricePosition)
                ?: estimateDistance(cardPrice)

            val time = ocrParsed?.rideTimeMin
                ?: disambiguated.rideTimeMin
                ?: parseRideTimeFromText(text, pricePosition)
                ?: estimateTime(distance)

            val pickupKm = ocrParsed?.pickupDistanceKm ?: disambiguated.pickupDistanceKm
            val pickupMin = ocrParsed?.pickupTimeMin ?: disambiguated.pickupTimeMin

            // Extrair rating do header do card (Uber: "4,93 (274)" / 99: "4,83 . 287 corridas")
            val headerRating = parseHeaderRating(text, appSource)

            Log.i(TAG, ">>> Card price parsing: preço=R$ $cardPrice, rideKm=$distance, rideMin=$time, pickupKm=${pickupKm ?: "?"}, pickupMin=${pickupMin ?: "?"}, rating=${headerRating ?: "?"}")

            buildAndEmitRideData(
                appSource = appSource,
                packageName = packageName,
                price = cardPrice,
                rideDistanceKm = distance,
                rideTimeMin = time,
                pickupDistanceKm = pickupKm,
                pickupTimeMin = pickupMin,
                userRating = headerRating,
                extractionSource = "${appSource.displayName.lowercase()}-card-pattern",
                isNotification = isNotification,
                text = text
            )
            return
        }

        // Se não tiver preço do card, continuar com regex genérico
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

        // Extrair endereços antes da validação
        val addresses = extractAddresses(text)

        // ===== VALIDAÇÃO: preço obrigatório, endereços opcionais =====
        if (price <= 0) {
            Log.d(TAG, "Corrida ignorada: preço inválido (R$ $price)")
            return
        }
        // Endereços são OPCIONAIS — muitas corridas válidas não expõem endereços
        // na árvore de acessibilidade (Uber usa React Native, 99 usa Canvas/Compose)
        if (addresses.first.isBlank() || addresses.second.isBlank()) {
            Log.d(TAG, "Endereço(s) não encontrado(s) (pickup='${addresses.first}', dropoff='${addresses.second}') — prosseguindo sem endereço")
        }

        // DEDUPLICAÇÃO
        val contentHash = "${appSource}_${price}_${extractionSource}_${distance}_${time}"
        val now = System.currentTimeMillis()
        if (contentHash == lastDetectedHash && now - lastDetectedTime < DUPLICATE_SUPPRESSION_WINDOW_MS) {
            Log.d(TAG, "Duplicado ignorado (${now - lastDetectedTime}ms): R$ $price")
            return
        }
        lastDetectedHash = contentHash

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
        pendingOcrRetryRunnable?.let { debounceHandler.removeCallbacks(it) }
        pendingOcrRetryRunnable = null
        ocrRetryPending = false

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

        // Registrar oferta para rastreamento de aceitação
        registerOfferForAcceptanceTracking(appSource)
    }

    private fun queueRideDataForFloatingService(rideData: RideData, successMessage: String) {
        pendingRideData = rideData
        pendingRunnable?.let { debounceHandler.removeCallbacks(it) }

        val runnable = Runnable {
            val data = pendingRideData ?: return@Runnable

            val service = FloatingAnalyticsService.instance
            if (service != null) {
                pendingRideData = null
                service.onRideDetected(data)
                Log.i(TAG, ">>> $successMessage (após debounce)")
            } else {
                Log.w(TAG, "FloatingAnalyticsService morto — reiniciando automaticamente")
                ensureFloatingServiceRunning()
                // Reenfileirar para tentar após o serviço subir (2s de espera)
                debounceHandler.postDelayed({
                    val svc = FloatingAnalyticsService.instance
                    val d = pendingRideData ?: return@postDelayed
                    if (svc != null) {
                        pendingRideData = null
                        svc.onRideDetected(d)
                        Log.i(TAG, ">>> $successMessage (após restart do serviço)")
                    } else {
                        Log.e(TAG, "FloatingAnalyticsService não reiniciou a tempo — tentando novamente")
                        ensureFloatingServiceRunning()
                        // Terceira tentativa após mais 3s
                        debounceHandler.postDelayed({
                            val svc2 = FloatingAnalyticsService.instance
                            val d2 = pendingRideData ?: return@postDelayed
                            if (svc2 != null) {
                                pendingRideData = null
                                svc2.onRideDetected(d2)
                                Log.i(TAG, ">>> $successMessage (terceira tentativa)")
                            } else {
                                pendingRideData = null
                                Log.e(TAG, "FloatingAnalyticsService não disponível após 3 tentativas — corrida perdida: R$ ${d2.ridePrice}")
                            }
                        }, 3000L)
                    }
                }, 2000L)
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
        if (best.score < 3) {
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
     * Parser específico para texto OCR da 99 e Uber.
     * Ambos mostram dados no formato:
     *   Uber: "8 minutos (4.0 km) de distancia - Endereço - Viagem de 9 minutos (5.5 km) - Endereço"
     *   Uber longa: "8 minutos (3.4 km) de distancia - Endereço - Viagem de 1 h 30 (50 km) - Endereço"
     *   99: "7min (2,7km) - Endereço - 9 min (5,7km) - Endereço"
     *
     * A PRIMEIRA ocorrência é o pickup, a SEGUNDA é a corrida.
     * Se só há 1 par min(km), tenta encontrar o segundo em formato "X h YY (Z km)" (Uber viagens longas).
     */
    private fun parseOcrRoutePairs(text: String): StructuredExtraction? {
        val routePairs = ROUTE_PAIR_PATTERN.findAll(text).toList()

        // Log detalhado: mostrar todos os pares encontrados com contexto
        routePairs.forEachIndexed { idx, match ->
            val contextStart = (match.range.first - 30).coerceAtLeast(0)
            val contextEnd = (match.range.last + 30).coerceAtMost(text.length - 1)
            val context = text.substring(contextStart, contextEnd + 1)
            Log.d(TAG, "OCR route-pair[$idx]: '${match.value}' => min=${match.groupValues[1]}, km=${match.groupValues[2]} | contexto: ...$context...")
        }

        var pickupMin: Int? = null
        var pickupKm: Double? = null
        var rideMin: Int? = null
        var rideKm: Double? = null

        if (routePairs.size >= 2) {
            // Caso padrão: 2+ pares min(km) encontrados
            // O PRIMEIRO par é o pickup (distância até o passageiro)
            // O SEGUNDO par é a corrida (distância da viagem)
            val pickupMatch = routePairs[0]
            val rideMatch = routePairs[1]

            pickupMin = pickupMatch.groupValues[1].toIntOrNull()
            pickupKm = pickupMatch.groupValues[2].replace(",", ".").toDoubleOrNull()
            rideMin = rideMatch.groupValues[1].toIntOrNull()
            rideKm = rideMatch.groupValues[2].replace(",", ".").toDoubleOrNull()
        } else if (routePairs.size == 1) {
            // Só achou 1 par min(km) — o primeiro é pickup.
            // Tentar achar a corrida no formato "X h YY (Z km)" (Uber viagens longas)
            val pickupMatch = routePairs[0]
            pickupMin = pickupMatch.groupValues[1].toIntOrNull()
            pickupKm = pickupMatch.groupValues[2].replace(",", ".").toDoubleOrNull()

            val hourMatch = UBER_HOUR_ROUTE_PATTERN.find(text)
            if (hourMatch != null) {
                val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
                val mins = hourMatch.groupValues[2].toIntOrNull() ?: 0
                rideMin = hours * 60 + mins
                rideKm = hourMatch.groupValues[3].replace(",", ".").toDoubleOrNull()
            } else {
                val tailStart = (pickupMatch.range.last + 1).coerceAtMost(text.length)
                val tail = text.substring(tailStart)

                // Uber OCR às vezes quebra o 2º par e mantém apenas "XX,X km".
                val secondKm = KM_VALUE_PATTERN.findAll(tail)
                    .mapNotNull {
                        it.value
                            .replace("km", "", ignoreCase = true)
                            .trim()
                            .replace(",", ".")
                            .toDoubleOrNull()
                    }
                    .firstOrNull { km ->
                        val p = pickupKm ?: 0.0
                        km > p + 0.4 && km in 0.5..300.0
                    }
                if (secondKm != null) {
                    rideKm = secondKm
                }

                val tripMinutes = Regex("""(?i)viagem\s+de\s*(\d{1,3})\s*min(?:utos?)?""")
                    .find(tail)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (tripMinutes != null && tripMinutes in 1..300) {
                    rideMin = tripMinutes
                }
            }
        }

        if (rideKm == null && rideMin == null) {
            Log.d(TAG, "OCR route pairs: encontrou ${routePairs.size} par(es) min(km), sem dados de corrida")
            return null
        }

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
     * Suporta formato Uber "X h YY (Z km)" para viagens longas.
     */
    private fun disambiguateByPosition(text: String, pricePosition: Int): StructuredExtraction {
        val beforePrice = if (pricePosition > 0) text.substring(0, pricePosition) else ""
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        val pickupKm = parseFirstKmValue(beforePrice)
        val pickupMin = parseFirstMinValue(beforePrice)

        // Para corrida: tentar formato "X h YY (Z km)" da Uber primeiro
        val hourMatch = UBER_HOUR_ROUTE_PATTERN.find(afterPrice)
        val rideKm: Double?
        val rideMin: Int?
        if (hourMatch != null) {
            val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatch.groupValues[2].toIntOrNull() ?: 0
            rideMin = hours * 60 + mins
            rideKm = hourMatch.groupValues[3].replace(",", ".").toDoubleOrNull()
        } else {
            rideKm = parseFirstKmValue(afterPrice)
            rideMin = parseFirstMinValue(afterPrice)
        }

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
     * Suporta formato Uber "X h YY (Z km)" para viagens longas.
     */
    private fun parseRideDistanceFromText(text: String, pricePosition: Int): Double? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        // Tentar formato em horas da Uber: "1 h 30 (50 km)" → km=50
        val hourMatch = UBER_HOUR_ROUTE_PATTERN.find(afterPrice)
        if (hourMatch != null) {
            val km = hourMatch.groupValues[3].replace(",", ".").toDoubleOrNull()
            if (km != null && km in 0.2..500.0) return km
        }

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
     * Suporta formato "X h YY (Z km)" da Uber para viagens longas.
     */
    private fun parseRideTimeFromText(text: String, pricePosition: Int): Int? {
        val afterPrice = if (pricePosition < text.length) text.substring(pricePosition) else text

        // Tentar formato em horas da Uber: "1 h 30 (50 km)" → 90 min
        val hourMatch = UBER_HOUR_ROUTE_PATTERN.find(afterPrice)
        if (hourMatch != null) {
            val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatch.groupValues[2].toIntOrNull() ?: 0
            val totalMin = hours * 60 + mins
            if (totalMin in 1..600) return totalMin
        }

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

        // Fallback: tentar hora em todo o texto
        val hourMatchAll = UBER_HOUR_ROUTE_PATTERN.find(text)
        if (hourMatchAll != null) {
            val hours = hourMatchAll.groupValues[1].toIntOrNull() ?: 0
            val mins = hourMatchAll.groupValues[2].toIntOrNull() ?: 0
            val totalMin = hours * 60 + mins
            if (totalMin in 1..600) return totalMin
        }

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

        val appSource = detectAppSource(packageName)
        if (appSource == AppSource.UNKNOWN && !triggerReason.startsWith("empty-tree")) {
            return false
        }

        // Cooldown adaptativo: mais agressivo para árvores vazias de apps de corrida
        val cooldown = if (triggerReason.startsWith("empty-tree")) {
            EMPTY_TREE_OCR_COOLDOWN_MS
        } else {
            OCR_FALLBACK_MIN_INTERVAL_MS
        }

        val now = System.currentTimeMillis()
        if (now - lastOcrFallbackAt < cooldown) return false
        lastOcrFallbackAt = now
        Log.i(TAG, "OCR disparado para $packageName (trigger=$triggerReason, cooldown=${cooldown}ms)")

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
                                    val rawOcrText = visionText.text.orEmpty().trim()
                                    val ocrText = sanitizeTextForRideParsing(rawOcrText)
                                    if (ocrText.isBlank()) {
                                        Log.i(TAG, "OCR fallback: texto vazio (trigger=$triggerReason)")
                                        return@addOnSuccessListener
                                    }

                                    Log.i(TAG, "OCR capturou ${ocrText.length} chars (trigger=$triggerReason): ${ocrText.take(250).replace('\n', ' ')}")

                                    if (hasStrongRideSignal(ocrText)) {
                                        Log.i(TAG, "OCR encontrou sinal forte de corrida (${ocrText.length} chars), trigger=$triggerReason")
                                        processCandidateText(
                                            finalText = ocrText,
                                            packageName = packageName,
                                            isStateChange = false,
                                            rootPackage = "ocr-fallback",
                                            allowOcrFallback = false
                                        )
                                    } else {
                                        Log.i(TAG, "OCR sem sinal forte (trigger=$triggerReason) — hasPrice=${PRICE_PATTERN.containsMatchIn(ocrText) || FALLBACK_PRICE_PATTERN.containsMatchIn(ocrText)}, hasKm=${KM_VALUE_PATTERN.containsMatchIn(ocrText)}, hasMin=${MIN_VALUE_PATTERN.containsMatchIn(ocrText) || MIN_RANGE_PATTERN.containsMatchIn(ocrText)}")
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "OCR fallback falhou (trigger=$triggerReason): ${e.message}")
                                }
                        } catch (e: Exception) {
                            Log.w(TAG, "OCR fallback erro no processamento (trigger=$triggerReason): ${e.message}")
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
        // 1. Formato universal: usar pares min(km) e capturar endereço logo após cada par.
        // Regra solicitada: endereço do passageiro após 1º par; destino após 2º par.
        val routePairs = ROUTE_PAIR_PATTERN.findAll(text).toList()
        if (routePairs.size >= 2) {
            val firstPairEnd = routePairs[0].range.last + 1
            val secondPairStart = routePairs[1].range.first
            val secondPairEnd = routePairs[1].range.last + 1

            val pickupSegment = if (firstPairEnd < secondPairStart) {
                text.substring(firstPairEnd, secondPairStart)
            } else ""

            val dropoffSegment = if (secondPairEnd < text.length) {
                text.substring(secondPairEnd)
            } else ""

            val pickup = extractBestAddressFromSegment(pickupSegment)
            val dropoff = extractBestAddressFromSegment(dropoffSegment)

            if (pickup.isNotBlank() || dropoff.isNotBlank()) {
                return Pair(pickup, dropoff)
            }
        }

        // 2. Padrões textuais com prefixo (Uber e genérico)
        val pickupPatterns = listOf(
            Regex("""(?:embarque|buscar|retirada|origem|local\s+de\s+embarque)[:\s]+([^,\n]{3,60})""", RegexOption.IGNORE_CASE)
        )
        val dropoffPatterns = listOf(
            Regex("""(?:destino|para|até|entrega|deixar|local\s+de\s+destino)[:\s]+([^,\n]{3,60})""", RegexOption.IGNORE_CASE)
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

        if (pickup.isNotBlank() && dropoff.isNotBlank()) {
            return Pair(pickup, dropoff)
        }

        // 3. Heurística OCR: linhas que parecem endereços (contêm nomes de ruas/avenidas/etc)
        val addressPattern = Regex(
            """(?:R\.|Rua|Av\.|Avenida|Al\.|Alameda|Trav\.|Travessa|Pra[cç]a|Estr\.|Estrada|Rod\.|Rodovia|Lg\.|Largo)\s+[A-ZÀ-Ú][^\n]{3,60}""",
            RegexOption.IGNORE_CASE
        )
        val addressMatches = addressPattern.findAll(text).map { it.value.trim() }.toList()
        if (addressMatches.size >= 2) {
            return Pair(addressMatches[0], addressMatches[1])
        } else if (addressMatches.size == 1) {
            // Só 1 endereço encontrado — usar como pickup, destino fica vazio
            return Pair(addressMatches[0], dropoff)
        }

        return Pair(pickup, dropoff)
    }

    private fun extractBestAddressFromSegment(segment: String): String {
        if (segment.isBlank()) return ""

        val originalLines = segment
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val candidateLines = originalLines
            .map { sanitizeAddressCandidate(it) }
            .filter { it.length >= 4 }
            .filterNot { isLikelyNoiseAddressLine(it) }

        if (candidateLines.isNotEmpty()) {
            val bestLine = candidateLines.maxByOrNull { line ->
                val roadScore = if (Regex("""(?i)\b(R\.?|Rua|Av\.?|Avenida|Al\.?|Alameda|Trav\.?|Travessa|Pra[cç]a|Estr\.?|Estrada|Rod\.?|Rodovia|Vila|Setor|Residencial|Condom[ií]nio)\b""").containsMatchIn(line)) 3 else 0
                val digitScore = if (line.any { it.isDigit() }) 1 else 0
                val commaScore = if (line.contains(',')) 1 else 0
                val sizeScore = line.length.coerceAtMost(60) / 20
                roadScore + digitScore + commaScore + sizeScore
            }.orEmpty()

            if (bestLine.isNotBlank()) {
                return bestLine
            }
        }

        val normalized = segment
            .replace("•", " ")
            .replace("·", " ")
            .replace("|", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Primeiro tentar padrão clássico de logradouro
        val roadPattern = Regex(
            """(?:R\\.?|Rua|Av\\.?|Avenida|Al\\.?|Alameda|Trav\\.?|Travessa|Pra[cç]a|Estr\\.?|Estrada|Rod\\.?|Rodovia)\\s+[A-ZÀ-Ú0-9][^•|]{3,120}""",
            RegexOption.IGNORE_CASE
        )
        val roadMatch = roadPattern.find(normalized)?.value?.trim().orEmpty()
        if (roadMatch.isNotBlank()) {
            return sanitizeAddressCandidate(roadMatch)
        }

        // Fallback: pegar trecho textual significativo até palavras de corte
        val generic = normalized
            .replace(Regex("""(?i)\\b(aceitar|recusar|ignorar|corrida longa|perfil essencial|perfil premium|taxa de deslocamento|parada\\(s\\)|parada)\\b.*"""), "")
            .trim()

        // Evitar retornar lixo (somente números/unidades)
        if (generic.length < 6) return ""
        if (!generic.any { it.isLetter() }) return ""
        if (Regex("""^\\d+[\\d,\.\\s]*$""").matches(generic)) return ""

        return sanitizeAddressCandidate(generic)
    }

    private fun sanitizeAddressCandidate(input: String): String {
        return input
            .replace(Regex("""(?i)\b(aceitar|recusar|ignorar|corrida longa|perfil essencial|perfil premium|taxa de deslocamento|parada\(s\)|parada)\b.*"""), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '•', '·', ',', ';')
    }

    private fun isLikelyNoiseAddressLine(line: String): Boolean {
        val lower = line.lowercase()
        if (line.length <= 3) return true
        if (!line.any { it.isLetter() }) return true
        if (Regex("""^\d+[\d\s,\.\-/]*$""").matches(line)) return true

        val uiNoiseTokens = listOf(
            "perfil premium", "perfil essencial", "corridas", "corrida longa",
            "taxa de deslocamento", "aceitar", "recusar", "ignorar", "soluções"
        )
        if (uiNoiseTokens.any { lower.contains(it) }) return true

        return false
    }
}
