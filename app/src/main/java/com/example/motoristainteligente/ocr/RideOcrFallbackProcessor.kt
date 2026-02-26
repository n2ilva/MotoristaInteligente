package com.example.motoristainteligente

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object RideOcrFallbackProcessor {

    fun processBitmap(
        bitmap: Bitmap,
        packageName: String,
        triggerReason: String,
        tag: String,
        bottomHalfStartFraction: Double,
        pricePattern: Regex,
        sanitizeText: (String) -> String,
        hasStrongRideSignal: (String) -> Boolean,
        hasAtLeastTwoKmSignals: (String) -> Boolean,
        onStrongSignal: (String) -> Unit
    ) {
        try {
            val normalizedStart = bottomHalfStartFraction.coerceIn(0.0, 0.95)
            val lowerRegionBitmap = cropBottomRegion(bitmap, normalizedStart)
            val blackAndWhiteBitmap = toBlackAndWhite(lowerRegionBitmap)

            val inputImage = InputImage.fromBitmap(blackAndWhiteBitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawOcrText = extractBottomHalfOcrText(
                        visionText = visionText,
                        imageHeight = blackAndWhiteBitmap.height,
                        bottomHalfStartFraction = 0.0
                    )
                    val ocrText = sanitizeText(rawOcrText)
                    if (ocrText.isBlank()) {
                        Log.i(tag, "OCR fallback: texto vazio (trigger=$triggerReason)")
                        return@addOnSuccessListener
                    }

                    Log.i(
                        tag,
                        "OCR capturou ${ocrText.length} chars (trigger=$triggerReason): ${ocrText.take(250).replace('\n', ' ')}"
                    )

                    if (hasStrongRideSignal(ocrText)) {
                        Log.i(tag, "OCR encontrou sinal forte de corrida (${ocrText.length} chars), trigger=$triggerReason")
                        onStrongSignal(ocrText)
                    } else {
                        Log.i(
                            tag,
                            "OCR sem sinal forte (trigger=$triggerReason) — hasPrice=${pricePattern.containsMatchIn(ocrText)}, hasTwoKmValues=${hasAtLeastTwoKmSignals(ocrText)}"
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(tag, "OCR fallback falhou (trigger=$triggerReason): ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(tag, "OCR fallback erro no processamento (trigger=$triggerReason): ${e.message}")
        }
    }

    private fun cropBottomRegion(bitmap: Bitmap, startFraction: Double): Bitmap {
        val yStart = (bitmap.height * startFraction).toInt().coerceIn(0, bitmap.height - 1)
        val cropHeight = (bitmap.height - yStart).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, 0, yStart, bitmap.width, cropHeight)
    }

    private fun toBlackAndWhite(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var luminanceSum = 0L
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val gray = ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
            luminanceSum += gray
        }

        val averageLuminance = (luminanceSum / pixels.size).toInt()
        val threshold = (averageLuminance + 10).coerceIn(90, 180)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = Color.alpha(pixel)
            val gray = ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
            val bw = if (gray >= threshold) 255 else 0
            pixels[i] = Color.argb(alpha, bw, bw, bw)
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
