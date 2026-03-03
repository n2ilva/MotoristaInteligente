package com.example.motoristainteligente

object RideOcrTextProcessing {

    fun hasAtLeastTwoDistanceSignals(text: String, kmPattern: Regex, metersPattern: Regex): Boolean {
        val kmCount = kmPattern.findAll(text).count()
        val metersCount = metersPattern.findAll(text).count()
        return (kmCount + metersCount) >= 2
    }

    fun sanitizeTextForRideParsing(text: String, ownCardNoiseTokens: List<String>): String {
        if (text.isBlank()) return ""

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val cleanedLines = lines.filterNot { line ->
            val lower = line.lowercase()
            ownCardNoiseTokens.any { lower.contains(it) }
        }

        val cleaned = if (cleanedLines.isNotEmpty()) cleanedLines.joinToString("\n") else text
        val withoutDirectionIcons = cleaned
            .replace("↑", " ")
            .replace("↗", " ")
            .replace("↖", " ")
            .replace("⇧", " ")
            .replace("⤴", " ")
            .replace("🡅", " ")
            .replace("🔺", " ")
            .replace("🟡", " ")
            .replace("↓", " ")
            .replace("↘", " ")
            .replace("↙", " ")
            .replace("⤵", " ")

        return withoutDirectionIcons
            .lines()
            .map { line -> line.replace(Regex("""[\t\x0B\f\r ]+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }
}
