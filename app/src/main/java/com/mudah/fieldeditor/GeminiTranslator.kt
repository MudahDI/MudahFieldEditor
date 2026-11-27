package com.mudah.fieldeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GeminiDialogInput(val index: Int, val text: String)

object GeminiTranslator {
    const val MODEL = "gemini-3.1-flash-lite"
    private const val PROMPT_VERSION = "ff7-id-economy-v1"
    private const val MAX_BATCH_ITEMS = 12
    private const val MAX_BATCH_CHARS = 5000
    private const val TOKEN_PREFIX = "ZXQKEEP"
    private const val TOKEN_SUFFIX = "QXZ"
    private val tokenPattern = Regex("ZXQKEEP(\\d{4})QXZ")

    private data class ProtectedText(
        val text: String,
        val tokens: LinkedHashMap<String, String>,
        val tokenOrder: List<String>
    )

    fun translateDialogs(
        context: Context,
        apiKey: String,
        dialogs: List<GeminiDialogInput>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<Int, String> {
        require(apiKey.isNotBlank()) { "API key Gemini belum diisi." }
        require(dialogs.isNotEmpty()) { "Tidak ada dialog untuk diterjemahkan." }

        val cacheFile = File(context.filesDir, "gemini_translation_cache.json")
        val cache = loadCache(cacheFile)
        val results = linkedMapOf<Int, String>()
        val pending = mutableListOf<Pair<GeminiDialogInput, ProtectedText>>()
        var completed = 0

        dialogs.forEach { dialog ->
            if (dialog.text.isBlank()) {
                completed++
                onProgress(completed, dialogs.size)
                return@forEach
            }
            val protected = protect(dialog.text)
            if (protected.text.replace(tokenPattern, "").isBlank()) {
                completed++
                onProgress(completed, dialogs.size)
                return@forEach
            }
            val key = cacheKey(dialog.text)
            val cached = cache.optString(key, "")
            if (cached.isNotBlank()) {
                validateAndEncode(cached, protected, dialog.index)
                results[dialog.index] = cached
                completed++
                onProgress(completed, dialogs.size)
            } else {
                pending += dialog to protected
            }
        }

        val batches = mutableListOf<List<Pair<GeminiDialogInput, ProtectedText>>>()
        var current = mutableListOf<Pair<GeminiDialogInput, ProtectedText>>()
        var currentChars = 0
        pending.forEach { item ->
            val size = item.second.text.length
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_ITEMS || currentChars + size > MAX_BATCH_CHARS)
            ) {
                batches += current.toList()
                current = mutableListOf()
                currentChars = 0
            }
            current += item
            currentChars += size
        }
        if (current.isNotEmpty()) batches += current.toList()

