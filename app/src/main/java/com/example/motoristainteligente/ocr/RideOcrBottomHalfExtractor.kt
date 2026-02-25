package com.example.motoristainteligente

import com.google.mlkit.vision.text.Text

fun extractBottomHalfOcrText(
    visionText: Text,
    imageHeight: Int,
    bottomHalfStartFraction: Double
): String {
    if (imageHeight <= 0) return visionText.text.orEmpty().trim()

    val yThreshold = (imageHeight * bottomHalfStartFraction).toInt()
    val lines = visionText.textBlocks
        .filter { block ->
            val bounds = block.boundingBox ?: return@filter false
            bounds.centerY() >= yThreshold
        }
        .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        .flatMap { block ->
            block.lines
                .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
                .map { line -> line.text.orEmpty().trim() }
        }
        .filter { it.isNotBlank() }

    return lines.joinToString("\n").trim()
}
