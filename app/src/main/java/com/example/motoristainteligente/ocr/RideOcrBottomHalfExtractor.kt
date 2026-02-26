package com.example.motoristainteligente

import com.google.mlkit.vision.text.Text

fun extractBottomHalfOcrText(
    visionText: Text,
    imageHeight: Int,
    bottomHalfStartFraction: Double
): String {
    if (imageHeight <= 0) return visionText.text.orEmpty().trim()

    val yThreshold = (imageHeight * bottomHalfStartFraction.coerceIn(0.0, 0.95)).toInt()

    val lines = visionText.textBlocks
        .flatMap { block -> block.lines }
        .filter { line ->
            val bounds = line.boundingBox ?: return@filter false
            bounds.centerY() >= yThreshold
        }
        .sortedWith(
            compareBy<Text.Line> { it.boundingBox?.top ?: Int.MAX_VALUE }
                .thenBy { it.boundingBox?.left ?: Int.MAX_VALUE }
        )
        .map { line -> line.text.orEmpty().trim() }
        .filter { it.isNotBlank() }

    return lines.joinToString("\n").trim()
}
