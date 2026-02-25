package com.example.motoristainteligente

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RideOcrAutoDebugDumpHelper {

    fun buildRelevantNodesSnapshot(
        allWindows: List<AccessibilityWindowInfo>?,
        expectedPackage: String,
        maxNodes: Int,
        ownPackage: String,
        detectAppSource: (String) -> AppSource,
        pricePattern: Regex,
        fallbackPricePattern: Regex,
        kmValuePattern: Regex,
        minValuePattern: Regex,
        minRangePattern: Regex
    ): String {
        return try {
            allWindows ?: return ""
            val expectedSource = detectAppSource(expectedPackage)
            val lines = mutableListOf<String>()

            fun collect(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 10 || lines.size >= maxNodes) return

                val rid = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val merged = listOf(text, desc).filter { it.isNotBlank() }.joinToString(" | ")

                val looksUseful =
                    merged.isNotBlank() &&
                        (
                            pricePattern.containsMatchIn(merged) ||
                                fallbackPricePattern.containsMatchIn(merged) ||
                                kmValuePattern.containsMatchIn(merged) ||
                                minValuePattern.containsMatchIn(merged) ||
                                minRangePattern.containsMatchIn(merged) ||
                                merged.contains("R$", ignoreCase = true) ||
                                merged.contains("km", ignoreCase = true) ||
                                merged.contains("min", ignoreCase = true) ||
                                merged.contains("aceitar", ignoreCase = true) ||
                                merged.contains("corrida", ignoreCase = true) ||
                                merged.contains("99", ignoreCase = true) ||
                                merged.contains("viagem", ignoreCase = true) ||
                                merged.contains("destino", ignoreCase = true)
                            )

                if (looksUseful) {
                    lines.add("d=$depth rid=$rid class=${node.className ?: ""} text=$text desc=$desc")
                }

                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (_: Exception) {
                        null
                    }
                    collect(child, depth + 1)
                    if (lines.size >= maxNodes) break
                }
            }

            for (window in allWindows) {
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                } ?: continue

                val rootPkg = root.packageName?.toString().orEmpty()
                val rootSource = detectAppSource(rootPkg)
                val shouldUseWindow =
                    rootPkg.isNotBlank() &&
                        rootPkg != ownPackage &&
                        (
                            rootPkg == expectedPackage ||
                                rootPkg.startsWith(expectedPackage) ||
                                expectedPackage.startsWith(rootPkg) ||
                                (expectedSource != AppSource.UNKNOWN && rootSource == expectedSource)
                            )

                if (shouldUseWindow) {
                    collect(root, 0)
                }
                if (lines.size >= maxNodes) break
            }

            lines.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    fun buildWindowInventory(allWindows: List<AccessibilityWindowInfo>?): String {
        return try {
            allWindows ?: return "(windows null)"
            val lines = mutableListOf<String>()
            for ((idx, window) in allWindows.withIndex()) {
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                }
                val rootPkg = root?.packageName?.toString() ?: "(null)"
                val childCount = root?.childCount ?: 0
                val rootText = root?.text?.toString()?.take(50) ?: ""
                val rootDesc = root?.contentDescription?.toString()?.take(50) ?: ""
                val windowType = try {
                    window.type
                } catch (_: Exception) {
                    -1
                }
                val windowLayer = try {
                    window.layer
                } catch (_: Exception) {
                    -1
                }
                lines.add("win[$idx] pkg=$rootPkg type=$windowType layer=$windowLayer children=$childCount text=$rootText desc=$rootDesc")
            }
            if (lines.isEmpty()) "(nenhuma janela)" else lines.joinToString("\n")
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    fun buildAllNodesSnapshot(
        allWindows: List<AccessibilityWindowInfo>?,
        maxNodes: Int,
        ownPackage: String
    ): String {
        return try {
            allWindows ?: return ""
            val lines = mutableListOf<String>()

            fun collect(node: AccessibilityNodeInfo?, depth: Int, windowPkg: String) {
                if (node == null || depth > 12 || lines.size >= maxNodes) return

                val rid = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val cls = node.className?.toString().orEmpty()

                if (text.isNotBlank() || desc.isNotBlank()) {
                    lines.add("d=$depth pkg=$windowPkg rid=$rid cls=$cls t=${text.take(80)} cd=${desc.take(80)}")
                }

                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (_: Exception) {
                        null
                    }
                    collect(child, depth + 1, windowPkg)
                    if (lines.size >= maxNodes) break
                }
            }

            for (window in allWindows) {
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                } ?: continue

                val rootPkg = root.packageName?.toString().orEmpty()
                if (rootPkg != ownPackage) {
                    collect(root, 0, rootPkg)
                }
                if (lines.size >= maxNodes) break
            }

            if (lines.isEmpty()) "(nenhum nó com texto)" else lines.joinToString("\n")
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    fun writeAutoDebugDump(
        filesDir: File,
        now: Long,
        reason: String,
        packageName: String,
        event: AccessibilityEvent?,
        eventText: String,
        sourceText: String,
        windowsText: String,
        nodeOfferText: String,
        relevantNodes: String,
        windowInventory: String,
        allNodesSnapshot: String,
        autoDebugMaxChars: Int,
        detectAppSource: (String) -> AppSource
    ): File {
        val dumpDir = File(filesDir, "debug_dumps")
        if (!dumpDir.exists()) dumpDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val appTag = when (detectAppSource(packageName)) {
            AppSource.UBER -> "uber"
            AppSource.NINETY_NINE -> "99"
            else -> "unknown"
        }
        val file = File(dumpDir, "${appTag}_${reason}_$timestamp.txt")

        val eventType = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "N/A"
        val contentChange = event?.contentChangeTypes ?: 0

        val payload = buildString {
            appendLine("timestamp=$timestamp")
            appendLine("reason=$reason")
            appendLine("package=$packageName")
            appendLine("eventType=$eventType")
            appendLine("contentChangeTypes=$contentChange")
            appendLine()
            appendLine("[eventText]")
            appendLine(eventText.take(autoDebugMaxChars))
            appendLine()
            appendLine("[sourceText]")
            appendLine(sourceText.take(autoDebugMaxChars))
            appendLine()
            appendLine("[nodeOfferText]")
            appendLine(nodeOfferText.take(autoDebugMaxChars))
            appendLine()
            appendLine("[windowsText]")
            appendLine(windowsText.take(autoDebugMaxChars))
            appendLine()
            appendLine("[relevantNodes]")
            appendLine(relevantNodes)
            appendLine()
            appendLine("[windowInventory]")
            appendLine(windowInventory)
            appendLine()
            appendLine("[allNodes]")
            appendLine(allNodesSnapshot)
        }

        file.writeText(payload)
        return file
    }
}
