package com.mudah.fieldeditor

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Rebuilds an existing LGP without changing its entry count or names.
 * The original hash/path metadata is preserved byte-for-byte.
 */
object LgpArchiveWriter {
    data class Result(val entryCount: Int, val replacedEntries: Int, val outputBytes: Long)
    private data class SourceEntry(val entry: LgpEntry, val member: LgpMember, val replacement: ByteArray?)

    fun build(openSource: () -> InputStream, archive: LgpArchive, replacements: Map<String, ByteArray>, output: OutputStream): Result {
        require(archive.entries.isNotEmpty()) { "Arsip tidak memiliki entri." }
        require(archive.fileCount == archive.entries.size) { "Jumlah entri indeks tidak konsisten." }
        require(replacements.keys.all { key -> archive.entries.any { it.name == key } }) { "Ada nama field pengganti yang tidak ditemukan di arsip." }

        val sources = archive.entries.map { entry ->
            val member = openSource().use { LgpReader.readMember(it, entry) }
            val replacement = replacements[entry.name]
            if (replacement != null) {
                require(replacement.isNotEmpty()) { "Data pengganti ${entry.name} kosong." }
                require(replacement.size.toLong() <= 0xFFFFFFFFL) { "Data ${entry.name} terlalu besar." }
            }
            SourceEntry(entry, member, replacement)
        }

        val dataStart = archive.entries.minOf { it.offset }
        val tocEnd = 16L + archive.fileCount.toLong() * 27L
        require(dataStart >= tocEnd && dataStart <= 16L * 1024L * 1024L) { "Batas metadata LGP tidak valid atau terlalu besar." }

        val prefix = openSource().use { readExactly(it, dataStart.toInt()) }
        require(prefix.size >= tocEnd.toInt()) { "Header/TOC LGP terpotong." }

        var nextOffset = dataStart
        val newOffsets = LongArray(sources.size)
        sources.forEachIndexed { index, source ->
            newOffsets[index] = nextOffset
            val dataSize = source.replacement?.size?.toLong() ?: source.member.size
            require(dataSize in 0..0xFFFFFFFFL) { "Ukuran ${source.entry.name} di luar batas LGP." }
            nextOffset += 24L + dataSize
            require(nextOffset <= 0xFFFFFFFFL) { "Ukuran arsip hasil melebihi batas offset LGP 32-bit." }
        }

        sources.forEachIndexed { index, _ -> putU32(prefix, 16 + index * 27 + 20, newOffsets[index]) }
        var written = 0L
        output.write(prefix)
        written += prefix.size

        sources.forEach { source ->
            val nameBytes = source.member.storedName.toByteArray(Charsets.US_ASCII)
            require(nameBytes.isNotEmpty() && nameBytes.size <= 20) { "Nama header ${source.entry.name} tidak valid." }
            val header = ByteArray(24)
            nameBytes.copyInto(header, 0)
            putU32(header, 20, source.replacement?.size?.toLong() ?: source.member.size)
            output.write(header)
            written += header.size

            val replacement = source.replacement
            if (replacement != null) {
                output.write(replacement)
                written += replacement.size
            } else {
                openSource().use { copyRange(it, source.member.dataOffset, source.member.size, output) }
                written += source.member.size
            }
        }

        val maxOriginalEnd = sources.maxOf { it.member.dataOffset + it.member.size }
        val suffix = openSource().use { input -> skipFully(input, maxOriginalEnd); input.readBytes() }
        require(suffix.size <= 1024 * 1024) { "Data setelah member LGP terlalu besar; rekonstruksi dihentikan." }
        val footer = "FINAL FANTASY7".toByteArray(Charsets.US_ASCII)
        require(suffix.size >= footer.size && suffix.copyOfRange(suffix.size - footer.size, suffix.size).contentEquals(footer)) {
            "Penanda akhir FINAL FANTASY7 tidak ditemukan. Arsip hasil tidak dibuat."
        }
        output.write(suffix)
        written += suffix.size
        output.flush()

        val checked = LgpReader.read(ByteArrayInputStream(prefix), prefix.size.toLong())
        require(checked.fileCount == archive.fileCount && checked.entries.size == archive.entries.size) { "Validasi ulang indeks LGP gagal." }
        checked.entries.forEachIndexed { index, entry ->
            require(entry.offset == newOffsets[index]) { "Validasi offset entri ${entry.name} gagal." }
        }

        return Result(sources.size, replacements.size, written)
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
        repeat(4) { index -> data[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte() }
    }
}
