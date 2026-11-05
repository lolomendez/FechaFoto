package com.fechafoto

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cr = app.contentResolver
    private val scanner = MediaScanner(app)
    private val writer = MetadataWriter(app)
    private val store = BackupStore(app)

    var entries by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var selected by mutableStateOf<Set<Uri>>(emptySet()); private set
    var filter by mutableStateOf<Status?>(null)
    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf(0 to 0); private set
    var message by mutableStateOf<String?>(null)
    var undoBatches by mutableIntStateOf(store.count()); private set

    var folders by mutableStateOf<List<FolderInfo>>(emptyList()); private set
    var folderSelection by mutableStateOf<Set<String>>(emptySet()); private set
    var showFolders by mutableStateOf(false)
    var loadingFolders by mutableStateOf(false); private set

    private var scanJob: Job? = null
    private var pendingFix: List<MediaEntry>? = null
    private var pendingUndo: List<Backup>? = null

    fun openFolders() {
        showFolders = true
        viewModelScope.launch {
            loadingFolders = true
            folders = scanner.listFolders()
            val all = folders.map { it.path }.toSet()
            folderSelection = if (folderSelection.isEmpty()) all else folderSelection intersect all
            loadingFolders = false
        }
    }

    fun toggleFolder(p: String) { folderSelection = if (p in folderSelection) folderSelection - p else folderSelection + p }
    fun setAllFolders(all: Boolean) { folderSelection = if (all) folders.map { it.path }.toSet() else emptySet() }

    fun scan() {
        showFolders = false
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning = true; message = null; selected = emptySet()
            entries = scanner.scan(folderSelection.takeIf { it.isNotEmpty() }) { d, t -> progress = d to t }
            scanning = false
            if (entries.isEmpty()) message = "No hay fotos ni vídeos con fecha en el nombre."
        }
    }

    fun toggle(uri: Uri) { selected = if (uri in selected) selected - uri else selected + uri }
    fun selectAllFixable() { selected = entries.filter { it.status != Status.MATCH }.map { it.uri }.toSet() }
    fun clearSelection() { selected = emptySet() }

    fun requestFix(): IntentSender? {
        val list = entries.filter { it.uri in selected && it.status != Status.MATCH }.take(BATCH_MAX)
        if (list.isEmpty()) return null
        pendingFix = list; pendingUndo = null
        return MediaStore.createWriteRequest(cr, list.map { it.uri }).intentSender
    }

    fun requestUndo(): IntentSender? {
        val batch = store.peek() ?: return null
        pendingUndo = batch; pendingFix = null
        return MediaStore.createWriteRequest(cr, batch.map { Uri.parse(it.uri) }).intentSender
    }

    fun onWriteResult(granted: Boolean) {
        val fix = pendingFix; val undo = pendingUndo
        pendingFix = null; pendingUndo = null
        if (!granted) { message = "Operación cancelada."; return }
        viewModelScope.launch {
            if (fix != null) {
                val done = mutableListOf<Backup>(); var failed = 0
                withContext(Dispatchers.IO) {
                    fix.forEach { e -> runCatching { writer.apply(e) }.onSuccess { done += it }.onFailure { failed++ } }
                    store.push(done)
                }
                val ok = done.map { it.uri }.toSet()
                entries = entries.map {
                    if (it.uri.toString() in ok) it.copy(metaDate = it.nameDate.dateTime, status = Status.MATCH) else it
                }
                selected = emptySet(); undoBatches = store.count()
                message = "Corregidas ${done.size}" + if (failed > 0) " · $failed con error" else ""
            } else if (undo != null) {
                var failed = 0
                withContext(Dispatchers.IO) {
                    undo.forEach { b -> runCatching { writer.restore(b) }.onFailure { failed++ } }
                    store.pop()
                }
                undoBatches = store.count()
                message = "Deshecho el último lote (${undo.size})" + if (failed > 0) " · $failed con error" else ""
                scan()
            }
        }
    }

    private companion object { const val BATCH_MAX = 500 }
}
