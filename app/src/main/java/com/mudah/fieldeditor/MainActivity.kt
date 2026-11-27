package com.mudah.fieldeditor

import android.net.Uri
import android.app.AlertDialog
import android.provider.DocumentsContract
import android.widget.Toast
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
    private var translationImportStatus by mutableStateOf("")
    private var isReading by mutableStateOf(false)
    private var isExporting by mutableStateOf(false)
    private var isAnalyzing by mutableStateOf(false)
    private var selectedDialogIndex by mutableStateOf<Int?>(null)
    private val dialogTranslations = mutableStateMapOf<Int, String>()
    private val batchTranslations = mutableStateMapOf<String, Map<Int, String>>()

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
            batchTranslations.clear()
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

    private val createTextTranslationExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val analysis = fieldAnalysis
        if (uri != null && analysis != null) {
            try {
                val text = buildTranslationTxt(analysis)
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Tidak dapat membuat file TXT.")
                status = "TXT field berhasil diekspor. Nama file sesuai nama field agar dapat diimpor massal."
            } catch (error: Exception) {
                status = "Gagal mengekspor TXT: ${error.message ?: "kesalahan tidak diketahui"}"
            }
        }
    }

    private val createPatchedFieldExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val analysis = fieldAnalysis
        val translations = dialogTranslations.toMap()
        if (uri != null && analysis != null) {
            exportTranslatedField(uri, analysis, translations)
        }
    }

    private val createPatchedArchiveExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) exportTranslatedArchive(uri)
    }

    private val createBatchArchiveExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) exportTranslatedArchiveBatch(uri)
    }

    private val openBatchTranslationImport = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importBatchTranslationProjects(uris)
    }

    private val openTranslationImport = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val analysis = fieldAnalysis
        if (uri != null && analysis != null) {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("File JSON tidak dapat dibuka.")
                val bytes = input.use { it.readBytes() }
                if (bytes.size > 16 * 1024 * 1024) {
                    throw IllegalArgumentException("Ukuran proyek terlalu besar (maksimal 16 MB).")
                }
                val importedText = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val info = analysis.scriptInfo
                    ?: throw IllegalStateException("Metadata field belum tersedia.")
                val currentDialogs = info.dialogInfo?.dialogs
                    ?: throw IllegalStateException("Tabel dialog belum tersedia.")

                if (!importedText.trimStart().startsWith("{")) {
                    val imported = parseTextTranslationImport(importedText, currentDialogs)
                    dialogTranslations.putAll(imported)
                    translationImportStatus = "Impor TXT berhasil: ${imported.size} draf dialog diterapkan. Pilih dialog untuk melihat isinya."
                    status = translationImportStatus
                } else {
                    val json = org.json.JSONObject(importedText)
                    if (json.optString("format") != "MudahFieldEditor-translation-draft") {
                    throw IllegalArgumentException("Format file bukan proyek terjemahan Mudah Field Editor.")
                }
                if (json.optInt("version", -1) != 1) {
                    throw IllegalArgumentException("Versi proyek tidak didukung.")
                }
                if (json.optString("field") != info.fieldName) {
                    throw IllegalArgumentException("Proyek ini untuk field ${json.optString("field")}, bukan ${info.fieldName}.")
                }

                val sourceDialogs = json.optJSONArray("dialogs")
                    ?: throw IllegalArgumentException("Daftar dialog tidak ditemukan.")
                if (sourceDialogs.length() != currentDialogs.size) {
                    throw IllegalArgumentException("Jumlah dialog proyek tidak cocok dengan field saat ini.")
                }
                val currentByIndex = currentDialogs.associateBy { it.index }
                val imported = linkedMapOf<Int, String>()
                for (i in 0 until sourceDialogs.length()) {
                    val item = sourceDialogs.optJSONObject(i)
                        ?: throw IllegalArgumentException("Data dialog ke-${i + 1} tidak valid.")
                    val index = item.optInt("index", -1)
                    val current = currentByIndex[index]
                        ?: throw IllegalArgumentException("Dialog ${index} tidak ditemukan pada field ini.")
                    if (item.optInt("offset", -1) != current.offset) {
                        throw IllegalArgumentException("Offset dialog ${index} tidak cocok. Impor dibatalkan.")
                    }
                    val expectedHex = current.rawBytes.joinToString("") {
                        "%02X".format(it.toInt() and 0xFF)
                    }
                    if (!item.has("original_bytes_hex") ||
                        !item.optString("original_bytes_hex").equals(expectedHex, ignoreCase = true)
                    ) {
                        throw IllegalArgumentException("Data asli dialog ${index} berbeda. Impor dibatalkan.")
                    }
                    if (imported.containsKey(index)) {
                        throw IllegalArgumentException("Dialog ${index} tercantum lebih dari sekali.")
                    }
                    if (!item.has("translation") || item.isNull("translation")) {
                        throw IllegalArgumentException("Draf dialog ${index} tidak memiliki teks terjemahan.")
                    }
                    imported[index] = item.getString("translation")
                }
                if (imported.size != currentDialogs.size) {
                    throw IllegalArgumentException("Tidak semua dialog tercakup dalam proyek.")
                }
                    dialogTranslations.clear()
                    dialogTranslations.putAll(imported)
                    translationImportStatus = "Impor JSON berhasil: ${imported.size} draf dialog dipulihkan. Pilih dialog untuk melihat isinya."
                    status = translationImportStatus
                }
            } catch (error: Exception) {
                translationImportStatus = "Gagal mengimpor: ${error.message ?: "kesalahan tidak diketahui"}"
                status = translationImportStatus
            }
        } else {
            translationImportStatus = when {
                uri == null -> "Pemilihan file dibatalkan."
                fieldAnalysis == null -> "Analisis field hilang. Analisis ulang field sebelum mengimpor."
                else -> "Impor tidak dapat dimulai."
            }
            status = translationImportStatus
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
                                if (entries.isNotEmpty()) {
                                    Button(
                                        onClick = { openBatchTranslationImport.launch(arrayOf("text/plain", "text/*")) },
                                        enabled = !isExporting,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) { Text("Impor TXT Massal") }
                                    Text(
                                        "Terjemahan TXT dimuat: ${batchTranslations.size} field",
                                        color = Color(0xFF9FE0B2),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Button(
                                        onClick = { createBatchArchiveExport.launch("flevel_translated.lgp") },
                                        enabled = batchTranslations.isNotEmpty() && !isExporting,
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                    ) { Text("Bangun flevel.lgp dari TXT (${batchTranslations.size} field)") }
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
                                                    onClick = { openTranslationImport.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                ) { Text("Impor Translasi (JSON / TXT)") }
                                                if (translationImportStatus.isNotBlank()) {
                                                    Text(
                                                        translationImportStatus,
                                                        color = if (translationImportStatus.startsWith("Gagal")) Color(0xFFFF9B9B) else Color(0xFF9FE0B2),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(top = 6.dp)
                                                    )
                                                }
                                                Button(
                                                    onClick = { createTranslationExport.launch("${info.fieldName.ifBlank { "field" }}_translation.json") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                ) { Text("Ekspor Proyek Terjemahan (JSON)") }
                                                Button(
                                                    onClick = { createTextTranslationExport.launch("${info.fieldName.ifBlank { "field" }}.txt") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                                ) { Text("Ekspor TXT untuk Terjemahan") }
                                                Button(
                                                    onClick = { createPatchedFieldExport.launch("${info.fieldName.ifBlank { "field" }}_translated.bin") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                ) { Text("Terapkan & Ekspor Field (.bin)") }
                                                Button(
                                                    onClick = { createPatchedArchiveExport.launch("flevel_translated.lgp") },
                                                    enabled = selectedFile != null && entries.isNotEmpty() && selectedMember != null && !isExporting,
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                ) { Text("Bangun flevel.lgp (field aktif)") }
                                                Text(
                                                    "Ekspor ini menghasilkan satu member field terkompresi (.bin), belum mengganti entri di flevel.lgp.",
                                                    color = Color(0xFFFFD18A),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(top = 5.dp)
                                                )
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
                                                    if (selectedDialogIndex == dialog.index) {
                                                        Text(
                                                            "DRAF TERJEMAHAN • DIALOG ${dialog.index}",
                                                            color = Color(0xFF9CC7FF),
                                                            style = MaterialTheme.typography.labelLarge,
                                                            modifier = Modifier.padding(top = 10.dp)
                                                        )
                                                        Text("Teks asli: ${dialog.preview}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                                        OutlinedTextField(
                                                            value = dialogTranslations[dialog.index] ?: "",
                                                            onValueChange = { dialogTranslations[dialog.index] = it },
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

    private fun parseTextTranslationImport(
        source: String,
        currentDialogs: List<FieldDialog>
    ): Map<Int, String> {
        val marker = Regex("(?m)^---TEXT(\\d{3,})---\\r?\\n")
        val matches = marker.findAll(source).toList()
        if (matches.isEmpty()) {
            throw IllegalArgumentException("Format TXT tidak dikenali. Gunakan penanda ---TEXT000--- untuk setiap dialog.")
        }

        val currentIndexes = currentDialogs.map { it.index }.toSet()
        val imported = linkedMapOf<Int, String>()
        matches.forEachIndexed { position, match ->
            val textId = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("Nomor TEXT tidak valid.")
            val dialogIndex = textId + 1
            if (dialogIndex !in currentIndexes) {
                throw IllegalArgumentException("TEXT${textId.toString().padStart(3, '0')} tidak tersedia pada field ini.")
            }
            if (imported.containsKey(dialogIndex)) {
                throw IllegalArgumentException("Penanda TEXT${textId.toString().padStart(3, '0')} tercantum lebih dari sekali.")
            }
            val start = match.range.last + 1
            val end = if (position + 1 < matches.size) matches[position + 1].range.first else source.length
            val translated = source.substring(start, end).trimEnd('\r', '\n')
            imported[dialogIndex] = translated
        }
        return imported
    }

    private fun buildTranslationTxt(analysis: FieldAnalysis): String {
        val info = analysis.scriptInfo ?: throw IllegalStateException("Metadata field tidak tersedia.")
        val dialogs = info.dialogInfo?.dialogs ?: throw IllegalStateException("Tabel dialog tidak tersedia.")
        return buildString {
            dialogs.forEach { dialog ->
                append("---TEXT")
                append((dialog.index - 1).toString().padStart(3, '0'))
                append("---\\n")
                append(dialogTranslations[dialog.index] ?: dialog.preview)
                append("\\n")
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

    private fun importBatchTranslationProjects(uris: List<Uri>) {
        val source = selectedFile
        val archiveEntries = entries.toList()
        if (source == null || archiveEntries.isEmpty()) {
            status = "Pilih dan baca flevel.lgp terlebih dahulu."
            return
        }
        isAnalyzing = true
        status = "Memvalidasi ${uris.size} file TXT terhadap flevel.lgp..."
        Thread {
            try {
                val staged = linkedMapOf<String, Map<Int, String>>()
                val knownNames = archiveEntries.groupBy { it.name.lowercase() }
                uris.forEachIndexed { position, uri ->
                    val fileName = queryDisplayName(uri)
                    val fieldName = fileName.substringBeforeLast('.', fileName).trim()
                    require(fieldName.isNotEmpty() && fieldName != fileName) {
                        "Nama file ke-${position + 1} harus memakai nama field, contoh ancnt1.txt."
                    }
                    val matching = knownNames[fieldName.lowercase()]
                        ?: throw IllegalArgumentException("Field '$fieldName' dari file '$fileName' tidak ditemukan di flevel.lgp.")
                    require(matching.size == 1) { "Nama field '$fieldName' ambigu di indeks arsip." }
                    val entry = matching.single()
                    require(!staged.containsKey(entry.name)) { "File TXT untuk field '$fieldName' dipilih lebih dari sekali." }
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("File '$fileName' tidak dapat dibuka.")
                    require(bytes.size <= 16 * 1024 * 1024) { "File '$fileName' melebihi batas 16 MB." }
                    val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                    require(!text.trimStart().startsWith("{")) {
                        "File '$fileName' terdeteksi JSON. Pilih file TXT hasil terjemahan."
                    }
                    val member = contentResolver.openInputStream(source)?.use {
                        LgpReader.readMember(it, entry, getArchiveLength(source))
                    } ?: throw IllegalStateException("Tidak dapat membaca member '$fieldName'.")
                    require(member.size <= 64L * 1024L * 1024L) { "Field '$fieldName' terlalu besar untuk diproses." }
                    val compressed = contentResolver.openInputStream(source)?.use { input ->
                        skipFully(input, member.dataOffset)
                        val data = ByteArray(member.size.toInt())
                        var offset = 0
                        while (offset < data.size) {
                            val read = input.read(data, offset, data.size - offset)
                            if (read < 0) throw IllegalArgumentException("Data field '$fieldName' terpotong.")
                            offset += read
                        }
                        data
                    } ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")
                    val analysis = FieldAnalyzer.analyze(compressed)
                    val info = analysis.scriptInfo
                        ?: throw IllegalArgumentException("Metadata field '$fieldName' tidak terbaca.")
                    require(info.fieldName.equals(fieldName, ignoreCase = true)) {
                        "Nama field file '$fieldName' tidak cocok dengan isi member '${info.fieldName}'."
                    }
                    val currentDialogs = info.dialogInfo?.dialogs
                        ?: throw IllegalArgumentException("Tabel dialog '$fieldName' tidak tersedia.")
                    val imported = parseTextTranslationImport(text, currentDialogs)
                    require(imported.isNotEmpty() && imported.values.any { it.isNotBlank() }) {
                        "File '$fileName' tidak berisi terjemahan yang dapat diterapkan."
                    }
                    staged[entry.name] = imported
                }
                runOnUiThread {
                    batchTranslations.putAll(staged)
                    isAnalyzing = false
                    val translatedCount = staged.values.sumOf { map -> map.values.count { it.isNotBlank() } }
                    status = "Impor TXT massal berhasil: ${staged.size} field tervalidasi, $translatedCount dialog berisi terjemahan. Arsip asli tidak diubah."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isAnalyzing = false
                    status = "Gagal impor TXT massal: ${error.message ?: error.javaClass.simpleName}. Tidak ada file yang diterapkan."
                }
            }
        }.start()
    }

    private fun exportTranslatedArchiveBatch(destination: Uri) {
        val source = selectedFile
        val archiveEntries = entries.toList()
        val projects = batchTranslations.toMap()
        if (source == null || archiveEntries.isEmpty() || projects.isEmpty()) {
            status = "Pilih arsip, baca indeks, lalu impor TXT massal terlebih dahulu."
            return
        }
        isExporting = true
        status = "Membangun dan memvalidasi ${projects.size} field dalam flevel.lgp..."
        Thread {
            var temporary: java.io.File? = null
            try {
                val replacements = linkedMapOf<String, ByteArray>()
                var changedDialogs = 0
                projects.forEach { (entryName, translations) ->
                    val entry = archiveEntries.singleOrNull { it.name == entryName }
                        ?: throw IllegalArgumentException("Field '$entryName' tidak lagi ditemukan di arsip.")
                    val member = contentResolver.openInputStream(source)?.use {
                        LgpReader.readMember(it, entry, getArchiveLength(source))
                    } ?: throw IllegalStateException("Tidak dapat membaca field '$entryName'.")
                    require(member.size <= 64L * 1024L * 1024L) {
                        "Field '$entryName' terlalu besar untuk diproses."
                    }
                    val compressed = contentResolver.openInputStream(source)?.use { input ->
                        skipFully(input, member.dataOffset)
                        val data = ByteArray(member.size.toInt())
                        var offset = 0
                        while (offset < data.size) {
                            val read = input.read(data, offset, data.size - offset)
                            if (read < 0) throw IllegalArgumentException("Data field '$entryName' terpotong.")
                            offset += read
                        }
                        data
                    } ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")
                    val analysis = FieldAnalyzer.analyze(compressed)
                    require(analysis.scriptInfo?.fieldName.equals(entryName, ignoreCase = true)) {
                        "Isi field '$entryName' berubah sejak proyek diimpor."
                    }
                    val result = FieldBinaryWriter.build(analysis, translations)
                    replacements[entryName] = result.compressedBytes
                    changedDialogs += result.changedDialogs
                }
                val archive = LgpArchive(archiveEntries.size, archiveEntries)
                temporary = java.io.File.createTempFile("field-editor-batch-", ".tmp", cacheDir)
                val archiveResult = temporary.outputStream().use { stream ->
                    LgpArchiveWriter.build(
                        openSource = {
                            contentResolver.openInputStream(source)
                                ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")
                        },
                        archive = archive,
                        replacements = replacements,
                        output = stream
                    )
                }
                LgpArchiveWriter.validate(
                    openSource = {
                        contentResolver.openInputStream(source)
                            ?: throw IllegalStateException("Tidak dapat membuka arsip sumber untuk validasi.")
                    },
                    archive = archive,
                    replacements = replacements,
                    outputFile = temporary
                )
                require(temporary.length() == archiveResult.outputBytes) {
                    "Ukuran file sementara tidak cocok dengan hasil rekonstruksi."
                }
                contentResolver.openOutputStream(destination)?.use { output ->
                    temporary.inputStream().use { it.copyTo(output) }
                } ?: throw IllegalStateException("Tidak dapat membuat file tujuan.")
                runOnUiThread {
                    isExporting = false
                    status = "LGP massal lolos validasi: ${archiveResult.entryCount} entri diperiksa, ${replacements.size} field diganti, $changedDialogs dialog berubah, ${formatSize(archiveResult.outputBytes)}. Arsip asli tidak diubah."
                }
            } catch (error: Exception) {
                try { DocumentsContract.deleteDocument(contentResolver, destination) } catch (_: Exception) {}
                runOnUiThread {
                    isExporting = false
                    val detail = error.message ?: error.javaClass.simpleName
                    status = "Gagal membangun LGP massal: $detail. Arsip asli tetap aman."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Rekonstruksi massal gagal")
                        .setMessage("$detail\\n\\nFile hasil tidak digunakan dan dihapus jika penyedia dokumen mengizinkan. Arsip asli tidak diubah.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                temporary?.delete()
            }
        }.start()
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

    private fun exportTranslatedField(
        destination: Uri,
        analysis: FieldAnalysis,
        translations: Map<Int, String>
    ) {
        isExporting = true
        status = "Menerapkan terjemahan dan memvalidasi ulang struktur field..."
        Thread {
            try {
                val result = FieldBinaryWriter.build(analysis, translations)
                contentResolver.openOutputStream(destination)?.use { output ->
                    output.write(result.compressedBytes)
                } ?: throw IllegalStateException("Tidak dapat membuat file tujuan.")
                runOnUiThread {
                    isExporting = false
                    status = "Field berhasil dibuat dan divalidasi. ${result.changedDialogs} dialog berubah; ukuran data dialog ${result.oldDialogBytes} → ${result.newDialogBytes} byte. File hasil masih berupa member field terkompresi, belum arsip LGP lengkap."
                }
            } catch (error: Exception) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, destination)
                } catch (_: Exception) {
                    // Beberapa penyedia dokumen tidak mendukung penghapusan; tampilkan error utama.
                }
                runOnUiThread {
                    isExporting = false
                    val detail = error.message ?: error.javaClass.simpleName
                    status = "Gagal menerapkan terjemahan: $detail. File game asli tetap aman."
                    Toast.makeText(
                        this@MainActivity,
                        "Ekspor field gagal. Lihat detail kesalahan.",
                        Toast.LENGTH_LONG
                    ).show()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ekspor field gagal")
                        .setMessage("$detail\n\nFile hasil kosong telah dihapus jika penyedia dokumen mengizinkan. File game asli tidak diubah.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun exportTranslatedArchive(destination: Uri) {
        val source = selectedFile
        val archiveEntries = entries.toList()
        val member = selectedMember
        val analysis = fieldAnalysis
        val translations = dialogTranslations.toMap()
        if (source == null || archiveEntries.isEmpty() || member == null || analysis == null) {
            status = "Pilih dan analisis satu field terlebih dahulu."
            return
        }

        isExporting = true
        status = "Membangun dan memvalidasi seluruh entri flevel.lgp..."
        Thread {
            var temporary: java.io.File? = null
            try {
                val fieldResult = FieldBinaryWriter.build(analysis, translations)
                val archive = LgpArchive(archiveEntries.size, archiveEntries)
                val replacements = mapOf(member.entry.name to fieldResult.compressedBytes)
                temporary = java.io.File.createTempFile("field-editor-lgp-", ".tmp", cacheDir)

                val archiveResult = temporary.outputStream().use { stream ->
                    LgpArchiveWriter.build(
                        openSource = {
                            contentResolver.openInputStream(source)
                                ?: throw IllegalStateException("Tidak dapat membuka arsip sumber.")
                        },
                        archive = archive,
                        replacements = replacements,
                        output = stream
                    )
                }

                LgpArchiveWriter.validate(
                    openSource = {
                        contentResolver.openInputStream(source)
                            ?: throw IllegalStateException("Tidak dapat membuka arsip sumber untuk validasi.")
                    },
                    archive = archive,
                    replacements = replacements,
                    outputFile = temporary
                )
                if (temporary.length() != archiveResult.outputBytes) {
                    throw IllegalStateException("Ukuran file sementara tidak cocok dengan hasil rekonstruksi.")
                }

                contentResolver.openOutputStream(destination)?.use { output ->
                    temporary.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Tidak dapat membuat file tujuan.")

                runOnUiThread {
                    isExporting = false
                    status = "LGP lolos validasi penuh: ${archiveResult.entryCount} entri diperiksa, 1 field diganti, ${formatSize(archiveResult.outputBytes)}. Arsip asli tidak diubah."
                }
            } catch (error: Exception) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, destination)
                } catch (_: Exception) {
                }
                runOnUiThread {
                    isExporting = false
                    val detail = error.message ?: error.javaClass.simpleName
                    status = "Gagal membangun atau memvalidasi LGP: $detail. Arsip asli tetap aman."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Rekonstruksi LGP gagal")
                        .setMessage("$detail\n\nFile hasil tidak digunakan dan dihapus jika penyedia dokumen mengizinkan. Arsip game asli tidak diubah.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                temporary?.delete()
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
