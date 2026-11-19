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
                        continuation.resume(ScreenVisionPolicy.formatLines(rows).takeIf { it.isNotBlank() })
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


/** A compact, normalized grayscale fingerprint learned from a user-designated crop. */
internal data class VisualTemplate(
    val id: String,
    val label: String,
    val appPackage: String,
    val widthPermille: Int,
    val heightPermille: Int,
    val gridSize: Int,
    val features: FloatArray,
)

/** A high-confidence candidate location. It is evidence, not permission to actuate. */
internal data class VisualMatch(
    val bounds: VisionBox,
    val confidence: Float,
)

internal object VisualTemplateMatcher {
    private const val DEFAULT_GRID = 12
    private const val MIN_VARIANCE = 7.0f
    private val scales = floatArrayOf(0.90f, 1.0f, 1.10f)

    /** Extract a bounded icon template from a screenshot crop expressed in 0..1000 units. */
    fun capture(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        crop: VisionBox,
        id: String,
        label: String,
        appPackage: String,
        gridSize: Int = DEFAULT_GRID,
    ): VisualTemplate? {
        if (id.isBlank() || label.isBlank() || appPackage.isBlank()) return null
        if (gridSize !in 8..24 || imageWidth <= 0 || imageHeight <= 0) return null
        if (pixels.size != imageWidth * imageHeight) return null
        val left = (crop.x.coerceIn(0, 1000) * imageWidth / 1000).coerceIn(0, imageWidth)
        val top = (crop.y.coerceIn(0, 1000) * imageHeight / 1000).coerceIn(0, imageHeight)
        val right = ((crop.x + crop.w).coerceIn(0, 1000) * imageWidth / 1000).coerceIn(left, imageWidth)
        val bottom = ((crop.y + crop.h).coerceIn(0, 1000) * imageHeight / 1000).coerceIn(top, imageHeight)
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) return null
        val features = featureVector(pixels, imageWidth, left, top, width, height, gridSize) ?: return null
        return VisualTemplate(
            id = id.take(80),
            label = label.take(80),
            appPackage = appPackage.take(160),
            widthPermille = (width * 1000f / imageWidth).toInt().coerceIn(1, 1000),
            heightPermille = (height * 1000f / imageHeight).toInt().coerceIn(1, 1000),
            gridSize = gridSize,
            features = features,
        )
    }

    /**
     * Find the best match for a template on a later screenshot.
     * The package guard prevents reusing an icon learned in an unrelated app.
     */
    fun find(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        template: VisualTemplate,
        currentApp: String,
        threshold: Float = 0.88f,
        stride: Int = 6,
    ): VisualMatch? {
        if (currentApp != template.appPackage) return null
        if (imageWidth <= 0 || imageHeight <= 0 || pixels.size != imageWidth * imageHeight) return null
        if (template.gridSize !in 8..24 || template.features.size != template.gridSize * template.gridSize) return null
        if (!threshold.isFinite() || threshold !in 0.5f..1.0f) return null
        val requestedStep = stride.coerceIn(2, 32)
        var bestScore = -1f
        var best: VisualMatch? = null
        var bestLeft = -1
        var bestTop = -1
        var bestWidth = 0
        var bestHeight = 0
        var bestStep = 1

        for (scale in scales) {
            val patchW = (imageWidth * template.widthPermille / 1000f * scale).toInt()
            val patchH = (imageHeight * template.heightPermille / 1000f * scale).toInt()
            if (patchW < 8 || patchH < 8 || patchW > imageWidth || patchH > imageHeight) continue
            val lastX = imageWidth - patchW
            val lastY = imageHeight - patchH
            // Bound work on high-resolution screens: at most about 5k candidate positions
            // per scale, while retaining the caller's requested minimum stride.
            val searchArea = (lastX + 1).toLong() * (lastY + 1).toLong()
            val adaptiveStep = kotlin.math.ceil(kotlin.math.sqrt(searchArea / 5_000.0)).toInt()
            val step = maxOf(requestedStep, adaptiveStep, 2)
            val xs = mutableListOf<Int>()
            val ys = mutableListOf<Int>()
            var x = 0
            while (x <= lastX) { xs += x; x += step }
            var y = 0
            while (y <= lastY) { ys += y; y += step }
            if (xs.lastOrNull() != lastX) xs += lastX
            if (ys.lastOrNull() != lastY) ys += lastY

            for (top in ys) for (left in xs) {
                val candidate = featureVector(pixels, imageWidth, left, top, patchW, patchH, template.gridSize)
                    ?: continue
                val score = similarity(template.features, candidate)
                if (score > bestScore) {
                    bestScore = score
                    bestLeft = left
                    bestTop = top
                    bestWidth = patchW
                    bestHeight = patchH
                    bestStep = step
                    best = VisualMatch(
                        bounds = VisionBox(
                            x = (left * 1000f / imageWidth).toInt(),
                            y = (top * 1000f / imageHeight).toInt(),
                            w = (patchW * 1000f / imageWidth).toInt(),
                            h = (patchH * 1000f / imageHeight).toInt(),
                        ),
                        confidence = score,
                    )
                }
            }
        }

        // Refine the best coarse candidate at pixel resolution. This removes stride
        // quantization so a small icon is not missed just because its top-left edge
        // fell between coarse scan positions.
        if (bestLeft >= 0) {
            val fromX = (bestLeft - bestStep).coerceAtLeast(0)
            val toX = (bestLeft + bestStep).coerceAtMost(imageWidth - bestWidth)
            val fromY = (bestTop - bestStep).coerceAtLeast(0)
            val toY = (bestTop + bestStep).coerceAtMost(imageHeight - bestHeight)
            for (top in fromY..toY) for (left in fromX..toX) {
                val candidate = featureVector(
                    pixels, imageWidth, left, top, bestWidth, bestHeight, template.gridSize,
                ) ?: continue
                val score = similarity(template.features, candidate)
                if (score > bestScore) {
                    bestScore = score
                    bestLeft = left
                    bestTop = top
                    best = VisualMatch(
                        bounds = VisionBox(
                            (left * 1000f / imageWidth).toInt(),
                            (top * 1000f / imageHeight).toInt(),
                            (bestWidth * 1000f / imageWidth).toInt(),
                            (bestHeight * 1000f / imageHeight).toInt(),
                        ),
                        confidence = score,
                    )
                }
            }
        }
        return best?.takeIf { it.confidence >= threshold }
    }

    private fun featureVector(
        pixels: IntArray,
        imageWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        gridSize: Int,
    ): FloatArray? {
        val count = gridSize * gridSize
        val values = FloatArray(count)
        var sum = 0f
        var index = 0
        for (gy in 0 until gridSize) {
            val py = (top + ((gy + 0.5f) * height / gridSize)).toInt().coerceIn(top, top + height - 1)
            for (gx in 0 until gridSize) {
                val px = (left + ((gx + 0.5f) * width / gridSize)).toInt().coerceIn(left, left + width - 1)
                val color = pixels[py * imageWidth + px]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val gray = (red * 0.299f + green * 0.587f + blue * 0.114f)
                values[index++] = gray
                sum += gray
            }
        }
        val mean = sum / count
        var variance = 0f
        for (v in values) {
            val delta = v - mean
            variance += delta * delta
        }
        val deviation = kotlin.math.sqrt(variance / count)
        if (deviation < MIN_VARIANCE) return null
        var norm = 0f
        for (i in values.indices) {
            values[i] = (values[i] - mean) / deviation
            norm += values[i] * values[i]
        }
        val length = kotlin.math.sqrt(norm).coerceAtLeast(0.0001f)
        for (i in values.indices) values[i] /= length
        return values
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return ((dot + 1f) / 2f).coerceIn(0f, 1f)
    }
}
