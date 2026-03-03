package com.example.motoristainteligente

object RideOcrAppClassifier {

    private fun matchesAllowedPackage(packageName: String, allowedPackages: Set<String>): Boolean {
        return packageName in allowedPackages || allowedPackages.any { packageName.startsWith("$it.") }
    }

    private val UBER_CARD_MARKERS = listOf(
        Regex("""\buberx\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*comfort\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcomfort\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*black\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*flash\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*connect\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*moto\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*green\b""", RegexOption.IGNORE_CASE)
    )

    private val NINETY_NINE_CARD_MARKERS = listOf(
        Regex("""\bcorrida\s+longa\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnegocia\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpriorit[áa]rio\b""", RegexOption.IGNORE_CASE),
        Regex("""\baceitar\s+por\s+r\$\b""", RegexOption.IGNORE_CASE),
        Regex("""\btaxa\s+de\s+deslocamento\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpre[cç]o\s*x\s*\d+(?:[\.,]\d+)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\br\$\s*\d{1,3}(?:[\.,]\d{1,2})?\s*/\s*km\b""", RegexOption.IGNORE_CASE),
        Regex("""\bn[aã]o\s+afeta\s+a\s*ta\b""", RegexOption.IGNORE_CASE),
        Regex("""\bperfil\s+premium\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d+[\.,]?\d*\s*[·\.]\s*\+?\d+\s*corridas\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\+?\d+\s*corridas\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpop\s+expresso\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcart[aã]o\s+verif\b""", RegexOption.IGNORE_CASE)
    )

    private val UBER_TOP_LINE_MARKERS = listOf(
        Regex("""\buberx\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcomfort\b""", RegexOption.IGNORE_CASE)
    )

    private val UBER_DESTINATION_LINE_MARKER = Regex(
        """^\s*viagem\b""",
        RegexOption.IGNORE_CASE
    )

    private val NINETY_NINE_EXPLICIT_MARKERS = listOf(
        Regex("""\bpriorit[áa]rio\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcorrida\s+longa\b""", RegexOption.IGNORE_CASE),
        Regex("""\btaxa\s+de\s+deslocamento\b""", RegexOption.IGNORE_CASE),
        Regex("""\bperfil\s+premium\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnegocia\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpop\s+expresso\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcart[aã]o\s+verif\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d+\s*corridas\b""", RegexOption.IGNORE_CASE)
    )

    fun isMonitoredPackage(
        packageName: String,
        ownPackage: String,
        allMonitored: Set<String>
    ): Boolean {
        if (packageName == ownPackage || packageName.startsWith("$ownPackage.")) return false
        return matchesAllowedPackage(packageName, allMonitored)
    }

    fun detectAppSource(
        packageName: String,
        uberPackages: Set<String>,
        ninetyNinePackages: Set<String>
    ): AppSource {
        if (matchesAllowedPackage(packageName, uberPackages)) {
            return AppSource.UBER
        }
        if (matchesAllowedPackage(packageName, ninetyNinePackages)) {
            return AppSource.NINETY_NINE
        }
        return AppSource.UNKNOWN
    }

    /**
     * Verifica se o texto contém marcadores de AMBOS os apps (Uber E 99).
     * Isso indica contaminação cruzada quando ambos os apps mostram ofertas simultaneamente.
     */
    fun hasBothAppMarkers(text: String): Boolean {
        val normalized = text.lowercase()
        val hasUber = UBER_CARD_MARKERS.any { it.containsMatchIn(normalized) }
        val has99 = NINETY_NINE_CARD_MARKERS.any { it.containsMatchIn(normalized) }
        return hasUber && has99
    }

    /**
     * Quando o texto contém marcadores de ambos os apps (Uber + 99), filtra para
     * manter somente o conteúdo relevante ao app primário (determinado pelo packageName).
     *
     * Estratégia: localiza os limites do conteúdo de cada app usando seus marcadores
     * e mantém apenas a seção pertencente ao app primário.
     */
    fun filterTextForSingleApp(text: String, primaryApp: AppSource): String {
        if (primaryApp == AppSource.UNKNOWN) return text

        val lines = text.lines()
        if (lines.isEmpty()) return text

        val primaryMarkers = when (primaryApp) {
            AppSource.UBER -> UBER_CARD_MARKERS
            AppSource.NINETY_NINE -> NINETY_NINE_CARD_MARKERS
            else -> return text
        }
        val otherMarkers = when (primaryApp) {
            AppSource.UBER -> NINETY_NINE_CARD_MARKERS
            AppSource.NINETY_NINE -> UBER_CARD_MARKERS
            else -> return text
        }

        // Encontrar índices das linhas com marcadores de cada app
        val otherMarkerLines = lines.indices.filter { idx ->
            otherMarkers.any { it.containsMatchIn(lines[idx]) }
        }
        if (otherMarkerLines.isEmpty()) return text

        val primaryMarkerLines = lines.indices.filter { idx ->
            primaryMarkers.any { it.containsMatchIn(lines[idx]) }
        }
        // Se não encontrou marcadores do app primário, não conseguimos isolar
        if (primaryMarkerLines.isEmpty()) return ""

        val firstOther = otherMarkerLines.first()
        val firstPrimary = primaryMarkerLines.first()

        return if (firstPrimary <= firstOther) {
            // Conteúdo do app primário vem primeiro: manter do início até a linha do outro app
            lines.subList(0, firstOther).joinToString("\n").trim()
        } else {
            // Conteúdo do outro app vem primeiro: manter a partir do app primário
            // Incluir algumas linhas antes do marcador primário (podem ser preço/rating)
            val startIdx = (firstPrimary - 2).coerceAtLeast(firstOther + 1)
                .coerceAtLeast(0)
            lines.subList(startIdx, lines.size).joinToString("\n").trim()
        }
    }

    fun detectAppSourceFromScreenText(text: String): AppSource {
        if (text.isBlank()) return AppSource.UNKNOWN

        val orderedLines = text
            .lines()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }

        val topLines = orderedLines.take(3)

        val hasUberTopMarker = topLines.any { line ->
            UBER_TOP_LINE_MARKERS.any { marker -> marker.containsMatchIn(line) }
        }
        val hasUberDestinationMarker = orderedLines.any { line ->
            UBER_DESTINATION_LINE_MARKER.containsMatchIn(line)
        }

        if (hasUberTopMarker && hasUberDestinationMarker) {
            return AppSource.UBER
        }

        val hasExplicit99ByLines = orderedLines.any { line ->
            NINETY_NINE_EXPLICIT_MARKERS.any { marker -> marker.containsMatchIn(line) }
        }
        if (hasExplicit99ByLines) {
            return AppSource.NINETY_NINE
        }

        val normalized = orderedLines.joinToString("\n").lowercase()

        return if (UBER_CARD_MARKERS.any { it.containsMatchIn(normalized) }) {
            AppSource.UBER
        } else if (NINETY_NINE_CARD_MARKERS.any { it.containsMatchIn(normalized) }) {
            AppSource.NINETY_NINE
        } else {
            AppSource.UNKNOWN
        }
    }
}
