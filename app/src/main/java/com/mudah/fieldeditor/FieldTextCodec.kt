package com.mudah.fieldeditor

/**
 * International FF7 field-dialog text codec.
 * The character table and control-code grammar follow the ff7tools reference.
 */
object FieldTextCodec {
    private const val END = 0xFF

    // The reference table contains seven trailing padding spaces; only 0x00..0xDF are bytes.
    private val normalChars = (
        " !\"#$%&'()*+,-./01234" +
        "56789:;<=>?@ABCDEFGHI" +
        "JKLMNOPQRSTUVWXYZ[\\]^" +
        "_`abcdefghijklmnopqrs" +
        "tuvwxyz{|}~ ÄÅÇÉÑÖÜáà" +
        "âäãåçéèêëíìîïñóòôöõúù" +
        "ûü♥°¢£↔→♪ßα  ´¨≠ÆØ∞±≤" +
        "≥¥µ∂ΣΠπ⌡ªºΩæø¿¡¬√ƒ≈∆«" +
        "»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄ ‹" +
        "›ﬁﬂ■‧‚„‰ÂÊÁËÈÍÎÏÌÓÔ Ò" +
        "ÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ       "
    ).take(0xE0)

    private val specialChars = mapOf(
        0xE0 to "{CHOICE}", 0xE1 to "\t", 0xE2 to ", ", 0xE3 to ".\"",
        0xE4 to "…\"", 0xE6 to "⑬", 0xE7 to "\n", 0xE8 to "{NEW}\n",
        0xEA to "{CLOUD}", 0xEB to "{BARRET}", 0xEC to "{TIFA}",
        0xED to "{AERITH}", 0xEE to "{RED XIII}", 0xEF to "{YUFFIE}",
        0xF0 to "{CAIT SITH}", 0xF1 to "{VINCENT}", 0xF2 to "{CID}",
        0xF3 to "{PARTY #1}", 0xF4 to "{PARTY #2}", 0xF5 to "{PARTY #3}",
        0xF6 to "〇", 0xF7 to "△", 0xF8 to "☐", 0xF9 to "✕"
    )

    private val controlCommands = mapOf(
        0xD2 to "GRAY", 0xD3 to "BLUE", 0xD4 to "RED", 0xD5 to "PURPLE",
        0xD6 to "GREEN", 0xD7 to "CYAN", 0xD8 to "YELLOW", 0xD9 to "WHITE",
        0xDA to "FLASH", 0xDB to "RAINBOW", 0xDC to "PAUSE",
        0xDE to "NUM", 0xDF to "HEX", 0xE0 to "SCROLL",
        0xE1 to "RNUM", 0xE9 to "FIXED"
    )

    private val simpleCommands = buildMap<String, ByteArray> {
        put("CHOICE", byteArrayOf(0xE0.toByte()))
        put("NEW", byteArrayOf(0xE8.toByte()))
        put("CLOUD", byteArrayOf(0xEA.toByte()))
        put("BARRET", byteArrayOf(0xEB.toByte()))
        put("TIFA", byteArrayOf(0xEC.toByte()))
        put("AERITH", byteArrayOf(0xED.toByte()))
        put("RED XIII", byteArrayOf(0xEE.toByte()))
        put("YUFFIE", byteArrayOf(0xEF.toByte()))
        put("CAIT SITH", byteArrayOf(0xF0.toByte()))
        put("VINCENT", byteArrayOf(0xF1.toByte()))
        put("CID", byteArrayOf(0xF2.toByte()))
        put("PARTY #1", byteArrayOf(0xF3.toByte()))
        put("PARTY #2", byteArrayOf(0xF4.toByte()))
        put("PARTY #3", byteArrayOf(0xF5.toByte()))
        controlCommands.forEach { (code, name) ->
            put(name, byteArrayOf(0xFE.toByte(), code.toByte()))
        }
    }

    init {
        check(normalChars.length == 0xE0) { "Tabel karakter FF7 harus berisi 224 karakter." }
    }

