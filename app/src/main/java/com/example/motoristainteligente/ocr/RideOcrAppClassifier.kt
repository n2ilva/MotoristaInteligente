package com.example.motoristainteligente

object RideOcrAppClassifier {

    private val UBER_CARD_MARKERS = listOf(
        Regex("""\buberx\b""", RegexOption.IGNORE_CASE),
        Regex("""\buber\s*comfort\b""", RegexOption.IGNORE_CASE),
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

    fun isMonitoredPackage(
        packageName: String,
        ownPackage: String,
        allMonitored: Set<String>
    ): Boolean {
        if (packageName == ownPackage || packageName.startsWith("$ownPackage.")) return false
        if (packageName in allMonitored) return true
        if (allMonitored.any { packageName.startsWith(it) }) return true

        val lower = packageName.lowercase()
        val looksLikeUber = lower.contains("uber")
        val looksLikeNineNine = lower.contains("99") || lower.contains("ninenine")
        val looksLikeDriverApp = lower.contains("driver") || lower.contains("taxi") || lower.contains("motorista")

        return (looksLikeUber || looksLikeNineNine) && looksLikeDriverApp
    }

    fun detectAppSource(
        packageName: String,
        uberPackages: Set<String>,
        ninetyNinePackages: Set<String>
    ): AppSource {
        val lower = packageName.lowercase()

        if (packageName in uberPackages || uberPackages.any { packageName.startsWith(it) } || lower.contains("uber")) {
            return AppSource.UBER
        }
        if (packageName in ninetyNinePackages ||
            ninetyNinePackages.any { packageName.startsWith(it) } ||
            lower.contains("99") ||
            lower.contains("ninenine")
        ) {
            return AppSource.NINETY_NINE
        }
        return AppSource.UNKNOWN
    }

    fun detectAppSourceFromScreenText(text: String): AppSource {
        if (text.isBlank()) return AppSource.UNKNOWN

        val normalized = text.lowercase()

        return if (UBER_CARD_MARKERS.any { it.containsMatchIn(normalized) }) {
            AppSource.UBER
        } else if (NINETY_NINE_CARD_MARKERS.any { it.containsMatchIn(normalized) }) {
            AppSource.NINETY_NINE
        } else {
            AppSource.UNKNOWN
        }
    }
}
