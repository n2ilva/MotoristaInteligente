package com.example.motoristainteligente

object RideOcrAddressExtractor {

    private val pickupPatterns = listOf(
        Regex("""(?:embarque|buscar|retirada|origem|local\s+de\s+embarque)[:\s]+([^,\n]{3,60})""", RegexOption.IGNORE_CASE)
    )
    private val dropoffPatterns = listOf(
        Regex("""(?:destino|para|até|entrega|deixar|local\s+de\s+destino)[:\s]+([^,\n]{3,60})""", RegexOption.IGNORE_CASE)
    )
    private val addressPattern = Regex(
        """(?:R(?:ua)?\.?|Av(?:enida)?\.?|M(?:arginal)?\.?)\s+[A-ZÀ-Ú0-9][^\n]{3,60}""",
        RegexOption.IGNORE_CASE
    )
    private val roadPattern = Regex(
        """(?:R(?:ua)?\.?|Av(?:enida)?\.?|M(?:arginal)?\.?)\s+[A-ZÀ-Ú0-9][^•|]{3,120}""",
        RegexOption.IGNORE_CASE
    )

    fun extractAddresses(text: String, routePairPattern: Regex): Pair<String, String> {
        val routePairs = routePairPattern.findAll(text).toList()
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

        var pickup = ""
        var dropoff = ""

        for (pattern in pickupPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = sanitizeAddressCandidate(match.groupValues[1].trim())
                pickup = candidate.takeIf { isValidAddressPrefix(it) }.orEmpty()
                break
            }
        }

        for (pattern in dropoffPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = sanitizeAddressCandidate(match.groupValues[1].trim())
                dropoff = candidate.takeIf { isValidAddressPrefix(it) }.orEmpty()
                break
            }
        }

        if (pickup.isNotBlank() && dropoff.isNotBlank()) {
            return Pair(pickup, dropoff)
        }

        val addressMatches = addressPattern.findAll(text).map { it.value.trim() }.toList()
        if (addressMatches.size >= 2) {
            return Pair(addressMatches[0], addressMatches[1])
        } else if (addressMatches.size == 1) {
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
            .filter { isValidAddressPrefix(it) }
            .filterNot { isLikelyNoiseAddressLine(it) }

        if (candidateLines.isNotEmpty()) {
            val bestLine = candidateLines.maxByOrNull { line ->
                val roadScore = if (isValidAddressPrefix(line)) 3 else 0
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

        val roadMatch = roadPattern.find(normalized)?.value?.trim().orEmpty()
        if (roadMatch.isNotBlank()) {
            return sanitizeAddressCandidate(roadMatch)
        }

        val generic = normalized
            .replace(
                Regex("""(?i)\b(aceitar|recusar|ignorar|corrida longa|perfil essencial|perfil premium|taxa de deslocamento|parada\(s\)|parada)\b.*"""),
                ""
            )
            .trim()

        if (generic.length < 6) return ""
        if (!generic.any { it.isLetter() }) return ""
        if (Regex("""^\d+[\d,\.\s]*$""").matches(generic)) return ""
        if (!isValidAddressPrefix(generic)) return ""

        return sanitizeAddressCandidate(generic)
    }

    private fun isValidAddressPrefix(value: String): Boolean {
        val trimmed = value.trimStart()
        return Regex("""^(?i)(R(?:ua)?\.?|Av(?:enida)?\.?|M(?:arginal)?\.?)\s+.+""").containsMatchIn(trimmed)
    }

    private fun sanitizeAddressCandidate(input: String): String {
        return input
            .replace(
                Regex("""(?i)\b(aceitar|recusar|ignorar|corrida longa|perfil essencial|perfil premium|taxa de deslocamento|parada\(s\)|parada)\b.*"""),
                ""
            )
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
