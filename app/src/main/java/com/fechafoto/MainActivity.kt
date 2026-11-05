package com.fechafoto

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private val shown = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

private fun Status.label() = when (this) {
    Status.MATCH -> "✅ Coincide"
    Status.MISMATCH -> "⚠️ No coincide"
    Status.NO_METADATA -> "❌ Sin metadatos"
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) vm.scan() else vm.message = "Hace falta permiso para leer fotos y vídeos."
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onWriteResult(it.resultCode == Activity.RESULT_OK)
    }
    fun launch(sender: IntentSender?) {
        if (sender == null) vm.message = "No hay nada que procesar." else
            writeLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    val counts = vm.entries.groupingBy { it.status }.eachCount()
    val visible = vm.entries.filter { vm.filter == null || it.status == vm.filter }

    Scaffold(
        bottomBar = {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.selectAllFixable() }, modifier = Modifier.weight(1f)) { Text("Marcar corregibles") }
                    OutlinedButton(onClick = { vm.clearSelection() }, modifier = Modifier.weight(1f)) { Text("Desmarcar") }
                }
                Button(
                    onClick = { launch(vm.requestFix()) },
                    enabled = vm.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Regenerar fecha desde el nombre (${vm.selected.size})") }
                if (vm.undoBatches > 0) TextButton(onClick = { launch(vm.requestUndo()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deshacer último lote")
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Button(onClick = { permLauncher.launch(perms) }, enabled = !vm.scanning, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.scanning) "Analizando…" else "Analizar fotos y vídeos")
            }
            if (vm.scanning) {
                val (d, t) = vm.progress
                LinearProgressIndicator(progress = { if (t == 0) 0f else d / t.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text("$d / $t", style = MaterialTheme.typography.bodySmall)
            }
            vm.message?.let { Text(it, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.primary) }

            if (vm.entries.isNotEmpty()) {
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("Todos ${vm.entries.size}") })
                    Status.values().forEach { s ->
                        FilterChip(
                            selected = vm.filter == s,
                            onClick = { vm.filter = if (vm.filter == s) null else s },
                            label = { Text("${s.label().take(2)} ${counts[s] ?: 0}") }
                        )
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visible, key = { it.uri.toString() }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = e.status != Status.MATCH) { vm.toggle(e.uri) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = e.uri in vm.selected, onCheckedChange = { vm.toggle(e.uri) }, enabled = e.status != Status.MATCH)
                        Thumb(e)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(e.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Nombre: ${e.nameDate.dateTime.format(shown)}" + if (!e.nameDate.hasTime) " (solo día)" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("Metadatos: ${e.metaDate?.format(shown) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                e.status.label() + if (!e.canWriteFile) " · solo galería" else "",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(e: MediaEntry) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, e.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(e.uri, Size(128, 128), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(56.dp).background(Color.LightGray)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