    fun decode(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val value = bytes[i].toInt() and 0xFF
            when {
                value == END -> break
                value < 0xE0 -> {
                    val c = normalChars[value]
                    if (c == '\\' || c == '{' || c == '}') out.append('\\')
                    out.append(c)
                    i++
                }
                value == 0xFE -> {
                    if (i + 1 >= bytes.size) {
                        out.append("{FE}")
                        i++
                    } else {
                        val code = bytes[i + 1].toInt() and 0xFF
                        when (code) {
                            0xDD -> {
                                if (i + 3 < bytes.size) {
                                    val frames = (bytes[i + 2].toInt() and 0xFF) or
                                        ((bytes[i + 3].toInt() and 0xFF) shl 8)
                                    out.append("{WAIT $frames}")
                                    i += 4
                                } else {
                                    out.append("{FE DD}")
                                    i += 2
                                }
                            }
                            0xE2 -> {
                                if (i + 5 < bytes.size) {
                                    val offset = (bytes[i + 2].toInt() and 0xFF) or
                                        ((bytes[i + 3].toInt() and 0xFF) shl 8)
                                    val length = (bytes[i + 4].toInt() and 0xFF) or
                                        ((bytes[i + 5].toInt() and 0xFF) shl 8)
                                    out.append("{STR %04X %04X}".format(offset, length))
                                    i += 6
                                } else {
                                    out.append("{FE E2}")
                                    i += 2
                                }
                            }
                            else -> {
                                val label = controlCommands[code]
                                out.append(label?.let { "{$it}" } ?: "{FE %02X}".format(code))
                                i += 2
                            }
                        }
                    }
                }
                else -> {
                    out.append(specialChars[value] ?: "{%02X}".format(value))
                    i++
                }
            }
        }
        return out.toString()
    }

    fun encode(text: String): ByteArray {
        val output = ArrayList<Byte>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                require(i + 1 < text.length) { "Karakter escape berada di akhir teks." }
                val escaped = text[i + 1]
                require(escaped == '\\' || escaped == '{' || escaped == '}') {
                    "Escape tidak dikenal: \\$escaped"
                }
                val index = normalChars.indexOf(escaped)
                require(index >= 0) { "Karakter tidak tersedia di font FF7: $escaped" }
                output.add(index.toByte())
                i += 2
                continue
            }

            if (c == '{') {
                val end = text.indexOf('}', i + 1)
                require(end >= 0) { "Kurung perintah tidak ditutup." }
                val command = text.substring(i + 1, end).trim()
                val rawSingle = Regex("^[0-9A-Fa-f]{2}$").matchEntire(command)
                val rawPair = Regex("^FE\\s+([0-9A-Fa-f]{2})$").matchEntire(command)
                when {
                    rawSingle != null -> {
                        val value = command.toInt(16)
                        require(value != END) { "0xFF adalah terminator dan tidak boleh berada di tengah teks." }
                        output.add(value.toByte())
                    }
                    rawPair != null -> {
                        output.add(0xFE.toByte())
                        output.add(rawPair.groupValues[1].toInt(16).toByte())
                    }
                    command.startsWith("WAIT ") -> {
                        val frames = command.removePrefix("WAIT ").trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Format WAIT tidak valid.")
                        require(frames in 0..65535) { "Nilai WAIT harus 0 sampai 65535." }
                        output.add(0xFE.toByte())
                        output.add(0xDD.toByte())
                        output.add((frames and 0xFF).toByte())
                        output.add(((frames shr 8) and 0xFF).toByte())
                    }
                    command.startsWith("STR ") -> {
                        val match = Regex("^STR ([0-9A-Fa-f]{4}) ([0-9A-Fa-f]{4})$").matchEntire(command)
                            ?: throw IllegalArgumentException("Format STR harus: STR OOOO LLLL.")
                        val offset = match.groupValues[1].toInt(16)
                        val length = match.groupValues[2].toInt(16)
                        output.add(0xFE.toByte())
                        output.add(0xE2.toByte())
                        output.add((offset and 0xFF).toByte())
                        output.add(((offset shr 8) and 0xFF).toByte())
                        output.add((length and 0xFF).toByte())
                        output.add(((length shr 8) and 0xFF).toByte())
                    }
                    simpleCommands.containsKey(command) -> {
                        output.addAll(simpleCommands.getValue(command).toList())
                    }
                    else -> throw IllegalArgumentException("Perintah FF7 tidak dikenal: {$command}")
                }
                i = end + 1
                if (command == "NEW" && i < text.length && text[i] == '\n') i++
                continue
            }

            when (c) {
                '\t' -> output.add(0xE1.toByte())
                '\n' -> output.add(0xE7.toByte())
                '〇' -> output.add(0xF6.toByte())
                '△' -> output.add(0xF7.toByte())
                '☐' -> output.add(0xF8.toByte())
                '✕' -> output.add(0xF9.toByte())
                '⑬' -> output.add(0xE6.toByte())
                else -> {
                    val index = normalChars.indexOf(c)
                    require(index >= 0) { "Karakter tidak didukung font FF7: '$c'." }
                    output.add(index.toByte())
                }
            }
            i++
        }
        output.add(END.toByte())
        return output.toByteArray()
    }
}
