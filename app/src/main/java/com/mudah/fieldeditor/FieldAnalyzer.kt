package com.mudah.fieldeditor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FieldSection(
    val index: Int,
    val name: String,
    val offset: Long,
    val dataLength: Long,
    val totalLength: Long,
    val valid: Boolean
)

data class FieldScriptInfo(
    val signature: Int,
    val entityCount: Int,
    val modelCount: Int,
    val stringOffset: Int,
    val akaoCount: Int,
    val scale: Int,
    val creator: String,
    val fieldName: String,
    val entityNames: List<String>,
    val scriptSlots: List<Int>,
    val populatedScriptSlots: Int,
    val akaoOffsets: List<Long>,
    val headerSize: Int,
    val valid: Boolean,
    val notes: List<String>
)

data class FieldAnalysis(
    val decompressedSize: Int,
    val sectionCount: Int,
    val sections: List<FieldSection>,
    val scriptInfo: FieldScriptInfo?
)

object FieldAnalyzer {
    private val sectionNames = listOf(
        "Field Script & Dialog",
        "Camera Matrix",
        "Model Loader",
        "Palette",
        "Walkmesh",
        "TileMap (Unused)",
        "Encounter",
        "Triggers",
        "Background"
    )

    fun analyze(compressedFile: ByteArray): FieldAnalysis {
        if (compressedFile.size < 4) {
            throw IllegalArgumentException("Data terlalu pendek untuk header LZSS.")
        }

        val declaredCompressedSize = u32(compressedFile, 0)
        val actualCompressedSize = compressedFile.size - 4L
        if (declaredCompressedSize != actualCompressedSize) {
            throw IllegalArgumentException(
                "Ukuran LZSS tidak cocok. Header: $declaredCompressedSize byte, data: $actualCompressedSize byte."
            )
        }

        val raw = decompressLzss(compressedFile, 4)
        if (raw.size < 0x2A) {
            throw IllegalArgumentException("Hasil dekompresi terlalu pendek untuk header field PC.")
        }
        if (raw[0] != 0.toByte() || raw[1] != 0.toByte()) {
            throw IllegalArgumentException("Header field tidak valid: dua byte awal bukan nol.")
        }

        val count = u32(raw, 2)
        if (count != 9L) {
            throw IllegalArgumentException("Jumlah section tidak sesuai format PC FFVII: ditemukan $count, seharusnya 9.")
        }

        val pointers = ArrayList<Pair<Int, Long>>(9)
        for (i in 0 until 9) {
            val offset = u32(raw, 6 + i * 4)
            if (offset < 0x2AL || offset > raw.size - 4L) {
                throw IllegalArgumentException(
                    "Pointer section ${i + 1} di luar batas file: 0x${offset.toString(16).uppercase()}."
                )
            }
            pointers.add(i to offset)
        }

        val sorted = pointers.sortedBy { it.second }
        if (sorted.map { it.second }.distinct().size != 9) {
            throw IllegalArgumentException("Ditemukan pointer section yang duplikat.")
        }

        val sections = ArrayList<FieldSection>(9)
        for ((position, pair) in sorted.withIndex()) {
            val (index, offset) = pair
            val dataLength = u32(raw, offset.toInt())
            val dataStart = offset + 4L
            val end = dataStart + dataLength
            val nextOffset = sorted.getOrNull(position + 1)?.second ?: raw.size.toLong()

            if (end < dataStart || end > raw.size.toLong() || end > nextOffset) {
                throw IllegalArgumentException(
                    "Panjang section ${index + 1} tidak valid atau bertabrakan dengan section berikutnya."
                )
            }

            sections.add(
                FieldSection(
                    index = index + 1,
                    name = sectionNames[index],
                    offset = offset,
                    dataLength = dataLength,
                    totalLength = dataLength + 4L,
                    valid = true
                )
            )
        }

        val firstSection = sections.firstOrNull { it.index == 1 }
            ?: throw IllegalArgumentException("Section Field Script & Dialog tidak ditemukan.")
        val scriptInfo = parseScriptHeader(
            raw = raw,
            sectionOffset = firstSection.offset.toInt() + 4,
            sectionLength = firstSection.dataLength.toInt()
        )

        return FieldAnalysis(
            decompressedSize = raw.size,
            sectionCount = count.toInt(),
            sections = sections.sortedBy { it.index },
            scriptInfo = scriptInfo
        )
    }

