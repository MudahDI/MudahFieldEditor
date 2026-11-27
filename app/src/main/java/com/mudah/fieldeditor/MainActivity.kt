package com.mudah.fieldeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private var selectedFile by mutableStateOf<Uri?>(null)
    private var selectedFileName by mutableStateOf("Belum ada file dipilih")
    private var entries by mutableStateOf<List<LgpEntry>>(emptyList())
    private var selectedMember by mutableStateOf<LgpMember?>(null)
    private var fieldAnalysis by mutableStateOf<FieldAnalysis?>(null)
    private var searchQuery by mutableStateOf("")
    private var status by mutableStateOf("Pilih flevel.lgp untuk mulai membaca.")
    private var isReading by mutableStateOf(false)
    private var isExporting by mutableStateOf(false)
    private var isAnalyzing by mutableStateOf(false)
    private var selectedDialogIndex by mutableStateOf<Int?>(null)
    private val dialogTranslations = mutableStateMapOf<Int, String>()

    private val openLgp = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            selectedFileName = queryDisplayName(uri)
            entries = emptyList()
            selectedMember = null
            fieldAnalysis = null
            selectedDialogIndex = null
            dialogTranslations.clear()
            searchQuery = ""
            status = "File dipilih. Tekan Baca Arsip untuk memindai indeks."
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val source = selectedFile
        val member = selectedMember
        if (uri != null && source != null && member != null) {
            exportMember(source, uri, member)
        }
    }

    private val createTranslationExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val analysis = fieldAnalysis
        if (uri != null && analysis != null) {
            try {
                val json = buildTranslationJson(analysis)
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Tidak dapat membuat file tujuan.")
                status = "Proyek terjemahan berhasil diekspor. File asli tidak diubah."
            } catch (error: Exception) {
                status = "Gagal mengekspor proyek: ${error.message ?: "kesalahan tidak diketahui"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9CC7FF),
                    background = Color(0xFF101318),
                    surface = Color(0xFF191E25)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val filteredEntries = entries.filter {
                        it.name.contains(searchQuery.trim(), ignoreCase = true)
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Column(modifier = Modifier.padding(top = 14.dp)) {
                                Text(
                                    "MUDAH FIELD EDITOR",
                                    color = Color(0xFF9CC7FF),
                                    fontSize = 11.sp,
                                    letterSpacing = 2.sp
                                )
                                Text(
                                    "Final Fantasy VII",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White
                                )
                                Text(
                                    "LGP Browser • Field Structure Inspector",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF191E25), RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Button(
                                    onClick = { openLgp.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Pilih flevel.lgp")
                                }
                                Text(
                                    selectedFileName,
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { readSelectedArchive() },
                                    enabled = selectedFile != null && !isReading,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                ) {
                                    Text(if (isReading) "Sedang membaca..." else "Baca Arsip")
                                }
                                Text(
                                    status,
                                    color = if (status.startsWith("Gagal")) Color(0xFFFF9B9B) else Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                            }
                        }

                        if (entries.isNotEmpty()) {
                            item {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Cari nama file") }
                                )
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "DAFTAR FILE",
                                        color = Color(0xFF9CC7FF),
                                        letterSpacing = 1.sp,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        "${filteredEntries.size} dari ${entries.size}",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            selectedMember?.let { member ->
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF202A36), RoundedCornerShape(14.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            "FILE TERPILIH",
                                            color = Color(0xFF9CC7FF),
                                            style = MaterialTheme.typography.labelLarge,
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            member.entry.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "Ukuran: ${formatSize(member.size)}",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (member.storedName != member.entry.name) {
                                            Text(
                                                "Nama header berbeda: ${member.storedName}",
                                                color = Color(0xFFFFD18A),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Button(
                                            onClick = { createExport.launch(member.entry.name) },
                                            enabled = !isExporting,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        ) {
                                            Text(if (isExporting) "Mengekspor..." else "Ekspor File")
                                        }
                                        Button(
                                            onClick = { analyzeSelectedMember() },
                                            enabled = !isAnalyzing,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(if (isAnalyzing) "Menganalisis..." else "Analisis Struktur Field")
                                        }
                                    }
                                }
                            }

                            fieldAnalysis?.let { analysis ->
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF191E25), RoundedCornerShape(14.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            "HASIL ANALISIS",
                                            color = Color(0xFF9CC7FF),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "Dekompresi berhasil",
                                            color = Color(0xFF9FE0B2),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Ukuran hasil", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                            Text(formatSize(analysis.decompressedSize.toLong()), color = Color.White, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Jumlah section", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                            Text("${analysis.sectionCount} / 9", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "STRUKTUR SECTION",
                                            color = Color(0xFF9CC7FF),
                                            style = MaterialTheme.typography.labelLarge,
                                            letterSpacing = 1.sp
                                        )
                                        analysis.sections.forEach { section ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 7.dp)
                                                    .background(Color(0xFF242B34), RoundedCornerShape(10.dp))
                                                    .padding(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "${section.index}. ${section.name}",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        if (section.valid) "VALID" else "CEK",
                                                        color = if (section.valid) Color(0xFF9FE0B2) else Color(0xFFFF9B9B),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                                Text(
                                                    "Pointer: 0x${section.offset.toString(16).uppercase()}",
                                                    color = Color(0xFFB8D6FF),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                Text(
                                                    "Data: ${formatSize(section.dataLength)} • Total: ${formatSize(section.totalLength)}",
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        analysis.scriptInfo?.let { info ->
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                "FIELD SCRIPT & DIALOG",
                                                color = Color(0xFF9CC7FF),
                                                style = MaterialTheme.typography.titleSmall,
                                                letterSpacing = 1.sp
                                            )
                                            Text(
                                                if (info.valid) "Header skrip terbaca" else "Header terbaca dengan catatan",
                                                color = if (info.valid) Color(0xFF9FE0B2) else Color(0xFFFFD18A),
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            val metadata = listOf(
                                                "Nama field" to info.fieldName.ifBlank { "(tidak tersedia)" },
                                                "Creator" to info.creator.ifBlank { "(tidak tersedia)" },
                                                "Signature" to "0x${info.signature.toString(16).uppercase().padStart(4, '0')}",
                                                "Jumlah entitas" to info.entityCount.toString(),
                                                "Jumlah model" to info.modelCount.toString(),
                                                "Offset string" to "0x${info.stringOffset.toString(16).uppercase()}",
                                                "Skala field" to info.scale.toString(),
                                                "Blok AKAO" to info.akaoCount.toString(),
                                                "Entry skrip terisi" to "${info.populatedScriptSlots} / ${info.scriptSlots.size}",
                                                "Ukuran header" to formatSize(info.headerSize.toLong())
                                            )
                                            metadata.forEach { (label, value) ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                                    Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            if (info.entityNames.isNotEmpty()) {
                                                Text(
                                                    "NAMA ENTITAS",
                                                    color = Color(0xFF9CC7FF),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    modifier = Modifier.padding(top = 10.dp)
                                                )
                                                info.entityNames.forEachIndexed { index, name ->
                                                    Text(
                                                        "${index + 1}. $name",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(top = 3.dp)
                                                    )
                                                }
                                            }
                                            info.notes.forEach { note ->
                                                Text(
                                                    note,
                                                    color = Color(0xFFFF9B9B),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }
                                            info.dialogInfo?.let { dialogInfo ->
                                                Spacer(Modifier.height(12.dp))
                                                Text(
                                                    "DIALOG TERDETEKSI",
                                                    color = Color(0xFF9CC7FF),
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                                Text(
                                                    "Jumlah dialog: ${dialogInfo.count} • Berhasil dibaca: ${dialogInfo.dialogs.size}",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    "Basis pointer: 0x${dialogInfo.pointerBase.toString(16).uppercase()}",
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                Button(
                                                    onClick = { createTranslationExport.launch("${info.fieldName.ifBlank { "field" }}_translation.json") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                ) { Text("Ekspor Proyek Terjemahan (JSON)") }
                                                Text(
                                                    "Pilih dialog untuk mengisi draf terjemahan. Tahap ini menyimpan proyek terjemahan terpisah; belum mengubah data game.",
                                                    color = Color(0xFFFFD18A),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(top = 6.dp)
                                                )
                                                dialogInfo.dialogs.forEach { dialog ->
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = 6.dp)
                                                            .background(Color(0xFF242B34), RoundedCornerShape(8.dp))
                                                            .padding(9.dp)
                                                    ) {
                                                        Text(
                                                            "Dialog ${dialog.index} • Offset 0x${dialog.offset.toString(16).uppercase()} • ${dialog.length} byte",
                                                            color = Color(0xFFB8D6FF),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                        Text(
                                                            dialog.preview.ifBlank { "(kosong / hanya kode kontrol)" },
                                                            color = Color.White,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        Text(
                                                            dialog.rawHex,
                                                            color = Color.Gray,
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                    Button(
                                                        onClick = { selectedDialogIndex = dialog.index },
                                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                                                    ) { Text(if (selectedDialogIndex == dialog.index) "Dialog ${dialog.index} dipilih" else "Terjemahkan dialog ${dialog.index}") }
                                                }
                                                selectedDialogIndex?.let { selectedIndex ->
                                                    val selectedDialog = dialogInfo.dialogs.firstOrNull { it.index == selectedIndex }
                                                    if (selectedDialog != null) {
                                                        Text(
                                                            "DRAF TERJEMAHAN • DIALOG ${selectedDialog.index}",
                                                            color = Color(0xFF9CC7FF),
                                                            style = MaterialTheme.typography.labelLarge,
                                                            modifier = Modifier.padding(top = 10.dp)
                                                        )
                                                        Text("Teks asli: ${selectedDialog.preview}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                                        OutlinedTextField(
                                                            value = dialogTranslations[selectedIndex] ?: "",
                                                            onValueChange = { dialogTranslations[selectedIndex] = it },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            minLines = 3,
                                                            label = { Text("Terjemahan Bahasa Indonesia") }
                                                        )
                                                    }
                                                }
                                                dialogInfo.notes.forEach { note ->
                                                    Text(note, color = Color(0xFFFFD18A), style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            Text(
                                                "Tabel entry skrip sudah dihitung, tetapi opcode dan teks dialog belum diterjemahkan atau diubah.",
                                                color = Color(0xFFFFD18A),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(top = 8.dp)
                                            )
                                        }
                                        Text(
                                            "Validasi saat ini mencakup header, pointer, ukuran, dan batas section. Parsing metadata Section 1 bersifat awal dan perlu diuji pada beberapa field.",
                                            color = Color(0xFFFFD18A),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 10.dp)
                                        )
                                    }
                                }
                            }

                            items(
                                items = filteredEntries,
                                key = { "${it.name}_${it.offset}" }
                            ) { entry ->
                                val isSelected = selectedMember?.entry == entry
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) Color(0xFF26384D) else Color(0xFF191E25),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { inspectEntry(entry) }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Offset 0x${entry.offset.toString(16).uppercase()}",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    if (isSelected) {
                                        Text("DIPILIH", color = Color(0xFF9CC7FF), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "Pilih arsip, lalu tekan Baca Arsip. File di dalam flevel.lgp akan muncul di sini.",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp)
                                )
                            }
                        }

                        item {
                            Text(
                                "Mudah Field Editor • Versi 0.8.0",
                                color = Color.Gray,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildTranslationJson(analysis: FieldAnalysis): String {
        val info = analysis.scriptInfo ?: throw IllegalStateException("Metadata field tidak tersedia.")
        val dialogs = info.dialogInfo?.dialogs ?: throw IllegalStateException("Tabel dialog tidak tersedia.")
        val root = org.json.JSONObject()
        root.put("format", "MudahFieldEditor-translation-draft")
        root.put("version", 1)
        root.put("field", info.fieldName)
        root.put("note", "Draf terjemahan saja; belum diterapkan ke data game.")
        val array = org.json.JSONArray()
        dialogs.forEach { dialog ->
            val item = org.json.JSONObject()
            item.put("index", dialog.index)
            item.put("offset", dialog.offset)
            item.put("original", dialog.preview)
            item.put("translation", dialogTranslations[dialog.index] ?: "")
            item.put("original_bytes_hex", dialog.rawBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            array.put(item)
        }
        root.put("dialogs", array)
        return root.toString(2)
    }

    private fun readSelectedArchive() {
        val uri = selectedFile ?: return
        isReading = true
        selectedMember = null
        fieldAnalysis = null
        status = "Membaca header dan tabel indeks..."
        Thread {
            try {
                val length = getArchiveLength(uri)
                val archive = contentResolver.openInputStream(uri)?.use {
                    LgpReader.read(it, length)
                } ?: throw IllegalStateException("Android tidak dapat membuka file ini.")

                runOnUiThread {
                    entries = archive.entries
                    status = "Indeks terbaca: ${archive.fileCount} entri. Pilih entri untuk melihat ukurannya."
                    isReading = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    entries = emptyList()
                    status = "Gagal membaca: ${error.message ?: "Kesalahan tidak diketahui"}"
                    isReading = false
                }
            }
        }.start()
    }

    private fun inspectEntry(entry: LgpEntry) {
        val uri = selectedFile ?: return
        selectedMember = null
        fieldAnalysis = null
        status = "Membaca detail ${entry.name}..."
        Thread {
            try {
                val length = getArchiveLength(uri)
                val member = contentResolver.openInputStream(uri)?.use {
                    LgpReader.readMember(it, entry, length)
                } ?: throw IllegalStateException("Tidak dapat membuka arsip.")
                runOnUiThread {
                    selectedMember = member
                    status = "Detail entri berhasil dibaca."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "Gagal membaca entri: ${error.message ?: "Kesalahan tidak diketahui"}"
                }
            }
        }.start()
    }

    private fun analyzeSelectedMember() {
        val uri = selectedFile ?: return
        val member = selectedMember ?: return
        isAnalyzing = true
        fieldAnalysis = null
        selectedDialogIndex = null
        dialogTranslations.clear()
        status = "Mendekompresi dan memeriksa struktur ${member.entry.name}..."
        Thread {
            try {
                if (member.size > 64L * 1024L * 1024L) {
                    throw IllegalArgumentException("File terlalu besar untuk dianalisis dalam satu proses.")
                }
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    skipFully(input, member.dataOffset)
                    val data = ByteArray(member.size.toInt())
                    var position = 0
                    while (position < data.size) {
                        val read = input.read(data, position, data.size - position)
                        if (read < 0) throw IllegalArgumentException("Data entri terpotong saat dibaca.")
                        position += read
                    }
                    data
                } ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")

                val result = FieldAnalyzer.analyze(bytes)
                runOnUiThread {
                    fieldAnalysis = result
                    isAnalyzing = false
                    status = "Analisis berhasil: header dan 9 section terdeteksi."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isAnalyzing = false
                    status = "Gagal analisis: ${error.message ?: "Format belum dikenali"}"
                }
            }
        }.start()
    }

    private fun skipFully(input: java.io.InputStream, byteCount: Long) {
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

    private fun exportMember(source: Uri, destination: Uri, member: LgpMember) {
        isExporting = true
        status = "Mengekspor ${member.entry.name}..."
        Thread {
            try {
                val input = contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")
                val output = contentResolver.openOutputStream(destination)
                    ?: throw IllegalStateException("Tidak dapat membuat file tujuan.")
                input.use { sourceStream ->
                    output.use { outputStream ->
                        LgpReader.extractMember(sourceStream, outputStream, member)
                    }
                }
                runOnUiThread {
                    isExporting = false
                    status = "Ekspor ${member.entry.name} berhasil."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isExporting = false
                    status = "Gagal mengekspor: ${error.message ?: "Kesalahan tidak diketahui"}"
                }
            }
        }.start()
    }

    private fun getArchiveLength(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size >= 1024L * 1024L -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024L -> String.format("%.2f KB", size / 1024.0)
            else -> "${size} B"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "File tidak dikenal"
    }
}
