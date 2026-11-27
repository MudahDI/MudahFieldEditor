package com.mudah.fieldeditor

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Rebuilds an LGP while preserving TOC order, hash/path metadata,
 * physical member order, duplicate-offset relationships, and trailing bytes.
 */
object LgpArchiveWriter {
    data class Result(val entryCount: Int, val replacedEntries: Int, val outputBytes: Long)

    private data class SourceBlock(
        val originalOffset: Long,
        val aliases: List<LgpMember>,
        val replacement: ByteArray?
    ) {
        val member: LgpMember get() = aliases.first()
        val originalEnd: Long get() = member.dataOffset + member.size
    }

    fun build(
        openSource: () -> InputStream,
        archive: LgpArchive,
        replacements: Map<String, ByteArray>,
        output: OutputStream
    ): Result {
        require(archive.entries.isNotEmpty()) { "Arsip tidak memiliki entri." }
        require(archive.fileCount == archive.entries.size) { "Jumlah entri indeks tidak konsisten." }
        require(replacements.keys.all { key -> archive.entries.any { it.name == key } }) {
            "Ada nama field pengganti yang tidak ditemukan di arsip."
        }

        val members = archive.entries.map { entry ->
            openSource().use { LgpReader.readMember(it, entry) }
        }

        val blocks = members.groupBy { it.entry.offset }.toSortedMap().map { (offset, aliases) ->
            val first = aliases.first()
            require(aliases.all {
                it.dataOffset == first.dataOffset &&
                    it.size == first.size &&
                    it.storedName == first.storedName
            }) { "Entri yang berbagi offset 0x${offset.toString(16)} memiliki header bertentangan." }

            val candidates = aliases.mapNotNull { replacements[it.entry.name] }
            if (candidates.size > 1) {
                require(candidates.drop(1).all { it.contentEquals(candidates.first()) }) {
                    "Entri duplikat pada offset 0x${offset.toString(16)} memiliki terjemahan berbeda."
                }
            }
            val replacement = candidates.firstOrNull()
            if (replacement != null) {
                require(replacement.isNotEmpty()) { "Data pengganti ${first.entry.name} kosong." }
            }
            SourceBlock(offset, aliases, replacement)
        }

        val dataStart = blocks.first().originalOffset
        val tocEnd = 16L + archive.fileCount.toLong() * 27L
        require(dataStart >= tocEnd + 3600L && dataStart <= 16L * 1024L * 1024L) {
            "Batas metadata/hash/path LGP tidak valid."
        }
        val prefix = openSource().use { readExactly(it, dataStart.toInt()) }
        require(prefix.size >= tocEnd.toInt() + 3600) {
            "Header, TOC, atau tabel hash LGP terpotong."
        }

        val newOffsetByOriginal = HashMap<Long, Long>()
        var outputPosition = dataStart
        var previousOriginalEnd = dataStart
        for (block in blocks) {
            require(block.originalOffset >= previousOriginalEnd) {
                "Blok data LGP bertumpang tindih pada offset 0x${block.originalOffset.toString(16)}."
            }
            outputPosition += block.originalOffset - previousOriginalEnd
            newOffsetByOriginal[block.originalOffset] = outputPosition
            val dataSize = block.replacement?.size?.toLong() ?: block.member.size
            require(dataSize in 1..0xFFFFFFFFL) {
                "Ukuran ${block.member.entry.name} di luar batas LGP."
            }
            outputPosition += 24L + dataSize
            require(outputPosition <= 0xFFFFFFFFL) {
                "Ukuran arsip hasil melebihi batas offset LGP 32-bit."
            }
            previousOriginalEnd = block.originalEnd
        }

        archive.entries.forEachIndexed { index, entry ->
            val newOffset = newOffsetByOriginal[entry.offset]
                ?: throw IllegalArgumentException("Offset entri ${entry.name} tidak terpetakan.")
            putU32(prefix, 16 + index * 27 + 20, newOffset)
        }

        var written = 0L
        output.write(prefix)
        written += prefix.size
        previousOriginalEnd = dataStart

        for (block in blocks) {
            val gap = block.originalOffset - previousOriginalEnd
            if (gap > 0) {
                openSource().use { copyRange(it, previousOriginalEnd, gap, output) }
                written += gap
            }

            val nameBytes = block.member.storedName.toByteArray(Charsets.US_ASCII)
            require(nameBytes.isNotEmpty() && nameBytes.size <= 20) {
                "Nama header ${block.member.entry.name} tidak valid."
            }
            val dataSize = block.replacement?.size?.toLong() ?: block.member.size
            val header = ByteArray(24)
            nameBytes.copyInto(header, 0)
            putU32(header, 20, dataSize)
            output.write(header)
            written += header.size

            if (block.replacement != null) {
                output.write(block.replacement)
            } else {
                openSource().use { copyRange(it, block.member.dataOffset, block.member.size, output) }
            }
            written += dataSize
            previousOriginalEnd = block.originalEnd
        }

        val suffix = openSource().use { input ->
            skipFully(input, previousOriginalEnd)
            input.readBytes()
        }
        val footer = "FINAL FANTASY7".toByteArray(Charsets.US_ASCII)
        require(suffix.size >= footer.size &&
            suffix.copyOfRange(suffix.size - footer.size, suffix.size).contentEquals(footer)
        ) { "Penanda akhir FINAL FANTASY7 tidak ditemukan." }

        output.write(suffix)
        written += suffix.size
        output.flush()

        return Result(archive.entries.size, replacements.size, written)
    }