    private fun parseScriptHeader(
        raw: ByteArray,
        sectionOffset: Int,
        sectionLength: Int
    ): FieldScriptInfo {
        fun requireRange(offset: Int, length: Int, label: String) {
            if (offset < sectionOffset || length < 0 ||
                offset.toLong() + length.toLong() > sectionOffset.toLong() + sectionLength ||
                offset.toLong() + length.toLong() > raw.size.toLong()
            ) {
                throw IllegalArgumentException("Header skrip tidak lengkap pada $label.")
            }
        }

        requireRange(sectionOffset, 32, "header dasar")
        val signature = u16(raw, sectionOffset)
        val entityCount = raw[sectionOffset + 2].toInt() and 0xFF
        val modelCount = raw[sectionOffset + 3].toInt() and 0xFF
        val stringOffset = u16(raw, sectionOffset + 4)
        val akaoCount = u16(raw, sectionOffset + 6)
        val scale = u16(raw, sectionOffset + 8)
        val creator = fixedText(raw, sectionOffset + 16, 8)
        val fieldName = fixedText(raw, sectionOffset + 24, 8)

        val headerSizeLong = 32L + entityCount * 8L + akaoCount * 4L + entityCount * 32L * 2L
        if (headerSizeLong > sectionLength.toLong() || headerSizeLong > Int.MAX_VALUE) {
            throw IllegalArgumentException(
                "Ukuran header skrip ($headerSizeLong byte) melewati batas section ($sectionLength byte)."
            )
        }
        val headerSize = headerSizeLong.toInt()
        var cursor = sectionOffset + 32
        requireRange(cursor, entityCount * 8, "nama entitas")
        val entityNames = (0 until entityCount).map { index ->
            fixedText(raw, cursor + index * 8, 8).ifBlank { "Entitas ${index + 1}" }
        }
        cursor += entityCount * 8

        requireRange(cursor, akaoCount * 4, "daftar AKAO")
        val akaoOffsets = (0 until akaoCount).map { index ->
            u32(raw, cursor + index * 4)
        }
        cursor += akaoCount * 4

        val slotCount = entityCount * 32
        requireRange(cursor, slotCount * 2, "tabel entry skrip")
        val scriptSlots = (0 until slotCount).map { index ->
            u16(raw, cursor + index * 2)
        }
        val populated = scriptSlots.count { it != 0 && it != 0xFFFF }

        val notes = mutableListOf<String>()
        if (signature != 0x0502) notes += "Signature bukan 0x0502; format perlu diperiksa."
        if (stringOffset !in 0 until sectionLength) notes += "Offset string berada di luar section."
        if (entityCount == 0) notes += "Tidak ada entitas yang terdaftar."
        if (akaoOffsets.any { it >= sectionLength.toLong() }) {
            notes += "Ada offset AKAO di luar section."
        }
        if (scriptSlots.any { it != 0 && it != 0xFFFF && it >= sectionLength }) {
            notes += "Ada entry skrip di luar section."
        }

        return FieldScriptInfo(
            signature = signature,
            entityCount = entityCount,
            modelCount = modelCount,
            stringOffset = stringOffset,
            akaoCount = akaoCount,
            scale = scale,
            creator = creator,
            fieldName = fieldName,
            entityNames = entityNames,
            scriptSlots = scriptSlots,
            populatedScriptSlots = populated,
            akaoOffsets = akaoOffsets,
            headerSize = headerSize,
            valid = notes.isEmpty(),
            notes = notes
        )
    }

    private fun fixedText(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size) return ""
        val bytes = data.copyOfRange(offset, offset + length)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.copyOfRange(0, end).toString(Charsets.ISO_8859_1).trim()
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) {
            throw IllegalArgumentException("Data tidak cukup untuk membaca integer 16-bit.")
        }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun decompressLzss(input: ByteArray, start: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val window = ByteArray(4096)
        var windowPosition = 0xFEE
        var inputPosition = start
        val maxOutput = 128 * 1024 * 1024

        while (inputPosition < input.size) {
            val control = input[inputPosition++].toInt() and 0xFF

            for (bit in 0 until 8) {
                if (inputPosition >= input.size) break

                if ((control and (1 shl bit)) != 0) {
                    val value = input[inputPosition++]
                    output.write(value.toInt() and 0xFF)
                    window[windowPosition] = value
                    windowPosition = (windowPosition + 1) and 0xFFF
                } else {
                    if (inputPosition + 1 >= input.size) {
                        throw IllegalArgumentException("Blok referensi LZSS terpotong.")
                    }

                    val first = input[inputPosition++].toInt() and 0xFF
                    val second = input[inputPosition++].toInt() and 0xFF
                    val reference = first or ((second and 0xF0) shl 4)
                    val length = (second and 0x0F) + 3

                    repeat(length) {
                        val value = window[(reference + it) and 0xFFF]
                        output.write(value.toInt() and 0xFF)
                        window[windowPosition] = value
                        windowPosition = (windowPosition + 1) and 0xFFF
                    }
                }

                if (output.size() > maxOutput) {
                    throw IllegalArgumentException("Hasil dekompresi melewati batas aman 128 MB.")
                }
            }
        }

        return output.toByteArray()
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) {
            throw IllegalArgumentException("Data tidak cukup untuk membaca integer 32-bit.")
        }
        return ByteBuffer.wrap(data, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL
    }
}
