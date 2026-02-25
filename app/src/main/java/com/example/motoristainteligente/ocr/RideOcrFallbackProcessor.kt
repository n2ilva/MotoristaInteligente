package com.example.motoristainteligente

import android.graphics.Bitmap
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
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawOcrText = extractBottomHalfOcrText(
                        visionText = visionText,
                        imageHeight = bitmap.height,
                        bottomHalfStartFraction = bottomHalfStartFraction
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
}
