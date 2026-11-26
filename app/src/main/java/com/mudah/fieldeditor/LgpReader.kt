package com.mudah.fieldeditor

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class LgpEntry(
    val name: String,
    val offset: Long,
    val checkByte: Int,
    val duplicateIndex: Int
)

data class LgpArchive(
    val fileCount: Int,
    val entries: List<LgpEntry>
)

data class LgpMember(
    val entry: LgpEntry,
    val storedName: String,
    val size: Long,
    val dataOffset: Long
)

object LgpReader {
    private val ASCII: Charset = Charsets.US_ASCII
    private val CREATOR = "SQUARESOFT".toByteArray(ASCII)

    fun read(input: InputStream, archiveLength: Long = -1L): LgpArchive {
        val stream = DataInputStream(input)
        val header = ByteArray(16)
        stream.readFully(header)

        if (header[0] != 0.toByte() || header[1] != 0.toByte()) {
            throw IllegalArgumentException("Header LGP tidak valid: dua byte awal bukan nol.")
        }

        val creator = header.copyOfRange(2, 12)
        if (!creator.contentEquals(CREATOR)) {
            throw IllegalArgumentException("Format tidak dikenali: identitas SQUARESOFT tidak ditemukan.")
        }

        val countLong = ByteBuffer.wrap(header, 12, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL

        if (countLong == 0L || countLong > 65535L) {
            throw IllegalArgumentException("Jumlah entri tidak masuk akal: $countLong.")
        }

        val count = countLong.toInt()
        val tocEnd = 16L + count.toLong() * 27L
        if (archiveLength >= 0L && tocEnd + 3600L > archiveLength) {
            throw IllegalArgumentException("Arsip terlalu pendek untuk memuat tabel indeks LGP.")
        }

        val entries = ArrayList<LgpEntry>(count)
        val nameBuffer = ByteArray(20)
        val numberBuffer = ByteArray(4)
        val tailBuffer = ByteArray(3)

        repeat(count) { index ->
            stream.readFully(nameBuffer)
            stream.readFully(numberBuffer)
            stream.readFully(tailBuffer)

            val zero = nameBuffer.indexOf(0)
            val nameLength = if (zero >= 0) zero else nameBuffer.size
            val name = String(nameBuffer, 0, nameLength, ASCII).trim()

            if (name.isEmpty()) {
                throw IllegalArgumentException("Nama file pada entri ${index + 1} kosong.")
            }

            val offset = ByteBuffer.wrap(numberBuffer)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int.toLong() and 0xFFFFFFFFL

            if (offset < tocEnd || (archiveLength >= 0L && offset + 24L > archiveLength)) {
                throw IllegalArgumentException(
                    "Offset entri ${index + 1} berada di luar batas arsip: $offset."
                )
            }

            val checkByte = tailBuffer[0].toInt() and 0xFF
            val duplicateIndex = (tailBuffer[1].toInt() and 0xFF) or
                ((tailBuffer[2].toInt() and 0xFF) shl 8)

            entries.add(LgpEntry(name, offset, checkByte, duplicateIndex))
        }

        return LgpArchive(count, entries)
    }

    fun readMember(
        input: InputStream,
        entry: LgpEntry,
        archiveLength: Long = -1L
    ): LgpMember {
        skipFully(input, entry.offset)
        val header = ByteArray(24)
        DataInputStream(input).readFully(header)

        val zero = header.copyOfRange(0, 20).indexOf(0)
        val nameLength = if (zero >= 0) zero else 20
        val storedName = String(header, 0, nameLength, ASCII).trim()
        val size = ByteBuffer.wrap(header, 20, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL
        val dataOffset = entry.offset + 24L

        if (archiveLength >= 0L && (dataOffset > archiveLength || size > archiveLength - dataOffset)) {
            throw IllegalArgumentException("Ukuran data entri ${entry.name} melewati batas arsip.")
        }
        if (storedName.isEmpty()) {
            throw IllegalArgumentException("Header data untuk ${entry.name} tidak memiliki nama.")
        }

        return LgpMember(entry, storedName, size, dataOffset)
    }

    fun extractMember(input: InputStream, output: java.io.OutputStream, member: LgpMember) {
        skipFully(input, member.dataOffset)
        val buffer = ByteArray(64 * 1024)
        var remaining = member.size
        while (remaining > 0L) {
            val amount = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, amount)
            if (read < 0) throw IllegalArgumentException("Data ${member.entry.name} terpotong.")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
        output.flush()
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw IllegalArgumentException("Arsip terpotong saat mencari data.")
                remaining--
            }
        }
    }
}
