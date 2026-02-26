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
        Regex("""\b\+?\d+\s*corridas\b""", RegexOption.IGNORE_CASE)
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
        Regex("""\bperfil\s+premium\b""", RegexOption.IGNORE_CASE)
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
