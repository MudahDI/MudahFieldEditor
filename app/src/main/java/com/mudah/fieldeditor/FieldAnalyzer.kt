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

data class FieldAnalysis(
    val decompressedSize: Int,
    val sectionCount: Int,
    val sections: List<FieldSection>
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

        return FieldAnalysis(
            decompressedSize = raw.size,
            sectionCount = count.toInt(),
            sections = sections.sortedBy { it.index }
        )
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
