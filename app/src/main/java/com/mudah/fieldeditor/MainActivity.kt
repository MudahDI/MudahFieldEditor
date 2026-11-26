package com.mudah.fieldeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    private val openLgp = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            selectedFileName = queryDisplayName(uri)
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
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "MUDAH",
                            color = Color(0xFF9CC7FF),
                            fontSize = 14.sp,
                            letterSpacing = 4.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Field Editor",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Final Fantasy VII",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(40.dp))
                        Button(
                            onClick = {
                                openLgp.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pilih flevel.lgp")
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF191E25),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "FILE TERPILIH",
                                color = Color(0xFF9CC7FF),
                                fontSize = 11.sp,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = selectedFileName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (selectedFile != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "File berhasil dipilih. Pembacaan arsip akan tersedia pada tahap berikutnya.",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                        Text(
                            text = "Versi 0.1.0 • Android Native",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "File tidak dikenal"
    }
}
