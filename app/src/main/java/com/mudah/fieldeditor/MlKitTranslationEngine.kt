package com.mudah.fieldeditor

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Replaceable local translation engine for the first FF7 translation test.
 * English -> Indonesian runs on-device after the language models are downloaded.
 */
class MlKitTranslationEngine : AutoCloseable {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
    )

    fun prepare(onReady: (Throwable?) -> Unit) {
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { onReady(null) }
            .addOnFailureListener { onReady(it) }
    }

    fun translateBatch(
        items: List<Pair<Int, String>>,
        onProgress: (completed: Int, total: Int) -> Unit,
        onTranslated: (dialogIndex: Int, translatedText: String) -> Unit,
        onFinished: (failures: List<String>) -> Unit
    ) {
        val failures = mutableListOf<String>()
        var position = 0

        fun next() {
            if (position >= items.size) {
                close()
                onFinished(failures)
                return
            }

            val (dialogIndex, source) = items[position]
            val protected = protectTokens(source)
            if (protected.translatableText.isBlank()) {
                onTranslated(dialogIndex, source)
                position++
                onProgress(position, items.size)
                next()
                return
            }

            translator.translate(protected.translatableText)
                .addOnSuccessListener { translated ->
                    try {
                        val restored = restoreTokens(translated, protected.tokens)
                        // Reject unsupported glyphs before they enter the editable draft.
                        FieldTextCodec.encode(restored)
                        onTranslated(dialogIndex, restored)
                    } catch (error: Exception) {
                        failures += "Dialog $dialogIndex: ${error.message ?: "hasil tidak valid"}"
                    }
                    position++
                    onProgress(position, items.size)
                    next()
                }
                .addOnFailureListener { error ->
                    failures += "Dialog $dialogIndex: ${error.message ?: "terjemahan gagal"}"
                    position++
                    onProgress(position, items.size)
                    next()
                }
        }

        if (items.isEmpty()) {
            close()
            onFinished(emptyList())
        } else {
            next()
        }
    }

    private data class ProtectedText(
        val translatableText: String,
        val tokens: List<String>
    )

    private fun protectTokens(source: String): ProtectedText {
        val tokens = mutableListOf<String>()
        val pattern = Regex("""\{[^{}\r\n]+\}|\\[{}\\]|\r?\n|\t""")
        val protected = pattern.replace(source) { match ->
            val index = tokens.size
            tokens += match.value
            "QZXF7TOKEN${index}Q"
        }
        return ProtectedText(protected, tokens)
    }

    private fun restoreTokens(translated: String, tokens: List<String>): String {
        var result = translated
        tokens.forEachIndexed { index, token ->
            val marker = "QZXF7TOKEN${index}Q"
            require(result.countSubstring(marker) == 1) {
                "Penanda kode kontrol berubah saat diterjemahkan."
            }
            result = result.replace(marker, token)
        }
        require(!Regex("""QZXF7TOKEN\d+Q""").containsMatchIn(result)) {
            "Ada penanda kode kontrol yang hilang atau berubah."
        }
        return result
    }

    private fun String.countSubstring(value: String): Int {
        if (value.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(value, start)
            if (index < 0) return count
            count++
            start = index + value.length
        }
    }

    override fun close() {
        translator.close()
    }
}
