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

    private val openLgp = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            selectedFileName = queryDisplayName(uri)
            entries = emptyList()
            selectedMember = null
            fieldAnalysis = null
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
                                        Text(
                                            "Validasi saat ini mencakup header, pointer, ukuran, dan batas section. Isi skrip/dialog belum diuraikan.",
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
                                "Mudah Field Editor • Versi 0.5.0",
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