        batches.forEachIndexed { batchIndex, batch ->
            val translated = requestWithRetry(apiKey, batch)
            batch.forEach { (dialog, protected) ->
                val value = translated[dialog.index]
                    ?: throw IllegalStateException("Gemini tidak mengembalikan dialog ${dialog.index}.")
                validateAndEncode(value, protected, dialog.index)
                results[dialog.index] = value
                cache.put(cacheKey(dialog.text), value)
                completed++
                onProgress(completed, dialogs.size)
            }
            saveCache(cacheFile, cache)
            if (batchIndex < batches.lastIndex) Thread.sleep(4000L)
        }
        return results
    }

    private fun requestWithRetry(
        apiKey: String,
        batch: List<Pair<GeminiDialogInput, ProtectedText>>
    ): Map<Int, String> {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                return requestBatch(apiKey, batch)
            } catch (error: GeminiHttpException) {
                lastError = error
                if (error.status !in listOf(429, 500, 502, 503, 504) || attempt == 2) throw error
            } catch (error: IOException) {
                lastError = error
                if (attempt == 2) throw error
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("Permintaan Gemini gagal.")
    }

    private fun requestBatch(
        apiKey: String,
        batch: List<Pair<GeminiDialogInput, ProtectedText>>
    ): Map<Int, String> {
        val sourceArray = JSONArray()
        batch.forEach { (dialog, protected) ->
            sourceArray.put(
                JSONObject()
                    .put("id", dialog.index)
                    .put("text", protected.text)
            )
        }

        val prompt = """
            You are a professional Indonesian video-game localization translator for Final Fantasy VII.
            Translate the English dialogue values into natural, concise Indonesian.
            Preserve the original meaning, character voice, tone, and story context.
            Keep character names and established game terminology consistent.
            Do not add explanations, notes, speaker labels, or quotation marks.
            Strings such as ZXQKEEP0000QXZ are protected control-code placeholders:
            preserve every placeholder exactly once and in the same order within its dialogue.
            Never translate, remove, duplicate, reorder, or modify placeholders.
            Return only a valid JSON array. Each item must contain the same integer "id"
            and a string "translation". Include every input id exactly once.
            Input:
            ${sourceArray}
        """.trimIndent()

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.15)
                    .put("responseMimeType", "application/json")
                    .put("maxOutputTokens", 8192)
            )

        val connection = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val message = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                } catch (_: Exception) {
                    null
                }.orEmpty().ifBlank { responseText.take(500) }
                throw GeminiHttpException(status, message)
            }

            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Respons Gemini tidak memiliki candidates.")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Respons Gemini tidak memiliki teks hasil.")
            val rawText = parts.optJSONObject(0)?.optString("text").orEmpty()
            val output = JSONArray(rawText.trim())
            val expectedIds = batch.map { it.first.index }.toSet()
            val mapped = linkedMapOf<Int, String>()

            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i)
                    ?: throw IllegalStateException("Format hasil Gemini tidak valid pada item ${i + 1}.")
                val id = item.optInt("id", -1)
                val translation = item.optString("translation", "")
                require(id in expectedIds) { "Gemini mengembalikan ID dialog yang tidak diminta: $id." }
                require(translation.isNotBlank()) { "Terjemahan dialog $id kosong." }
                require(!mapped.containsKey(id)) { "Gemini mengembalikan dialog $id lebih dari sekali." }
                mapped[id] = translation
            }
            require(mapped.keys == expectedIds) {
                "Hasil Gemini tidak lengkap. Diharapkan ${expectedIds.size} dialog, diterima ${mapped.size}."
            }
            return mapped
        } finally {
            connection.disconnect()
        }
    }

    private fun protect(source: String): ProtectedText {
        val output = StringBuilder()
        val tokens = linkedMapOf<String, String>()
        val order = mutableListOf<String>()
        var position = 0

        fun keep(value: String) {
            val token = "$TOKEN_PREFIX${tokens.size.toString().padStart(4, '0')}$TOKEN_SUFFIX"
            tokens[token] = value
            order += token
            output.append(token)
        }

        while (position < source.length) {
            val current = source[position]
            if (current == '\\' && position + 1 < source.length &&
                source[position + 1] in charArrayOf('\\', '{', '}')
            ) {
                keep(source.substring(position, position + 2))
                position += 2
            } else if (current == '{') {
                val end = source.indexOf('}', position + 1)
                if (end >= 0) {
                    keep(source.substring(position, end + 1))
                    position = end + 1
                } else {
                    output.append(current)
                    position++
                }
            } else if (current == '\n' || current == '\t') {
                keep(current.toString())
                position++
            } else {
                output.append(current)
                position++
            }
        }
        return ProtectedText(output.toString(), tokens, order)
    }

    private fun validateAndEncode(value: String, protected: ProtectedText, dialogIndex: Int) {
        val found = tokenPattern.findAll(value).map { it.value }.toList()
        require(found == protected.tokenOrder) {
            "Validasi dialog $dialogIndex gagal: kode kontrol berubah atau hilang. Dialog ini tidak diterapkan."
        }
        val restored = tokenPattern.replace(value) { match ->
            protected.tokens[match.value]
                ?: throw IllegalArgumentException("Placeholder tidak dikenal pada dialog $dialogIndex.")
        }
        require(!restored.isBlank()) { "Terjemahan dialog $dialogIndex kosong." }
        try {
            FieldTextCodec.encode(restored)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Terjemahan dialog $dialogIndex memiliki karakter atau perintah yang tidak didukung: ${error.message}"
            )
        }
    }

    private fun cacheKey(source: String): String {
        val bytes = "$MODEL|$PROMPT_VERSION|$source".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadCache(file: File): JSONObject {
        return try {
            if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveCache(file: File, cache: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(cache.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(cache.toString(), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private class GeminiHttpException(
        val status: Int,
        message: String
    ) : IOException("Gemini HTTP $status: $message")
}
