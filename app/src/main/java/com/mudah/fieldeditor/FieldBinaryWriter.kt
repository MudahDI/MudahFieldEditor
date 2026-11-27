package com.mudah.fieldeditor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rebuilds the decompressed PC FF7 field member and wraps it in the game's LZSS format.
 * The original source bytes are never modified.
 */
object FieldBinaryWriter {
    data class Result(
        val compressedBytes: ByteArray,
        val decompressedSize: Int,
        val oldDialogBytes: Int,
        val newDialogBytes: Int,
        val changedDialogs: Int
    )

    fun build(analysis: FieldAnalysis, translations: Map<Int, String>): Result {
        val original = analysis.rawData
        val info = analysis.scriptInfo ?: error("Metadata skrip tidak tersedia.")
        val dialogInfo = info.dialogInfo ?: error("Tabel dialog tidak tersedia.")
        require(dialogInfo.valid && dialogInfo.dialogs.size == dialogInfo.count) {
            "Tabel dialog belum tervalidasi lengkap; ekspor dibatalkan."
        }
        require(dialogInfo.count in 1..256) { "Jumlah dialog di luar batas aman." }

        val firstSection = analysis.sections.singleOrNull { it.index == 1 }
            ?: error("Section Field Script & Dialog tidak ditemukan.")
        val sectionOffset = firstSection.offset.toInt()
        val sectionDataStart = sectionOffset + 4
        val sectionDataEnd = sectionDataStart + firstSection.dataLength.toInt()
        require(sectionOffset >= 0 && sectionDataEnd <= original.size) {
            "Batas section skrip tidak valid."
        }

        val stringOffset = info.stringOffset
        val tableStart = sectionDataStart + stringOffset
        require(stringOffset >= info.headerSize && tableStart + 4 <= sectionDataEnd) {
            "Offset tabel dialog tidak valid atau bertabrakan dengan header skrip."
        }
        val firstStringOffset = u16(original, tableStart + 2)
        val pointerCount = firstStringOffset / 2 - 1
        require(pointerCount == dialogInfo.count && firstStringOffset == 2 + pointerCount * 2) {
            "Ukuran tabel pointer dialog tidak konsisten."
        }
        require(tableStart + firstStringOffset <= sectionDataEnd) {
            "Tabel pointer melewati batas section."
        }

        val akaoBoundaryRelative = info.akaoOffsets
            .filter { it > stringOffset.toLong() && it < firstSection.dataLength }
            .minOrNull()?.toInt() ?: firstSection.dataLength.toInt()
        require(akaoBoundaryRelative >= stringOffset + firstStringOffset &&
            akaoBoundaryRelative <= firstSection.dataLength.toInt()) {
            "Batas data dialog dan AKAO tidak valid; ekspor dibatalkan."
        }
        val regionEnd = sectionDataStart + akaoBoundaryRelative

        data class Group(
            val pointer: Int,
            val dialogs: List<FieldDialog>,
            val oldBytes: ByteArray,
            val newBytes: ByteArray
        )

        val pointerValues = (0 until dialogInfo.count).map { index ->
            u16(original, tableStart + 2 + index * 2)
        }
        val dialogsByPointer = dialogInfo.dialogs.groupBy { it.offset - stringOffset }
        require(dialogsByPointer.size == dialogInfo.dialogs.size) {
            "Ada beberapa dialog yang berbagi pointer. Ekspor otomatis dihentikan untuk mencegah perubahan dialog yang tidak disengaja."
        }

        val groups = dialogsByPointer.entries.sortedBy { it.key }.mapIndexed { position, entry ->
            val pointer = entry.key
            val dialog = entry.value.single()
            val absoluteStart = tableStart + pointer
            require(pointer >= firstStringOffset && absoluteStart >= tableStart + firstStringOffset &&
                absoluteStart + dialog.length <= regionEnd) {
                "Pointer dialog ${dialog.index} berada di luar area string."
            }
            val oldBytes = original.copyOfRange(absoluteStart, absoluteStart + dialog.length)
            require(oldBytes.isNotEmpty() && (oldBytes.last().toInt() and 0xFF) == 0xFF) {
                "Dialog ${dialog.index} tidak memiliki terminator 0xFF."
            }
            require(oldBytes.contentEquals(dialog.rawBytes)) {
                "Data asli dialog ${dialog.index} berubah sejak analisis."
            }
            val translated = translations[dialog.index]
            val newBytes = if (translated.isNullOrBlank() || translated == dialog.preview) {
                oldBytes
            } else {
                FieldTextCodec.encode(translated)
            }
            Group(pointer, entry.value, oldBytes, newBytes)
        }

        val region = ByteArrayOutputStream()
        region.write(original, tableStart, firstStringOffset)
        var oldCursor = tableStart + firstStringOffset
        val newPointers = HashMap<Int, Int>()
        for (group in groups) {
            val oldStart = tableStart + group.pointer
            require(oldStart >= oldCursor) { "Rentang string saling bertumpuk." }
            region.write(original, oldCursor, oldStart - oldCursor)
            val newPointer = region.size()
            require(newPointer <= 0xFFFF) { "Offset dialog baru melebihi batas pointer 16-bit." }
            newPointers[group.dialogs.single().index] = newPointer
            region.write(group.newBytes)
            oldCursor = oldStart + group.oldBytes.size
            val nextStart = groups.getOrNull(groups.indexOf(group) + 1)?.let { tableStart + it.pointer }
                ?: regionEnd
            require(oldCursor <= nextStart) { "Data dialog bertumpuk atau melewati batas area string." }
            region.write(original, oldCursor, nextStart - oldCursor)
            oldCursor = nextStart
        }
        if (groups.isEmpty()) error("Tidak ada dialog yang dapat diproses.")
        require(oldCursor == regionEnd) { "Area string tidak dapat direkonstruksi secara utuh." }

        val rebuiltRegion = region.toByteArray()
        val delta = rebuiltRegion.size - (regionEnd - tableStart)
        val patchedRegion = rebuiltRegion.copyOf()
        for (index in pointerValues.indices) {
            val dialog = dialogInfo.dialogs[index]
            val newPointer = newPointers[dialog.index]
                ?: error("Pointer baru untuk dialog ${dialog.index} tidak ditemukan.")
            putU16(patchedRegion, 2 + index * 2, newPointer)
        }

        val scriptPrefix = original.copyOfRange(sectionDataStart, tableStart)
        val scriptSuffix = original.copyOfRange(regionEnd, sectionDataEnd)
        val patchedPrefix = scriptPrefix.copyOf()
        val akaoTableStart = 32 + info.entityCount * 8
        info.akaoOffsets.forEachIndexed { index, offset ->
            if (offset >= akaoBoundaryRelative.toLong()) {
                val shifted = offset + delta
                require(shifted in 0..0xFFFFFFFFL) { "Offset AKAO melebihi batas 32-bit." }
                putU32(patchedPrefix, akaoTableStart + index * 4, shifted)
            }
        }
        val scriptSlotTableStart = akaoTableStart + info.akaoCount * 4
        info.scriptSlots.forEachIndexed { index, offset ->
            if (offset != 0 && offset != 0xFFFF && offset >= akaoBoundaryRelative) {
                val shifted = offset + delta
                require(shifted in 0..0xFFFF) { "Offset entry skrip melebihi batas 16-bit." }
                putU16(patchedPrefix, scriptSlotTableStart + index * 2, shifted)
            }
        }

        val newSectionData = ByteArrayOutputStream()
        newSectionData.write(patchedPrefix)
        newSectionData.write(patchedRegion)
        newSectionData.write(scriptSuffix)
        val sectionBytes = newSectionData.toByteArray()

        val sortedSections = analysis.sections.sortedBy { it.offset }
        val rebuiltField = ByteArrayOutputStream()
        var sourceCursor = 0
        val newSectionOffsets = HashMap<Int, Int>()
        for (section in sortedSections) {
            val start = section.offset.toInt()
            val end = start + section.totalLength.toInt()
            require(start >= sourceCursor && end <= original.size) {
                "Batas section ${section.index} saling bertumpuk atau di luar file."
            }
            rebuiltField.write(original, sourceCursor, start - sourceCursor)
            val newStart = rebuiltField.size()
            newSectionOffsets[section.index] = newStart
            if (section.index == 1) {
                writeU32(rebuiltField, sectionBytes.size.toLong())
                rebuiltField.write(sectionBytes)
            } else {
                rebuiltField.write(original, start, end - start)
            }
            sourceCursor = end
        }
        rebuiltField.write(original, sourceCursor, original.size - sourceCursor)
        val rebuiltRaw = rebuiltField.toByteArray()
        newSectionOffsets.forEach { (index, offset) ->
            putU32(rebuiltRaw, 6 + (index - 1) * 4, offset.toLong())
        }

        val compressed = compressLzss(rebuiltRaw)
        val verified = FieldAnalyzer.analyze(compressed)
        val verifiedDialogs = verified.scriptInfo?.dialogInfo?.dialogs
            ?: error("Validasi hasil gagal: tabel dialog tidak terbaca.")
        require(verifiedDialogs.size == dialogInfo.count) {
            "Validasi hasil gagal: jumlah dialog berubah."
        }
        verifiedDialogs.forEach { dialog ->
            val expected = translations[dialog.index]
            if (!expected.isNullOrBlank() && expected != dialogInfo.dialogs[dialog.index - 1].preview) {
                require(dialog.preview == expected) {
                    "Validasi hasil gagal pada dialog ${dialog.index}: teks hasil baca ulang tidak cocok."
                }
            }
        }

        return Result(
            compressedBytes = compressed,
            decompressedSize = rebuiltRaw.size,
            oldDialogBytes = regionEnd - tableStart,
            newDialogBytes = rebuiltRegion.size,
            changedDialogs = groups.count { !it.oldBytes.contentEquals(it.newBytes) }
        )
    }

