package com.imagesorter.ui

import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase

class MainActivity : ComponentActivity() {
    /** Se registra al construir la Activity, antes de onStart, como exige la API de resultados. */
    val approvals = ApprovalLauncher(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        val queries = MediaQueries(contentResolver)
        val ops = MediaOps(
            resolver = contentResolver,
            queries = queries,
            dao = db.decisions(),
            attachedVolumes = { MediaStore.getExternalVolumeNames(this) },
            approve = approvals::approve,
        )
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    App(queries, ops, db.decisions(), db.archive())
                }
            }
        }
    }
}
