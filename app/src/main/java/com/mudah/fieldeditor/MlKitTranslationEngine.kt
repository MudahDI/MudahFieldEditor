package com.mudah.fieldeditor

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * English -> Indonesian on-device translation engine.
 * FF7 commands and glossary entries are split out before translation,
 * so the translation model never receives artificial placeholder markers.
 */
data class TranslationFailure(
    val dialogIndex: Int,
    val sourceText: String,
    val stage: String,
    val reason: String
)

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
        onFinished: (failures: List<TranslationFailure>) -> Unit
    ) {
        val failures = mutableListOf<TranslationFailure>()
        var position = 0

        fun nextDialog() {
            if (position >= items.size) {
                close()
                onFinished(failures.toList())
                return
            }

            val (dialogIndex, source) = items[position]
            val segments = splitProtectedSegments(source)
            val output = StringBuilder()
            var segmentPosition = 0
            var failed = false

            fun nextSegment() {
                if (segmentPosition >= segments.size) {
                    if (!failed) {
                        val translated = output.toString()
                        try {
                            FieldTextCodec.encode(translated)
                            onTranslated(dialogIndex, translated)
                        } catch (error: Exception) {
                            failures += TranslationFailure(
                                dialogIndex, source, "Validasi karakter FF7",
                                error.message ?: error.javaClass.simpleName
                            )
                        }
                    }
                    position++
                    onProgress(position, items.size)
                    nextDialog()
                    return
                }

                val segment = segments[segmentPosition++]
                if (segment.protected || segment.text.isBlank()) {
                    output.append(segment.text)
                    nextSegment()
                    return
                }

                translator.translate(segment.text)
                    .addOnSuccessListener { translatedPart ->
                        output.append(translatedPart)
                        nextSegment()
                    }
                    .addOnFailureListener { error ->
                        failed = true
                        failures += TranslationFailure(
                            dialogIndex,
                            source,
                            "Mesin terjemahan ML Kit",
                            "Bagian teks '${segment.text.take(100)}': ${error.message ?: error.javaClass.simpleName}"
                        )
                        // Stop this dialog after a segment failure; do not save a partial translation.
                        segmentPosition = segments.size
                        nextSegment()
                    }
            }

            nextSegment()
        }

        if (items.isEmpty()) {
            close()
            onFinished(emptyList())
        } else {
            nextDialog()
        }
    }

    private data class TextSegment(val text: String, val protected: Boolean)

    // Nama lokasi/karakter yang perlu konsisten dan tidak boleh diubah ML Kit.
    private val glossary = listOf(
        "Temple of the Ancients" to "Kuil Kuno",
        "Northern Crater" to "Kawah Utara",
        "Bone Village" to "Desa Tulang",
        "Fort Condor" to "Benteng Condor",
        "Cosmo Canyon" to "Ngarai Cosmo",
        "Rocket Town" to "Kota Roket",
        "Wall Market" to "Pasar Tembok",
        "Costa del Sol" to "Costa del Sol",
        "Gold Saucer" to "Gold Saucer",
        "Midgar" to "Midgar",
        "Shinra" to "Shinra",
        "Nibelheim" to "Nibelheim",
        "Junon" to "Junon",
        "Wutai" to "Wutai",
        "Mideel" to "Mideel",
        "Aerith" to "Aerith",
        "Barret" to "Barret",
        "Tifa" to "Tifa",
        "Cloud" to "Cloud",
        "Yuffie" to "Yuffie",
        "Vincent" to "Vincent",
        "Cid" to "Cid",
        "Cait Sith" to "Cait Sith",
        "Red XIII" to "Red XIII"
    )

    private fun splitProtectedSegments(source: String): List<TextSegment> {
        val glossaryAlternatives = glossary
            .sortedByDescending { it.first.length }
            .joinToString("|") { Regex.escape(it.first) }

        // FF7 command tokens, formatting tags, line breaks, and glossary terms
        // are emitted literally and never passed through the translation model.
        val pattern = Regex(
            "(?<![A-Za-z0-9])(?i:$glossaryAlternatives)(?![A-Za-z0-9])" +
                """|\{[^{}\r\n]+\}|\\[{}\\]|\[[^\]\r\n]+\]|\([A-Z][A-Z0-9_ ]*\)|\r?\n|\t"""
        )
        val segments = mutableListOf<TextSegment>()
        var cursor = 0

        pattern.findAll(source).forEach { match ->
            if (match.range.first > cursor) {
                segments += TextSegment(source.substring(cursor, match.range.first), false)
            }

            val glossaryEntry = glossary.firstOrNull {
                it.first.equals(match.value, ignoreCase = true)
            }
            val exactText = glossaryEntry?.second ?: match.value
            segments += TextSegment(exactText, true)
            cursor = match.range.last + 1
        }

        if (cursor < source.length) {
            segments += TextSegment(source.substring(cursor), false)
        }
        if (segments.isEmpty()) segments += TextSegment(source, false)
        return segments
    }

    override fun close() {
        translator.close()
    }
}