    /**
     * Reopens a completed temporary archive and verifies every TOC entry and payload
     * against the source or its replacement before it is copied to user storage.
     */
    fun validate(
        openSource: () -> InputStream,
        archive: LgpArchive,
        replacements: Map<String, ByteArray>,
        outputFile: File
    ) {
        val length = outputFile.length()
        val rebuilt = FileInputStream(outputFile).use { LgpReader.read(it, length) }
        require(rebuilt.fileCount == archive.fileCount && rebuilt.entries.size == archive.entries.size) {
            "Validasi gagal: jumlah entri hasil tidak sama dengan arsip asli."
        }

        val replacementByOffset = archive.entries.mapNotNull { entry ->
            replacements[entry.name]?.let { entry.offset to it }
        }.toMap()

        rebuilt.entries.forEachIndexed { index, entry ->
            val originalEntry = archive.entries[index]
            require(entry.name == originalEntry.name &&
                entry.checkByte == originalEntry.checkByte &&
                entry.duplicateIndex == originalEntry.duplicateIndex
            ) { "Validasi metadata entri ke-${index + 1} gagal." }

            val originalMember = openSource().use { LgpReader.readMember(it, originalEntry) }
            val rebuiltMember = FileInputStream(outputFile).use {
                LgpReader.readMember(it, entry, length)
            }
            require(rebuiltMember.storedName == originalMember.storedName) {
                "Validasi nama header ${entry.name} gagal."
            }

            val expectedReplacement = replacementByOffset[originalEntry.offset]
            val expectedSize = expectedReplacement?.size?.toLong() ?: originalMember.size
            require(rebuiltMember.size == expectedSize) {
                "Validasi ukuran data ${entry.name} gagal."
            }

            if (expectedReplacement != null) {
                FileInputStream(outputFile).use { input ->
                    require(rangeEquals(input, rebuiltMember.dataOffset, expectedReplacement)) {
                        "Validasi isi terjemahan ${entry.name} gagal."
                    }
                }
            } else {
                val same = openSource().use { source ->
                    FileInputStream(outputFile).use { target ->
                        rangesEqual(
                            source, originalMember.dataOffset,
                            target, rebuiltMember.dataOffset,
                            originalMember.size
                        )
                    }
                }
                require(same) { "Data entri ${entry.name} berubah tanpa diminta." }
            }
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var position = 0
        while (position < count) {
            val read = input.read(result, position, count - position)
            if (read < 0) throw IllegalArgumentException("Arsip terpotong saat membaca metadata.")
            position += read
        }
        return result
    }

    private fun copyRange(input: InputStream, offset: Long, size: Long, output: OutputStream) {
        skipFully(input, offset)
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalArgumentException("Data member terpotong saat rekonstruksi.")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun rangesEqual(
        first: InputStream, firstOffset: Long,
        second: InputStream, secondOffset: Long,
        size: Long
    ): Boolean {
        skipFully(first, firstOffset)
        skipFully(second, secondOffset)
        val a = ByteArray(64 * 1024)
        val b = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val amount = minOf(a.size.toLong(), remaining).toInt()
            readFully(first, a, amount)
            readFully(second, b, amount)
            for (i in 0 until amount) if (a[i] != b[i]) return false
            remaining -= amount
        }
        return true
    }

    private fun rangeEquals(input: InputStream, offset: Long, expected: ByteArray): Boolean {
        skipFully(input, offset)
        val buffer = ByteArray(64 * 1024)
        var position = 0
        while (position < expected.size) {
            val amount = minOf(buffer.size, expected.size - position)
            readFully(input, buffer, amount)
            for (i in 0 until amount) if (buffer[i] != expected[position + i]) return false
            position += amount
        }
        return true
    }

    private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
        var position = 0
        while (position < count) {
            val read = input.read(buffer, position, count - position)
            if (read < 0) throw IllegalArgumentException("Data member terpotong saat validasi.")
            position += read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() < 0) throw IllegalArgumentException("Arsip terpotong saat mencari offset.")
                remaining--
            }
        }
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        require(offset >= 0 && offset + 4 <= data.size)
        repeat(4) { index ->
            data[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }
}
