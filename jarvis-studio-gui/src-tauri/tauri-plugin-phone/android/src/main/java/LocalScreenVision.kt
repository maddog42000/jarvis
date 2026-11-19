package com.jarvis.phone

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Normalized screen rectangle. Each coordinate is in 0..1000 screenshot units. */
internal data class VisionBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** Pure policy helpers so privacy and coordinate behavior can be JVM-tested. */
internal object ScreenVisionPolicy {
    private val sensitiveMarker = Regex(
        "(?i)\\b(password|passcode|pass code|pin|otp|one[- ]time code|verification code|" +
            "security code|recovery code|backup code|authentication code|login code|cvv|cvc|" +
            "api key|access token|refresh token|private key|bearer token|authorization|secret)\\b"
    )
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val markerOnly = Regex(
        "(?i)\\s*(password|passcode|pass code|pin|otp|one[- ]time code|verification code|" +
            "security code|recovery code|backup code|authentication code|login code|cvv|cvc|" +
            "api key|access token|refresh token|private key|bearer token|authorization|secret)\\s*:?\\s*"
    )

    fun normalizeBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): VisionBox {
        if (imageWidth <= 0 || imageHeight <= 0) return VisionBox(0, 0, 0, 0)
        val l = left.coerceIn(0, imageWidth)
        val t = top.coerceIn(0, imageHeight)
        val r = right.coerceIn(l, imageWidth)
        val b = bottom.coerceIn(t, imageHeight)
        val x = (l * 1000f / imageWidth).toInt().coerceIn(0, 1000)
        val y = (t * 1000f / imageHeight).toInt().coerceIn(0, 1000)
        val w = ((r - l) * 1000f / imageWidth).toInt().coerceIn(0, 1000 - x)
        val h = ((b - t) * 1000f / imageHeight).toInt().coerceIn(0, 1000 - y)
        return VisionBox(x, y, w, h)
    }

    /** Returns null for empty lines; masks credential labels and associated values. */
    fun sanitizeLine(raw: String): String? {
        val clean = raw.replace(Regex("\\s+"), " ").trim().take(240)
        if (clean.isEmpty()) return null
        if (sensitiveMarker.containsMatchIn(clean)) {
            val marker = sensitiveMarker.find(clean)?.value ?: "sensitive field"
            return marker.lowercase() + ": [REDACTED]"
        }
        // Email addresses are not useful to screen navigation and can identify accounts.
        return email.replace(clean, "[REDACTED EMAIL]")
    }

    fun formatLines(lines: List<Pair<VisionBox, String>>, maxLines: Int = 80, maxChars: Int = 4_000): String {
        val out = StringBuilder()
        var shown = 0
        var suppressNext = false
        for ((box, raw) in lines) {
            val clean = raw.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) continue
            if (suppressNext) {
                suppressNext = false
                continue
            }
            val safe = sanitizeLine(clean) ?: continue
            if (markerOnly.matches(clean)) suppressNext = true
            val escaped = safe.replace("\\", "\\\\").replace("\"", "\\\"")
            val row = "- [x=" + box.x + " y=" + box.y + " w=" + box.w + " h=" + box.h + "] \"" + escaped + "\"\n"
            if (shown >= maxLines || out.length + row.length > maxChars) break
            out.append(row)
            shown++
        }
        return out.toString().trimEnd()
    }
}

/**
 * On-device OCR for screenshots already captured by AccessibilityService.
 *
 * The bundled Latin model is packaged in the APK. Images are decoded in memory,
 * never written to disk, and the bitmap is recycled as soon as ML Kit completes.
 */
internal object LocalScreenVision {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(base64Jpeg: String): String? {
        val encoded = base64Jpeg.substringAfter(',', base64Jpeg)
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return null
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        val rows = result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                val bounds = line.boundingBox ?: return@mapNotNull null
                                val box = ScreenVisionPolicy.normalizeBounds(
                                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                                    bitmap.width, bitmap.height,
                                )
                                box to line.text
                            }
                        }
                        continuation.resume(ScreenVisionPolicy.formatLines(rows).ifBlank { null })
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
        }
    }
}
