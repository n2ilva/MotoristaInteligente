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
class RideInfoOcrService : AccessibilityService() {

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
        // Aceita: R$ 15,50 / R$15.50 / $ 15,50 / $15.50 / R$ 8,00 / R$ 125,90 / R$7.5 / R$ 15
        // Ignora multiplicadores como "$1,2~1,8x" / "$1,2x"
        private val PRICE_PATTERN = Regex(
            """(?:R\$|\$)\s*(\d{1,4}(?:[,\.]\d{1,2})?)(?!\s*(?:[~\-–]\s*\d{1,4}(?:[,\.]\d{1,2})?)\s*x)(?!\s*x)""",
            RegexOption.IGNORE_CASE
        )
        private val KM_IN_PAREN_PATTERN = Regex("""\(\s*(\d{1,3}(?:[,\.]\d+)?)\s*km\s*\)""", RegexOption.IGNORE_CASE)
        // Fallback sem R$: 15,50 / 125.90 (2 casas para evitar conflito com km/min)
        private val FALLBACK_PRICE_PATTERN = Regex("""\b(\d{1,4}[,\.]\d{2})\b""")
        private val AVG_PRICE_PER_KM_SUFFIX_PATTERN = Regex(
            """^\s*(?:↑|↗|↖|⇧|⤴|🡅|🔺|🟡|\+)?\s*(?:/\s*km|por\s*km\b|km\b)""",
            RegexOption.IGNORE_CASE
        )
        // Aceita: 8.2 km / 8,2km / 12.5 Km / 3km
        private val DISTANCE_PATTERN = Regex("""(\d{1,3}[,\.]?\d*)\s*km""", RegexOption.IGNORE_CASE)
        // Aceita: 15 min / 8min / 20 minutos / 1-3 nmin (OCR)
        private val TIME_PATTERN = Regex("""(\d{1,3})\s*(?:n?\s*min(?:utos?)?)\b""", RegexOption.IGNORE_CASE)
        // Aceita: 1 h e 43 min / 2h 05 min / 43 min
        private val DURATION_PATTERN = Regex(
            """(?:(\d{1,2})\s*h(?:oras?)?\s*(?:e\s*)?)?(\d{1,3})\s*min(?:utos?)?\b""",
            RegexOption.IGNORE_CASE
        )
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
            """(?:buscar|embarque|pickup|retirada|chegar|chegada|até\s+(?:o\s+)?passageiro|ir\s+até)[^\d]{0,20}(\d{1,3})\s*min(?:utos?)?\b""",
            RegexOption.IGNORE_CASE
        )
        // Padrão inline: "X min (Y km)" / "X minuto (Y km)" / "X minutos (Y km)" — Uber e 99
        private val PICKUP_INLINE_PATTERN = Regex(
            """(\d{1,2})\s*min(?:utos?)?\s*(?:de\s*dist[aâ]ncia)?\s*\(?\s*(\d{1,3}(?:[,\.]\d+)?)\s*km\s*\)?""",
            RegexOption.IGNORE_CASE
        )

        // Padrão universal OCR: "Xmin (Y,Zkm)" ou "X minutos (Y.Z km)"
        // 99:   "3min (1,1km)"  / "8min (4,4km)"
        // Uber: "7 minutos (3.0 km) de distância" / "Viagem de 1 h e 43 min (93.7 km)"
        private val ROUTE_PAIR_PATTERN = Regex(
            """(?:(\d{1,2})\s*h(?:oras?)?\s*(?:e\s*)?)?(\d{1,3})\s*min(?:utos?)?(?:\s*de\s*dist[aâ]ncia)?\s*(?:\(\s*)?(\d{1,3}(?:[,\.]\d+)?)\s*km(?:\s*\))?""",
            RegexOption.IGNORE_CASE
        )
        // 99: pickup expresso em metros: "3min (843m)" — passageiro muito próximo
        private val ROUTE_PAIR_METERS_PATTERN = Regex(
            """(\d{1,3})\s*min(?:utos?)?\s*\(\s*(\d{2,4})\s*m\s*\)""",
            RegexOption.IGNORE_CASE
        )
        // Distância em metros entre parênteses: (843m) / (200 m) — formato 99 para pickup curto
        private val METERS_IN_PAREN_PATTERN = Regex(
            """\(\s*(\d{2,4})\s*m\s*\)""",
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
            "Score:",
            "ANÁLISE", "ANALISE",
            "Média valor/Km", "Media valor/Km",
            "Média valor/Hora", "Media valor/Hora",
            "Valor da Corrida"
        )
        private val OWN_CARD_NOISE_TOKENS = listOf(
            "r\$/km", "r\$km", "r\$/min", "r\$/h", "km total",
            "valor corrida", "valor da corrida",
            "media valor/km", "média valor/km",
            "media valor/hora", "média valor/hora",
            "analise", "análise",
            "dentro dos seus parâmetros", "dentro dos seus parametros",
            "não compensa", "nao compensa",
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

        private val UBER_NOTIFICATION_RIDE_KEYWORDS = listOf(
            "viagem", "corrida", "pedido", "trip", "request", "ride", "delivery", "entrega"
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
        // Ex.: 843m / 500 m / 1200m — qualquer valor em metros (parênteses ou não).
        // \b após m evita match em "30min" (m seguido de 'i' não tem word boundary).
        private val METERS_VALUE_PATTERN = Regex("""\b(\d{1,4})\s*m\b""", RegexOption.IGNORE_CASE)

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
        // 99: "Prioritário" simples (sem "Pop Expresso") seguido de preço
        // Ex.: "Prioritário\nR$32,70\nR$1,23/km" — R$1,23 é filtrado por MIN_RIDE_PRICE
        private val NINETY_NINE_PRIORITARIO_SIMPLE_PATTERN = Regex(
            """Priorit[áa]rio[\s\S]{0,80}?R\$\s*(\d{1,4}(?:[,\.]\d{1,2})?)""",
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
        private const val AUTO_DEBUG_DUMP_ENABLED = false
        private const val AUTO_DEBUG_DUMP_INTERVAL_MS = 3000L
        private const val AUTO_DEBUG_MAX_CHARS = 5000
        private const val OCR_FALLBACK_ENABLED = true
        private const val OCR_FALLBACK_MIN_INTERVAL_MS = 1200L  // 1.2s entre tentativas OCR
        private const val OCR_BOTTOM_HALF_START_FRACTION = 0.3
        private const val OCR_BOTTOM_HALF_START_FRACTION_99 = 0.0

        // Filtro: ignorar nós de acessibilidade no topo da tela (earnings card Uber)
        private const val TOP_SCREEN_FILTER_FRACTION = 0.15  // 15% superior da tela

        // Dedup persistente para node-price-only: mesmo preço repetido = earnings card, não corrida
        private const val NODE_PRICE_ONLY_MAX_REPEATS = 2
        private const val NODE_PRICE_ONLY_DEDUP_WINDOW_MS = 60_000L  // 60s
        private const val NODE_PRICE_ONLY_SUPPRESSION_HOLD_MS = 45_000L
        private const val NODE_PRICE_ONLY_SUPPRESSION_LOG_THROTTLE_MS = 10_000L

        // Debounce: reduzir latência sem perder estabilidade
        private const val DEBOUNCE_DELAY = 250L // 250ms

        // Delay para aguardar animação de entrada do card (Uber desliza o card de baixo para cima)
        // Sem esse delay, o OCR captura o card parcialmente renderizado e obtém dados corrompidos
        private const val CARD_RENDER_DELAY_MS = 700L  // 700ms para STATE_CHANGED (nova tela/popup)

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
    private val recentOfferFingerprints = LinkedHashMap<String, Long>()
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
    private var pendingStateOcrRunnable: Runnable? = null
    private val OFFER_FINGERPRINT_WINDOW_MS = 90_000L
    private val OFFER_FINGERPRINT_MAX_SIZE = 200

    // ========================
    // Detecção de Aceitação de Corrida
    // ========================
    // Após uma oferta ser detectada, monitorar por sinais de aceitação
    // (tela mudou para "a caminho", "corrida aceita", etc.)
    private var lastOfferAppSource: AppSource? = null
    private var lastOfferTimestamp = 0L
    private var lastOfferAlreadyAccepted = false
    private val ACCEPTANCE_DETECTION_WINDOW_MS = 30_000L  // Janela de 30s pós-oferta
    private var tripInProgress = false
    private var tripInProgressStartedAt = 0L
    private val TRIP_MODE_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2h

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
        "aceitar", "accept", "aceitar por", "confirmar"
    )

    private fun String.isOwnPackageName(): Boolean {
        return this == OWN_PACKAGE || this.startsWith(OWN_PACKAGE)
    }

    private fun shouldLogWithThrottle(
        cache: MutableMap<String, Long>,
        key: String,
        throttleMs: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = cache[key] ?: 0L
        if (now - last < throttleMs) return false
        cache[key] = now
        return true
    }

    private fun containsAnyIgnoreCase(text: String, keywords: List<String>): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun normalizeAddressForFingerprint(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s,.-]"""), "")
            .trim()
    }

    private fun buildOfferFingerprint(
        appSource: AppSource,
        packageName: String,
        normalizedPrice: Double,
        rideDistanceKm: Double,
        pickupDistanceKm: Double?,
        pickupAddress: String,
        dropoffAddress: String
    ): String {
        val priceKey = (normalizedPrice * 100).toInt()
        val rideDistanceKey = (rideDistanceKm * 10).toInt()
        val pickupDistanceKey = ((pickupDistanceKm ?: -1.0) * 10).toInt()
        val pickupKey = normalizeAddressForFingerprint(pickupAddress)
        val dropoffKey = normalizeAddressForFingerprint(dropoffAddress)

        return listOf(
            appSource.name,
            packageName,
            priceKey.toString(),
            rideDistanceKey.toString(),
            pickupDistanceKey.toString(),
            pickupKey,
            dropoffKey
        ).joinToString("|")
    }

    private fun isRecentlySeenOffer(fingerprint: String, now: Long): Boolean {
        val iterator = recentOfferFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > OFFER_FINGERPRINT_WINDOW_MS) {
                iterator.remove()
            }
        }

        val lastSeen = recentOfferFingerprints[fingerprint]
        if (lastSeen != null && now - lastSeen < OFFER_FINGERPRINT_WINDOW_MS) {
            recentOfferFingerprints[fingerprint] = now
            return true
        }

        recentOfferFingerprints[fingerprint] = now
        while (recentOfferFingerprints.size > OFFER_FINGERPRINT_MAX_SIZE) {
            val firstKey = recentOfferFingerprints.keys.firstOrNull() ?: break
            recentOfferFingerprints.remove(firstKey)
        }
        return false
    }

    private fun shouldLogEvent(key: String): Boolean {
        return shouldLogWithThrottle(lastEventLogAt, key, EVENT_LOG_THROTTLE_MS)
    }

    private fun shouldLogDiagnostic(key: String): Boolean {
        return shouldLogWithThrottle(lastDiagnosticLogAt, key, DIAGNOSTIC_LOG_THROTTLE_MS)
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
        return RideOcrAppClassifier.isMonitoredPackage(
            packageName = packageName,
            ownPackage = OWN_PACKAGE,
            allMonitored = ALL_MONITORED
        )
    }

    private fun detectAppSource(packageName: String): AppSource {
        return RideOcrAppClassifier.detectAppSource(
            packageName = packageName,
            uberPackages = UBER_PACKAGES,
            ninetyNinePackages = NINETY_NINE_PACKAGES
        )
    }

    /**
     * Detecta a fonte do app (Uber ou 99) analisando o CONTEÚDO DO TEXTO na tela,
     * sem depender do nome do pacote. Usa os padrões específicos de cada app.
     *
     * Uber: "UberX - Exclusivo - R$ XX" / "UberX - R$ XX" / "UberX . Adolescentes - R$ XX"
     * 99: "Corrida Longa - R$ XX" / "Negocia - R$ XX" / "Prioritário - Pop Expresso - R$ XX"
     */
    private fun detectAppSourceFromScreenText(text: String): AppSource {
        return RideOcrAppClassifier.detectAppSourceFromScreenText(text)
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
                if (rootPkg.isOwnPackageName()) {
                    continue
                }

                extractTextRecursive(root, sb, 0)
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * NÃO inicia o serviço automaticamente.
     * A análise deve ser ativada apenas por ação manual do usuário na UI.
     */
    private fun ensureFloatingServiceRunning() {
        if (!AnalysisServiceState.isEnabled(this)) {
            return
        }

        if (FloatingAnalyticsService.instance == null &&
            shouldLogDiagnostic("manual-activation-required")
        ) {
            Log.w(
                TAG,
                "FloatingAnalyticsService inativo. Ativação automática bloqueada; usuário deve iniciar manualmente."
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (!AnalysisServiceState.isEnabled(this) || AnalysisServiceState.isPaused(this)) return

        val packageName = event.packageName?.toString() ?: return

        // IGNORAR eventos do nosso próprio app
        if (packageName.isOwnPackageName()) {
            pendingStateOcrRunnable?.let { debounceHandler.removeCallbacks(it) }
            pendingStateOcrRunnable = null
            return
        }

        // Processar somente Uber/99 para evitar leitura de janelas de outros apps
        if (!isMonitoredPackage(packageName)) {
            return
        }

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
            val appSource = detectAppSource(packageName)
            val bypassCooldown =
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    hasStrongRideSignal(
                        event.text?.joinToString(" ")?.trim().orEmpty(),
                        appSource
                    )

            if (bypassCooldown) {
                Log.d(TAG, "Cooldown ignorado: nova corrida detectada via WINDOW_STATE_CHANGED com sinal forte")
                lastDetectedHash = ""
            } else {
                if (now - lastCooldownDebugAt > DEBUG_COOLDOWN_LOG_INTERVAL) {
                    lastCooldownDebugAt = now
                    Log.d(TAG, "Ignorado por cooldown: tipo=${AccessibilityEvent.eventTypeToString(eventType)}, delta=${now - lastDetectedTime}ms, cooldown=${cooldown}ms")
                }
                return
            }
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
        pendingStateOcrRunnable = null
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

                if (!AnalysisServiceState.isEnabled(this@RideInfoOcrService)) {
                    debounceHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
                    return
                }

                val instance = FloatingAnalyticsService.instance
                if (instance == null && shouldLogDiagnostic("healthcheck-manual-only")) {
                    Log.w(TAG, "HEALTHCHECK: FloatingAnalyticsService inativo (sem auto-restart, modo manual)")
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
            Log.d(TAG, "✗ Sinal de REJEIÇÃO detectado: '$text' → oferta descartada, resetando cooldowns")
            tripInProgress = false
            tripInProgressStartedAt = 0L
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
        tripInProgress = true
        tripInProgressStartedAt = System.currentTimeMillis()

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

    private fun updateTripModeByText(text: String) {
        if (!tripInProgress || text.isBlank()) return

        val lower = text.lowercase()
        val ended = REJECTION_SIGNALS.any { lower.contains(it) } ||
            lower.contains("corrida finalizada") ||
            lower.contains("viagem finalizada") ||
            lower.contains("finalizar corrida") ||
            lower.contains("você está online") ||
            lower.contains("procurando corridas")

        if (ended) {
            tripInProgress = false
            tripInProgressStartedAt = 0L
            Log.d(TAG, "Modo corrida em andamento desativado por sinal de fim/cancelamento")
        }
    }

    private fun shouldSuppressBecauseTripInProgress(text: String): Boolean {
        if (!tripInProgress) return false

        // Se uma nova oferta forte apareceu, sair do modo de supressão imediatamente.
        // Evita ficar "travado" em tripInProgress por falso positivo de aceitação.
        if (hasStrongRideSignal(text)) {
            tripInProgress = false
            tripInProgressStartedAt = 0L
            return false
        }

        val now = System.currentTimeMillis()
        if (tripInProgressStartedAt > 0L && now - tripInProgressStartedAt > TRIP_MODE_MAX_DURATION_MS) {
            tripInProgress = false
            tripInProgressStartedAt = 0L
            return false
        }

        val lower = text.lowercase()
        val hasOfferAction = ACTION_KEYWORDS.any { lower.contains(it) }
        if (hasOfferAction) return false

        val navigationSignals = listOf(
            "a caminho", "navegando", "navegação", "rota", "rota iniciada", "iniciar navegação",
            "dirija", "vire", "continue", "chegar ao passageiro", "chegando", "viagem em andamento",
            "corrida em andamento", "tempo estimado", "trânsito"
        )

        if (navigationSignals.any { lower.contains(it) }) {
            return true
        }

        val roadLikeCount = Regex("""(?i)\b(?:r(?:ua)?\.?|av(?:enida)?\.?|m(?:arginal)?\.?)\s+[\p{L}\p{N}]""")
            .findAll(text)
            .count()

        return roadLikeCount >= 2
    }

    /**
     * Limpa o estado de rastreamento de oferta pendente.
     */
    private fun clearOfferState() {
        lastOfferAppSource = null
        lastOfferTimestamp = 0L
        lastOfferAlreadyAccepted = false
        lastDetectedTime = 0L
        lastDetectedHash = ""
    }

    /**
     * Registra que uma nova oferta foi mostrada, iniciando a janela de detecção de aceitação.
     */
    private fun registerOfferForAcceptanceTracking(appSource: AppSource) {
        // Nova oferta na tela significa que não estamos mais em corrida ativa anterior.
        // Isso evita manter supressão indevida entre ofertas consecutivas.
        tripInProgress = false
        tripInProgressStartedAt = 0L

        lastOfferAppSource = appSource
        lastOfferTimestamp = System.currentTimeMillis()
        lastOfferAlreadyAccepted = false
        Log.d(TAG, "Oferta registrada para rastreamento de aceitação: ${appSource.displayName}")
    }

    // ========================
    // Handlers de Eventos
    // ========================

    private fun handleNotification(event: AccessibilityEvent, packageName: String) {
        val eventText = event.text?.joinToString(" ")?.trim().orEmpty()
        if (eventText.isNotBlank()) {
            processCandidateText(
                finalText = eventText,
                packageName = packageName,
                isStateChange = true,
                rootPackage = "notification",
                allowOcrFallback = true
            )
        }
        requestOcrFallbackForOffer(packageName, "notification-ocr-only")
    }

    private fun handleWindowChange(event: AccessibilityEvent, packageName: String, isStateChange: Boolean) {
        val eventText = event.text?.joinToString(" ")?.trim().orEmpty()
        if (eventText.isNotBlank()) {
            processCandidateText(
                finalText = eventText,
                packageName = packageName,
                isStateChange = isStateChange,
                rootPackage = "event-text",
                allowOcrFallback = true
            )
        }

        if (isStateChange) {
            // Aguardar a animação de entrada do card completar (ex.: Uber desliza card de baixo)
            // antes de disparar o OCR, para evitar capturar dados parcialmente renderizados
            pendingStateOcrRunnable?.let { debounceHandler.removeCallbacks(it) }
            val stateRunnable = Runnable {
                requestOcrFallbackForOffer(packageName, "window-state-ocr-only")
            }
            pendingStateOcrRunnable = stateRunnable
            debounceHandler.postDelayed(stateRunnable, CARD_RENDER_DELAY_MS)
        } else {
            requestOcrFallbackForOffer(packageName, "window-content-ocr-only")
        }
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
        val sanitizedText = RideOcrTextProcessing.sanitizeTextForRideParsing(
            text = finalText,
            ownCardNoiseTokens = OWN_CARD_NOISE_TOKENS
        )

        if (sanitizedText.isBlank()) {
            Log.d(TAG, "Ignorado: texto final vazio")
            return
        }

        // Muitos eventos de Uber/99 trazem apenas IDs de layout (sem conteúdo útil de corrida).
        // Ex.: com.ubercab.driver:id/rootView ...
        if (looksLikeStructuralIdOnlyText(sanitizedText)) {
            if (allowOcrFallback && rootPackage != "ocr-fallback") {
                val requested = requestOcrFallbackForOffer(packageName, "structural-id-only")
                if (requested) {
                    Log.d(TAG, "Texto estrutural (IDs) detectado em $packageName → OCR disparado")
                }
            }
            return
        }

        // Verificar se é texto do nosso próprio card (loop de auto-detecção)
        if (isOwnCardText(sanitizedText)) {
            return
        }

        updateTripModeByText(sanitizedText)
        if (shouldSuppressBecauseTripInProgress(sanitizedText)) {
            val key = "trip-mode-suppressed|$packageName|${if (isStateChange) "STATE" else "CONTENT"}"
            if (shouldLogDiagnostic(key)) {
                Log.d(TAG, "Ignorado por modo corrida em andamento (texto de navegação/mapa)")
            }
            return
        }

        val appSource = detectAppSource(packageName)

        if (!hasRequiredOfferTokens(sanitizedText, appSource)) {
            val key = "missing-core-tokens|$packageName|${if (isStateChange) "STATE" else "CONTENT"}"
            if (shouldLogDiagnostic(key)) {
                Log.d(
                    TAG,
                    "Ignorado sem tokens obrigatórios (app=${appSource.displayName})"
                )
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

        if (!isLikelyRideOffer(sanitizedText, isStateChange, appSource)) {
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

    private fun hasStrongOfferSignalForPriceOnly(text: String): Boolean {
        val hasActionKeyword = ACTION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (hasActionKeyword) return true

        val hasOfferContextKeyword = CONTEXT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (hasOfferContextKeyword) return true

        return text.contains("nova viagem", ignoreCase = true) ||
            text.contains("novo pedido", ignoreCase = true) ||
            text.contains("solicitação", ignoreCase = true) ||
            text.contains("request", ignoreCase = true) ||
            text.contains("trip", ignoreCase = true) ||
            text.contains("ride", ignoreCase = true)
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
            val keywordQueries = listOf("R$", "$", "km", "min", "aceitar", "accept", "corrida", "viagem")
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
                    }
                }
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
                    continue
                }

                extractTextRecursive(root, sb, 0)
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isLikelyRideOffer(text: String, isStateChange: Boolean, appSource: AppSource = AppSource.UNKNOWN): Boolean {
        // === FAST PATH: Se bater nos padrões específicos de Uber ou 99, ACEITAR direto ===
        if (
            isRecognizedRideCard(text) &&
            PRICE_PATTERN.containsMatchIn(text) &&
            RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
                text = text,
                kmPattern = KM_VALUE_PATTERN,
                metersPattern = METERS_VALUE_PATTERN
            )
        ) return true

        val hasPrice = PRICE_PATTERN.containsMatchIn(text)
        if (!hasPrice) return false

        val actionCount = ACTION_KEYWORDS.count { text.contains(it, ignoreCase = true) }
        val contextCount = CONTEXT_KEYWORDS.count { text.contains(it, ignoreCase = true) }
        val hasKm = KM_VALUE_PATTERN.containsMatchIn(text)
        val hasTwoKmValues = RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
            text = text,
            kmPattern = KM_VALUE_PATTERN,
            metersPattern = METERS_VALUE_PATTERN
        )
        val hasExplicitCurrency = text.contains("R$", ignoreCase = true)
        val hasPlusPrice = Regex("""\+\s*R\$\s*\d""").containsMatchIn(text)

        val confidence =
            (if (actionCount > 0) 3 else 0) +
            (if (contextCount > 0) 2 else 0) +
            (if (hasKm) 2 else 0) +
            (if (hasTwoKmValues) 2 else 0) +
            (if (hasExplicitCurrency) 1 else 0) +
            (if (hasPlusPrice) 1 else 0)

        val uberLikeOfferPattern = hasPrice && hasTwoKmValues && hasKm

        // Regra:
        // - Se tiver ação explícita (aceitar/recusar etc), aceitar.
        // - Se bater padrão típico de oferta da Uber, aceitar.
        // - Senão, usar limiar de confiança moderado.
        val threshold = when (appSource) {
            AppSource.UBER -> if (isStateChange) 2 else 3
            AppSource.NINETY_NINE -> if (isStateChange) 3 else 4
            AppSource.UNKNOWN -> if (isStateChange) 3 else 4
        }

        val accepted = actionCount > 0 || uberLikeOfferPattern || confidence >= threshold

        if (!accepted) {
            val key = "confidence-detail|${if (isStateChange) "STATE" else "CONTENT"}|$actionCount|$contextCount|$hasKm|$hasTwoKmValues|$hasExplicitCurrency|$hasPlusPrice|$confidence"
            if (shouldLogDiagnostic(key)) {
                Log.d(
                    TAG,
                    "Baixa confiança: action=$actionCount, context=$contextCount, km=$hasKm, hasTwoKmValues=$hasTwoKmValues, moeda=$hasExplicitCurrency, plusPrice=$hasPlusPrice, score=$confidence"
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
                    continue
                }

                // Obter altura da tela para filtrar nós no topo (earnings card)
                val screenHeight = resources.displayMetrics.heightPixels
                val topThreshold = (screenHeight * TOP_SCREEN_FILTER_FRACTION).toInt()

                val queries = listOf("R$", "$", "km", "min")
                for (query in queries) {
                    val nodes = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null } ?: continue
                    for (node in nodes) {
                        // Filtrar nós no topo da tela (earnings card Uber mostra ganhos acumulados)
                        val nodeBounds = Rect()
                        node.getBoundsInScreen(nodeBounds)
                        if (query == "R$" && nodeBounds.top < topThreshold && nodeBounds.bottom < topThreshold) {
                            Log.d(TAG, "Node R$ ignorado por estar no topo da tela (top=${nodeBounds.top}, threshold=$topThreshold): ${node.text}")
                            continue
                        }

                        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        node.hintText?.let { if (it.isNotBlank()) sb.append(it).append(' ') }

                        val parent = try { node.parent } catch (_: Exception) { null }
                        parent?.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                        parent?.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                    }
                }
            }

            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun hasStrongRideSignal(text: String, appSource: AppSource = AppSource.UNKNOWN): Boolean {
        if (text.isBlank()) return false

        // Se bater nos padrões específicos de Uber ou 99, é sinal forte
        val recognizedCard = isRecognizedRideCard(text)
        if (
            recognizedCard &&
            PRICE_PATTERN.containsMatchIn(text) &&
            RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
                text = text,
                kmPattern = KM_VALUE_PATTERN,
                metersPattern = METERS_VALUE_PATTERN
            )
        ) return true

        val hasPrice = PRICE_PATTERN.containsMatchIn(text)
        val hasTwoDistanceSignals = RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
            text = text,
            kmPattern = KM_VALUE_PATTERN,
            metersPattern = METERS_VALUE_PATTERN
        )
        val hasActionContext =
            containsAnyIgnoreCase(text, ACTION_KEYWORDS) ||
                containsAnyIgnoreCase(text, CONTEXT_KEYWORDS)

        if (appSource == AppSource.UBER && hasPrice && (hasTwoDistanceSignals || hasActionContext)) return true
        if (appSource == AppSource.NINETY_NINE && hasPrice && hasTwoDistanceSignals) return true

        if (hasPrice && hasTwoDistanceSignals && hasActionContext) return true

        return false
    }

    private fun hasRequiredOfferTokens(text: String, appSource: AppSource): Boolean {
        val hasPrice = PRICE_PATTERN.containsMatchIn(text) || FALLBACK_PRICE_PATTERN.containsMatchIn(text)
        val hasKmToken =
            KM_VALUE_PATTERN.containsMatchIn(text) ||
                METERS_VALUE_PATTERN.containsMatchIn(text)
        val hasMinToken =
            MIN_VALUE_PATTERN.containsMatchIn(text) ||
                MIN_RANGE_PATTERN.containsMatchIn(text) ||
                DURATION_PATTERN.containsMatchIn(text)
        val hasActionOrContext =
            containsAnyIgnoreCase(text, ACTION_KEYWORDS) ||
                containsAnyIgnoreCase(text, CONTEXT_KEYWORDS)

        return when (appSource) {
            AppSource.NINETY_NINE -> hasPrice && hasKmToken && hasMinToken
            AppSource.UBER -> hasPrice && (hasKmToken || hasMinToken || hasActionOrContext)
            AppSource.UNKNOWN ->
                hasPrice && RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
                    text = text,
                    kmPattern = KM_VALUE_PATTERN,
                    metersPattern = METERS_VALUE_PATTERN
                )
        }
    }

    private fun hasVisibleMonitoredWindow(): Boolean {
        return try {
            val allWindows = windows ?: return false
            allWindows.any { window ->
                val root = try { window.root } catch (_: Exception) { null } ?: return@any false
                val rootPkg = root.packageName?.toString().orEmpty()
                rootPkg.isNotBlank() &&
                    !rootPkg.isOwnPackageName() &&
                    isMonitoredPackage(rootPkg)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detecta se o texto contém marcadores do nosso próprio card de análise.
     * Evita o loop infinito de auto-detecção.
     */
    private fun isOwnCardText(text: String): Boolean {
        val matchCount = OWN_CARD_MARKERS.count { text.contains(it, ignoreCase = true) }
        return matchCount >= 2  // Se 2+ marcadores do nosso card estão presentes, é auto-detecção
    }

    private fun parsePriceFromMatch(match: MatchResult): Double? {
        return RideInfoPriceParser.parsePriceFromMatch(match)
    }

    private fun isAvgPerKmPriceMatch(text: String, match: MatchResult): Boolean {
        return RideInfoPriceParser.isAvgPerKmPriceMatch(
            text = text,
            match = match,
            avgPricePerKmSuffixPattern = AVG_PRICE_PER_KM_SUFFIX_PATTERN
        )
    }

    private fun selectRidePriceMatch(
        text: String,
        appSource: AppSource,
        priceMatches: List<MatchResult>
    ): MatchResult? {
        return RideInfoPriceParser.selectRidePriceMatch(
            text = text,
            appSource = appSource,
            priceMatches = priceMatches,
            minRidePrice = MIN_RIDE_PRICE,
            avgPricePerKmSuffixPattern = AVG_PRICE_PER_KM_SUFFIX_PATTERN
        )
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
    private fun parseFirstPriceFromMiddleThird(text: String, appSource: AppSource): Double? {
        return RideInfoPriceParser.parseFirstPriceFromMiddleThird(
            text = text,
            appSource = appSource,
            minRidePrice = MIN_RIDE_PRICE,
            pricePattern = PRICE_PATTERN,
            avgPricePerKmSuffixPattern = AVG_PRICE_PER_KM_SUFFIX_PATTERN
        )
    }

    private fun parseCardPrice(text: String, appSource: AppSource): Double? {
        return RideInfoPriceParser.parseCardPrice(
            text = text,
            appSource = appSource,
            minRidePrice = MIN_RIDE_PRICE,
            pricePattern = PRICE_PATTERN,
            avgPricePerKmSuffixPattern = AVG_PRICE_PER_KM_SUFFIX_PATTERN,
            uberCardPattern = UBER_CARD_PATTERN,
            ninetyNineCorridaLongaPattern = NINETY_NINE_CORRIDA_LONGA_PATTERN,
            ninetyNineNegociaPattern = NINETY_NINE_NEGOCIA_PATTERN,
            ninetyNinePrioritarioPattern = NINETY_NINE_PRIORITARIO_PATTERN,
            ninetyNineAcceptPattern = NINETY_NINE_ACCEPT_PATTERN,
            ninetyNinePrioritarioSimplePattern = NINETY_NINE_PRIORITARIO_SIMPLE_PATTERN
        )
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
        return RideInfoPriceParser.parseHeaderRating(
            text = text,
            appSource = appSource,
            uberHeaderRatingPattern = UBER_HEADER_RATING_PATTERN,
            ninetyNineHeaderRatingPattern = NINETY_NINE_HEADER_RATING_PATTERN
        )
    }

    private fun tryParseRideData(
        text: String,
        packageName: String,
        isNotification: Boolean,
        extractionSource: String
    ) {
        val appSource = detectAppSourceFromScreenText(text)

        // ========== PASSO 0: Extração por padrões específicos de card Uber/99 ==========
        val cardPrice = parseCardPrice(text, appSource)
        if (cardPrice != null) {
            Log.i(TAG, ">>> Preço extraído do card ${appSource.displayName}: R$ $cardPrice")
        }

        // ========== PASSO 1: route pairs / km em parênteses (OCR-only) ==========
        // Ambos os apps mostram pickup e corrida como pares "min (km)".
        // Mesmo quando source != "ocr", o texto combinado pode conter esse padrão.
        val ocrParsed = parseOcrRoutePairs(text)

        if (ocrParsed != null && ocrParsed.price != null) {
            val priceForRoutePairs = if (appSource == AppSource.NINETY_NINE && cardPrice != null && cardPrice >= MIN_RIDE_PRICE) {
                cardPrice
            } else {
                ocrParsed.price
            }

            Log.i(TAG, ">>> Parse OCR route pairs: preço=R$ $priceForRoutePairs, rideKm=${ocrParsed.rideDistanceKm}, pickupKm=${ocrParsed.pickupDistanceKm ?: "?"}, rating=${ocrParsed.userRating ?: "?"}")
            buildAndEmitRideData(
                appSource = appSource,
                packageName = packageName,
                price = priceForRoutePairs,
                rideDistanceKm = ocrParsed.rideDistanceKm,
                rideTimeMin = null,
                pickupDistanceKm = ocrParsed.pickupDistanceKm,
                pickupTimeMin = null,
                userRating = ocrParsed.userRating,
                extractionSource = "ocr-route-pairs",
                isNotification = isNotification,
                text = text
            )
            return
        }

        // OCR tem pares km mas sem preço R$ — continua para PASSO 2 para buscar preço
        if (ocrParsed != null && ocrParsed.rideDistanceKm != null) {
            Log.d(TAG, "OCR: pares encontrados mas sem preço R$ — tentando regex para preço")
            // Continua para PASSO 2 mas usa dados do OCR como fallback
        }

        // ========== PASSO 2: Fallback para parsing por REGEX com desambiguação posicional ==========
        // Se já temos o preço do card específico, usar como prioridade
        if (cardPrice != null && cardPrice >= MIN_RIDE_PRICE) {
            val pricePosition = PRICE_PATTERN.find(text)?.range?.first ?: 0
            val disambiguated = disambiguateByPosition(text, pricePosition)

            val distance = ocrParsed?.rideDistanceKm
                ?: disambiguated.rideDistanceKm
                ?: parseRideDistanceFromText(text, pricePosition)
                ?: estimateDistance(cardPrice)

            val pickupKm = ocrParsed?.pickupDistanceKm ?: disambiguated.pickupDistanceKm

            // Rating opcional
            val headerRating = parseHeaderRating(text, appSource)

            Log.i(TAG, ">>> Card price parsing: preço=R$ $cardPrice, rideKm=$distance, pickupKm=${pickupKm ?: "?"}, rating=${headerRating ?: "?"}")

            buildAndEmitRideData(
                appSource = appSource,
                packageName = packageName,
                price = cardPrice,
                rideDistanceKm = distance,
                rideTimeMin = null,
                pickupDistanceKm = pickupKm,
                pickupTimeMin = null,
                userRating = headerRating,
                extractionSource = "${appSource.displayName.lowercase()}-card-pattern",
                isNotification = isNotification,
                text = text
            )
            return
        }

        // Se não tiver preço do card, continuar com regex genérico EXIGINDO símbolo R$
        val priceMatches = PRICE_PATTERN.findAll(text).toList()

        if (priceMatches.isEmpty()) {
            Log.d(TAG, "Nenhum preço encontrado no texto de $packageName")
            return
        }

        val selectedPriceMatch = selectRidePriceMatch(text, appSource, priceMatches) ?: run {
            Log.d(TAG, "Nenhum preço válido encontrado após filtro de média por km no texto de $packageName")
            return
        }
        val pricePosition = selectedPriceMatch.range.first

        val bestCandidate = selectBestOfferCandidate(text, priceMatches) ?: return
        val selectedPrice = parsePriceFromMatch(selectedPriceMatch) ?: bestCandidate.price
        val price = maxOf(bestCandidate.price, selectedPrice)

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

        val pickupKm = ocrParsed?.pickupDistanceKm
            ?: bestCandidate.pickupDistanceKm
            ?: disambiguated.pickupDistanceKm

        Log.i(TAG, ">>> Regex parsing: preço=R$ $price, rideKm=$distance, pickupKm=${pickupKm ?: "?"}")

        buildAndEmitRideData(
            appSource = appSource,
            packageName = packageName,
            price = price,
            rideDistanceKm = distance,
            rideTimeMin = null,
            pickupDistanceKm = pickupKm,
            pickupTimeMin = null,
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
        val normalizedPrice = normalizeSuspiciousPriceScale(
            price = price,
            rideDistanceKm = rideDistanceKm,
            text = text
        )

        val hasPriceToken = PRICE_PATTERN.containsMatchIn(text)
        val hasKmSignals = RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
            text = text,
            kmPattern = KM_VALUE_PATTERN,
            metersPattern = METERS_VALUE_PATTERN
        )
        if (normalizedPrice <= 0.0 || !hasPriceToken || !hasKmSignals) {
            Log.d(
                TAG,
                "Corrida ignorada por critério unificado (R$ + 2x km). " +
                    "price=$normalizedPrice, hasPriceToken=$hasPriceToken, hasKmSignals=$hasKmSignals"
            )
            return
        }

        val distance = rideDistanceKm ?: estimateDistance(normalizedPrice)
        val time = rideTimeMin ?: estimateTime(distance)

        // Extrair endereços antes da validação
        val addresses = extractAddresses(text)

        // ===== VALIDAÇÃO: endereços opcionais =====
        // Endereços são OPCIONAIS — muitas corridas válidas não expõem endereços
        // na árvore de acessibilidade (Uber usa React Native, 99 usa Canvas/Compose)
        if (addresses.first.isBlank() || addresses.second.isBlank()) {
            Log.d(TAG, "Endereço(s) não encontrado(s) (pickup='${addresses.first}', dropoff='${addresses.second}') — prosseguindo sem endereço")
        }

        // DEDUPLICAÇÃO
        val contentHash = "${appSource}_${normalizedPrice}_${extractionSource}_${distance}_${time}"
        val now = System.currentTimeMillis()
        if (contentHash == lastDetectedHash && now - lastDetectedTime < DUPLICATE_SUPPRESSION_WINDOW_MS) {
            Log.d(TAG, "Duplicado ignorado (${now - lastDetectedTime}ms): R$ $price")
            return
        }
        lastDetectedHash = contentHash

        val offerFingerprint = buildOfferFingerprint(
            appSource = appSource,
            packageName = packageName,
            normalizedPrice = normalizedPrice,
            rideDistanceKm = distance,
            pickupDistanceKm = pickupDistanceKm,
            pickupAddress = addresses.first,
            dropoffAddress = addresses.second
        )

        if (isRecentlySeenOffer(offerFingerprint, now)) {
            Log.i(TAG, "Oferta repetida ignorada (janela ${OFFER_FINGERPRINT_WINDOW_MS}ms): R$ $normalizedPrice")
            return
        }

        val rideData = RideData(
            appSource = appSource,
            ridePrice = normalizedPrice.toFloat(),
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
        Log.i(TAG, "  Preço: R$ $normalizedPrice")
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
                pendingRideData = null
                Log.w(
                    TAG,
                    "FloatingAnalyticsService inativo. Corrida não enviada; ativação manual é obrigatória."
                )
            }
        }
        pendingRunnable = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_DELAY)
        Log.d(TAG, "Corrida agendada para envio (debounce ${DEBOUNCE_DELAY}ms)")
    }

    private fun selectBestOfferCandidate(text: String, matches: List<MatchResult>): OfferCandidate? {
        val selected = RideInfoOfferCandidateSelector.selectBestOfferCandidate(
            text = text,
            matches = matches,
            parsePriceFromMatch = ::parsePriceFromMatch,
            parseFirstKmValue = ::parseFirstKmValue,
            parseFirstMinValue = ::parseFirstMinValue,
            parsePickupDistanceFromText = ::parsePickupDistanceFromText,
            parsePickupTimeFromText = ::parsePickupTimeFromText,
            parseUserRatingFromText = ::parseUserRatingFromText,
            actionKeywords = ACTION_KEYWORDS,
            contextKeywords = CONTEXT_KEYWORDS
        ) ?: return null

        if (selected.score < 3 && matches.size == 1) {
            val key = "offer-candidate-low-score"
            if (shouldLogDiagnostic(key)) {
                Log.d(TAG, "Oferta rejeitada por baixa pontuação contextual (score=${selected.score})")
            }
            return null
        }

        return OfferCandidate(
            price = selected.price,
            distanceKm = selected.distanceKm,
            estimatedTimeMin = selected.estimatedTimeMin,
            pickupDistanceKm = selected.pickupDistanceKm,
            pickupTimeMin = selected.pickupTimeMin,
            userRating = selected.userRating,
            score = selected.score
        )
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

        val parsed = RideInfoStructuredExtractionParser.parseStructuredExtraction(
            nodes = nodes.map { node ->
                StructuredNodeInput(
                    idSuffix = node.idSuffix,
                    combinedText = node.combinedText,
                    traversalIndex = node.traversalIndex
                )
            },
            minRidePrice = MIN_RIDE_PRICE,
            pricePattern = PRICE_PATTERN,
            fallbackPricePattern = FALLBACK_PRICE_PATTERN,
            distancePattern = DISTANCE_PATTERN,
            timePattern = TIME_PATTERN,
            minRangePattern = MIN_RANGE_PATTERN
        ) ?: return null

        Log.d(
            TAG,
            "Structured extraction: price=${parsed.price}, rideKm=${parsed.rideDistanceKm}, " +
                "rideMin=${parsed.rideTimeMin}, pickupKm=${parsed.pickupDistanceKm}, " +
                "pickupMin=${parsed.pickupTimeMin}, conf=${parsed.confidence}, nodes=${nodes.size}"
        )

        return StructuredExtraction(
            price = parsed.price,
            rideDistanceKm = parsed.rideDistanceKm,
            rideTimeMin = parsed.rideTimeMin,
            pickupDistanceKm = parsed.pickupDistanceKm,
            pickupTimeMin = parsed.pickupTimeMin,
            confidence = parsed.confidence,
            source = parsed.source
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao extrair nós semânticos: ${e.message}")
        }

        return result
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
        val extraction = RideInfoRouteParsers.parseOcrRoutePairs(
            text = text,
            minRidePrice = MIN_RIDE_PRICE,
            routePairPattern = ROUTE_PAIR_PATTERN,
            routePairMetersPattern = ROUTE_PAIR_METERS_PATTERN,
            kmInParenPattern = KM_IN_PAREN_PATTERN,
            metersInParenPattern = METERS_IN_PAREN_PATTERN,
            kmValuePattern = KM_VALUE_PATTERN,
            pricePattern = PRICE_PATTERN,
            userRatingPattern = USER_RATING_PATTERN,
            tag = TAG
        ) ?: return null

        return StructuredExtraction(
            price = extraction.price,
            rideDistanceKm = extraction.rideDistanceKm,
            rideTimeMin = null,
            pickupDistanceKm = extraction.pickupDistanceKm,
            pickupTimeMin = null,
            userRating = extraction.userRating,
            confidence = extraction.confidence,
            source = extraction.source
        )
    }

    private fun normalizeSuspiciousPriceScale(
        price: Double,
        rideDistanceKm: Double?,
        text: String
    ): Double {
        if (price < 100.0) return price

        val kmReference = rideDistanceKm
            ?: KM_IN_PAREN_PATTERN.findAll(text)
                .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
                .filter { it in 0.2..300.0 }
                .toList()
                .let { if (it.size >= 2) it[1] else it.lastOrNull() }

        if (kmReference == null || kmReference <= 0.0) return price

        val originalPricePerKm = price / kmReference
        if (originalPricePerKm <= 60.0) return price

        val candidates = listOf(price / 10.0, price / 100.0)
            .filter { it >= MIN_RIDE_PRICE }

        val corrected = candidates
            .map { candidate -> candidate to (candidate / kmReference) }
            .filter { (_, valuePerKm) -> valuePerKm in 0.6..35.0 }
            .minByOrNull { (_, valuePerKm) -> kotlin.math.abs(valuePerKm - 2.5) }
            ?.first

        if (corrected != null) {
            Log.w(
                TAG,
                "Preço ajustado por validação anti-escala OCR: original=R$ $price, corrigido=R$ $corrected, kmRef=$kmReference"
            )
            return corrected
        }

        return price
    }

    /**
     * Desambigua múltiplos valores km/min no texto usando posição relativa ao preço.
     * Valores ANTES do preço → pickup. Valores DEPOIS do preço → corrida.
     * Suporta formato Uber "X h YY (Z km)" para viagens longas.
     */
    private fun disambiguateByPosition(text: String, pricePosition: Int): StructuredExtraction {
        val extraction = RideInfoRouteParsers.disambiguateByPosition(
            text = text,
            pricePosition = pricePosition,
            uberHourRoutePattern = UBER_HOUR_ROUTE_PATTERN,
            distancePattern = DISTANCE_PATTERN,
            minRangePattern = MIN_RANGE_PATTERN,
            timePattern = TIME_PATTERN
        )

        return StructuredExtraction(
            price = null,
            rideDistanceKm = extraction.rideDistanceKm,
            rideTimeMin = extraction.rideTimeMin,
            pickupDistanceKm = extraction.pickupDistanceKm,
            pickupTimeMin = extraction.pickupTimeMin,
            confidence = extraction.confidence,
            source = extraction.source
        )
    }

    /**
     * Extrai distância da CORRIDA (não pickup) do texto, priorizando valores após o preço.
     * Suporta formato Uber "X h YY (Z km)" para viagens longas.
     */
    private fun parseRideDistanceFromText(text: String, pricePosition: Int): Double? {
        return RideInfoTextParsers.parseRideDistanceFromText(
            text = text,
            pricePosition = pricePosition,
            uberHourRoutePattern = UBER_HOUR_ROUTE_PATTERN,
            distancePattern = DISTANCE_PATTERN
        )
    }

    /**
     * Extrai tempo da CORRIDA (não pickup) do texto, priorizando valores após o preço.
     * Suporta formato "X h YY (Z km)" da Uber para viagens longas.
     */
    private fun parseRideTimeFromText(text: String, pricePosition: Int): Int? {
        return RideInfoTextParsers.parseRideTimeFromText(
            text = text,
            pricePosition = pricePosition,
            uberHourRoutePattern = UBER_HOUR_ROUTE_PATTERN,
            minRangePattern = MIN_RANGE_PATTERN,
            timePattern = TIME_PATTERN
        )
    }

    /**
     * Extrai o PRIMEIRO valor em km de um fragmento de texto.
     */
    private fun parseFirstKmValue(text: String): Double? {
        return RideInfoTextParsers.parseFirstKmValue(text, DISTANCE_PATTERN)
    }

    /**
     * Extrai o PRIMEIRO valor em min de um fragmento de texto.
     * Inclui suporte a ranges como "1-11 min" (retorna o máximo).
     */
    private fun parseFirstMinValue(text: String): Int? {
        return RideInfoTextParsers.parseFirstMinValue(
            text = text,
            minRangePattern = MIN_RANGE_PATTERN,
            timePattern = TIME_PATTERN
        )
    }

    private fun parseDistanceFromText(text: String): Double? {
        return RideInfoTextParsers.parseDistanceFromText(text, DISTANCE_PATTERN)
    }

    private fun parseMinutesFromText(text: String): Int? {
        return RideInfoTextParsers.parseMinutesFromText(
            text = text,
            minRangePattern = MIN_RANGE_PATTERN,
            minValuePattern = MIN_VALUE_PATTERN
        )
    }

    private fun parsePickupDistanceFromText(text: String): Double? {
        return RideInfoTextParsers.parsePickupDistanceFromText(
            text = text,
            pickupDistancePattern = PICKUP_DISTANCE_PATTERN,
            pickupInlinePattern = PICKUP_INLINE_PATTERN,
            pricePattern = PRICE_PATTERN
        )
    }

    private fun parsePickupTimeFromText(text: String): Int? {
        return RideInfoTextParsers.parsePickupTimeFromText(
            text = text,
            pickupTimePattern = PICKUP_TIME_PATTERN,
            pickupInlinePattern = PICKUP_INLINE_PATTERN,
            pricePattern = PRICE_PATTERN
        )
    }

    private fun parseUserRatingFromText(text: String): Double? {
        return RideInfoTextParsers.parseUserRatingFromText(text, USER_RATING_PATTERN)
    }

    private fun requestOcrFallbackForOffer(packageName: String, triggerReason: String): Boolean {
        if (!OCR_FALLBACK_ENABLED) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val monitoredWindowVisible = hasVisibleMonitoredWindow()
        if (!monitoredWindowVisible && !triggerReason.startsWith("empty-tree")) {
            Log.d(TAG, "OCR ignorado: nenhuma janela Uber/99 visível (trigger=$triggerReason, eventPkg=$packageName)")
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

                            val appSource = detectAppSource(packageName)
                            val cropStartFraction = if (appSource == AppSource.NINETY_NINE) {
                                OCR_BOTTOM_HALF_START_FRACTION_99
                            } else {
                                OCR_BOTTOM_HALF_START_FRACTION
                            }
                            val useBlackAndWhite = appSource != AppSource.NINETY_NINE

                            RideOcrFallbackProcessor.processBitmap(
                                bitmap = bitmap,
                                packageName = packageName,
                                triggerReason = triggerReason,
                                tag = TAG,
                                bottomHalfStartFraction = cropStartFraction,
                                useBlackAndWhite = useBlackAndWhite,
                                pricePattern = PRICE_PATTERN,
                                sanitizeText = { raw ->
                                    RideOcrTextProcessing.sanitizeTextForRideParsing(
                                        text = raw,
                                        ownCardNoiseTokens = OWN_CARD_NOISE_TOKENS
                                    )
                                },
                                hasStrongRideSignal = ::hasStrongRideSignal,
                                hasAtLeastTwoKmSignals = { raw ->
                                    RideOcrTextProcessing.hasAtLeastTwoDistanceSignals(
                                        text = raw,
                                        kmPattern = KM_VALUE_PATTERN,
                                        metersPattern = METERS_VALUE_PATTERN
                                    )
                                },
                                onStrongSignal = { ocrText ->
                                    processCandidateText(
                                        finalText = ocrText,
                                        packageName = packageName,
                                        isStateChange = false,
                                        rootPackage = "ocr-fallback",
                                        allowOcrFallback = false
                                    )
                                }
                            )
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
            val allWindows = windows
            val windowsText = extractTextFromInteractiveWindows(packageName)
            val nodeOfferText = extractOfferTextFromNodes(packageName)
            val relevantNodes = RideOcrAutoDebugDumpHelper.buildRelevantNodesSnapshot(
                allWindows = allWindows,
                expectedPackage = packageName,
                maxNodes = 60,
                ownPackage = OWN_PACKAGE,
                detectAppSource = ::detectAppSource,
                pricePattern = PRICE_PATTERN,
                fallbackPricePattern = FALLBACK_PRICE_PATTERN,
                kmValuePattern = KM_VALUE_PATTERN,
                minValuePattern = MIN_VALUE_PATTERN,
                minRangePattern = MIN_RANGE_PATTERN
            )
            val windowInventory = RideOcrAutoDebugDumpHelper.buildWindowInventory(allWindows)
            val allNodesSnapshot = RideOcrAutoDebugDumpHelper.buildAllNodesSnapshot(
                allWindows = allWindows,
                maxNodes = 100,
                ownPackage = OWN_PACKAGE
            )

            val file = RideOcrAutoDebugDumpHelper.writeAutoDebugDump(
                filesDir = filesDir,
                now = now,
                reason = reason,
                packageName = packageName,
                event = event,
                eventText = eventText,
                sourceText = sourceText,
                windowsText = windowsText,
                nodeOfferText = nodeOfferText,
                relevantNodes = relevantNodes,
                windowInventory = windowInventory,
                allNodesSnapshot = allNodesSnapshot,
                autoDebugMaxChars = AUTO_DEBUG_MAX_CHARS,
                detectAppSource = ::detectAppSource
            )

            Log.i(TAG, "AUTO_DEBUG_DUMP salvo: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar AUTO_DEBUG_DUMP: ${e.message}")
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
        return RideOcrAddressExtractor.extractAddresses(
            text = text,
            routePairPattern = ROUTE_PAIR_PATTERN
        )
    }
}
