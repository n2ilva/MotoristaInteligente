package com.example.motoristainteligente

object RideOcrAppClassifier {

    private val UBERX_PATTERN = Regex("""\buberx\b""", RegexOption.IGNORE_CASE)

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
        return if (UBERX_PATTERN.containsMatchIn(text)) {
            AppSource.UBER
        } else {
            AppSource.NINETY_NINE
        }
    }
}