    private fun compressLzss(input: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        val chains = HashMap<Int, java.util.ArrayDeque<Int>>()
        var position = 0

        fun keyAt(index: Int): Int {
            return (input[index].toInt() and 0xFF) or
                ((input[index + 1].toInt() and 0xFF) shl 8) or
                ((input[index + 2].toInt() and 0xFF) shl 16)
        }

        fun remember(index: Int) {
            if (index + 2 >= input.size) return
            val key = keyAt(index)
            val chain = chains.getOrPut(key) { java.util.ArrayDeque() }
            chain.addLast(index)
            while (chain.isNotEmpty() && index - chain.peekFirst() > 4096) {
                chain.removeFirst()
            }
            while (chain.size > 96) chain.removeFirst()
        }

        while (position < input.size) {
            val group = ByteArrayOutputStream()
            var control = 0
            for (bit in 0 until 8) {
                if (position >= input.size) break
                var bestLength = 0
                var bestReference = 0
                val maxLength = minOf(18, input.size - position)
                if (maxLength >= 3) {
                    val chain = chains[keyAt(position)]
                    if (chain != null) {
                        val iterator = chain.descendingIterator()
                        var examined = 0
                        while (iterator.hasNext() && examined < 96) {
                            val candidate = iterator.next()
                            val distance = position - candidate
                            if (distance > 4096) break
                            examined++
                            var length = 0
                            while (length < maxLength &&
                                input[candidate + length] == input[position + length]
                            ) {
                                length++
                            }
                            if (length > bestLength) {
                                bestLength = length
                                bestReference = (0xFEE + candidate) and 0xFFF
                                if (length == maxLength) break
                            }
                        }
                    }
                }

                val consumed = if (bestLength >= 3) {
                    group.write(bestReference and 0xFF)
                    group.write(((bestReference shr 4) and 0xF0) or (bestLength - 3))
                    bestLength
                } else {
                    control = control or (1 shl bit)
                    group.write(input[position].toInt() and 0xFF)
                    1
                }

                repeat(consumed) {
                    remember(position)
                    position++
                }
            }
            body.write(control)
            body.write(group.toByteArray())
        }

        val compressedBody = body.toByteArray()
        val result = ByteArray(compressedBody.size + 4)
        putU32(result, 0, compressedBody.size.toLong())
        compressedBody.copyInto(result, 4)
        return result
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> data[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte() }
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index -> output.write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }
}
