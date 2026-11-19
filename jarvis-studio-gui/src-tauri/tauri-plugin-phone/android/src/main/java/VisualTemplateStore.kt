package com.jarvis.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Persists compact icon fingerprints only; screenshots are never retained. */
internal object VisualTemplateStore {
    private const val PREFS = "jarvis.visual.templates.v1"
    private const val KEY = "templates"
    private const val MAX_TEMPLATES = 80
    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 6_000_000
    private const val MAX_MATCH_TEMPLATES = 12

    data class Summary(val id: String, val label: String, val appPackage: String)
    data class Found(val id: String, val label: String, val appPackage: String, val match: VisualMatch)

    fun save(
        context: Context,
        screenshotBase64: String,
        crop: VisionBox,
        id: String,
        label: String,
        appPackage: String,
    ): Summary? {
        if (!id.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) return null
        val cleanLabel = label.trim().replace(Regex("\\s+"), " ").take(80)
        if (cleanLabel.isBlank() || appPackage.isBlank() || appPackage.length > 160) return null
        val bitmap = decode(screenshotBase64) ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val template = VisualTemplateMatcher.capture(
                pixels, bitmap.width, bitmap.height, crop, id, cleanLabel, appPackage,
            ) ?: return null
            val current = load(context).filterNot { it.id == id }.toMutableList()
            if (current.size >= MAX_TEMPLATES) current.removeAt(0)
            current += template
            persist(context, current)
            return Summary(template.id, template.label, template.appPackage)
        } finally {
            bitmap.recycle()
        }
    }

    fun list(context: Context): List<Summary> =
        load(context).map { Summary(it.id, it.label, it.appPackage) }

    fun delete(context: Context, id: String): Boolean {
        val before = load(context)
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        persist(context, after)
        return true
    }

    fun find(context: Context, screenshotBase64: String, appPackage: String): List<Found> {
        val templates = load(context)
            .filter { it.appPackage == appPackage }
            .takeLast(MAX_MATCH_TEMPLATES)
        if (templates.isEmpty()) return emptyList()
        val bitmap = decode(screenshotBase64) ?: return emptyList()
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return templates.mapNotNull { template ->
                val match = VisualTemplateMatcher.find(
                    pixels, bitmap.width, bitmap.height, template,
                    currentApp = appPackage, threshold = 0.92f, stride = 6,
                ) ?: return@mapNotNull null
                Found(template.id, template.label, template.appPackage, match)
            }.sortedByDescending { it.match.confidence }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(raw: String): Bitmap? {
        if (raw.isBlank() || raw.length > MAX_IMAGE_BYTES * 2) return null
        val encoded = raw.substringAfter(',', raw)
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return null }
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_IMAGE_PIXELS
        ) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun load(context: Context): List<VisualTemplate> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<VisualTemplate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val label = item.optString("label")
            val app = item.optString("appPackage")
            val grid = item.optInt("gridSize", 0)
            val featuresJson = item.optJSONArray("features") ?: continue
            if (id.isBlank() || label.isBlank() || app.isBlank() || grid !in 8..24 ||
                featuresJson.length() != grid * grid
            ) continue
            val features = FloatArray(featuresJson.length()) { index ->
                featuresJson.optDouble(index, Double.NaN).toFloat()
            }
            if (features.any { !it.isFinite() }) continue
            out += VisualTemplate(
                id, label, app,
                item.optInt("widthPermille").coerceIn(1, 1000),
                item.optInt("heightPermille").coerceIn(1, 1000),
                grid, features,
            )
        }
        return out.takeLast(MAX_TEMPLATES)
    }

    private fun persist(context: Context, templates: List<VisualTemplate>) {
        val array = JSONArray()
        templates.takeLast(MAX_TEMPLATES).forEach { template ->
            val features = JSONArray()
            template.features.forEach { features.put(it.toDouble()) }
            array.put(
                JSONObject()
                    .put("id", template.id)
                    .put("label", template.label)
                    .put("appPackage", template.appPackage)
                    .put("widthPermille", template.widthPermille)
                    .put("heightPermille", template.heightPermille)
                    .put("gridSize", template.gridSize)
                    .put("features", features),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }
}


/** One short-lived screenshot held only in memory while the user returns to the learning panel. */
internal object VisualTemplateCaptureSession {
    data class Preview(
        val token: String,
        val base64: String,
        val appPackage: String,
        val generation: Long,
        val expiresAtMs: Long,
    )

    @Volatile private var current: Preview? = null

    @Synchronized
    fun store(base64: String, appPackage: String, generation: Long): Preview {
        val preview = Preview(
            token = java.util.UUID.randomUUID().toString().replace("-", ""),
            base64 = base64,
            appPackage = appPackage,
            generation = generation,
            expiresAtMs = android.os.SystemClock.elapsedRealtime() + 300_000L,
        )
        current = preview
        return preview
    }

    @Synchronized
    fun peek(token: String = ""): Preview? {
        val value = current ?: return null
        if (android.os.SystemClock.elapsedRealtime() > value.expiresAtMs) {
            current = null
            return null
        }
        if (token.isNotBlank() && token != value.token) return null
        return value
    }

    @Synchronized
    fun consume(token: String): Preview? {
        val value = peek(token) ?: return null
        current = null
        return value
    }

    @Synchronized
    fun discard(token: String = ""): Boolean {
        val value = peek(token) ?: return false
        if (token.isNotBlank() && token != value.token) return false
        current = null
        return true
    }
}
