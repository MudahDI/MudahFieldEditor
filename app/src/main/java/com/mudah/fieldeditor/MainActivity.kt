package com.mudah.fieldeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    private var status by mutableStateOf("Pilih flevel.lgp untuk mulai membaca.")
    private var isReading by mutableStateOf(false)

    private val openLgp = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            selectedFileName = queryDisplayName(uri)
            entries = emptyList()
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "MUDAH FIELD EDITOR",
                            color = Color(0xFF9CC7FF),
                            fontSize = 12.sp,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Final Fantasy VII",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { openLgp.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pilih flevel.lgp")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = selectedFileName,
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { readSelectedArchive() },
                            enabled = selectedFile != null && !isReading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isReading) "Sedang membaca..." else "Baca Arsip")
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = status,
                            color = if (status.startsWith("Gagal")) Color(0xFFFF9B9B) else Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (entries.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("DAFTAR FILE", color = Color(0xFF9CC7FF), letterSpacing = 1.sp)
                                Text("${entries.size} entri", color = Color.LightGray)
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFF191E25), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                items(entries) { entry ->
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                        Text(entry.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Offset: 0x${entry.offset.toString(16).uppercase()}",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Versi 0.2.0 • LGP Reader",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    private fun readSelectedArchive() {
        val uri = selectedFile ?: return
        isReading = true
        status = "Membaca header dan tabel indeks..."
        Thread {
            try {
                val length = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
                val archive = contentResolver.openInputStream(uri)?.use {
                    LgpReader.read(it, length)
                } ?: throw IllegalStateException("Android tidak dapat membuka file ini.")

                runOnUiThread {
                    entries = archive.entries
                    status = "Berhasil membaca indeks LGP. Belum ada data yang diubah."
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
