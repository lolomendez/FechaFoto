package com.fechafoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Valores originales de un fichero, para poder deshacer. */
data class Backup(
    val uri: String,
    val kind: String,
    var dateTakenMs: Long? = null,
    var exifOriginal: String? = null,
    var exifDigitized: String? = null,
    var exifDateTime: String? = null,
    var exifOffset: String? = null,
    var mvhdSecs: Long? = null,
    var fileTouched: Boolean = false,
) {
    fun toJson() = JSONObject().put("uri", uri).put("kind", kind).put("fileTouched", fileTouched).apply {
        dateTakenMs?.let { put("dateTakenMs", it) }
        exifOriginal?.let { put("exifOriginal", it) }
        exifDigitized?.let { put("exifDigitized", it) }
        exifDateTime?.let { put("exifDateTime", it) }
        exifOffset?.let { put("exifOffset", it) }
        mvhdSecs?.let { put("mvhdSecs", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Backup(
            uri = o.getString("uri"), kind = o.getString("kind"),
            dateTakenMs = if (o.has("dateTakenMs")) o.getLong("dateTakenMs") else null,
            exifOriginal = o.optString("exifOriginal").ifEmpty { null },
            exifDigitized = o.optString("exifDigitized").ifEmpty { null },
            exifDateTime = o.optString("exifDateTime").ifEmpty { null },
            exifOffset = o.optString("exifOffset").ifEmpty { null },
            mvhdSecs = if (o.has("mvhdSecs")) o.getLong("mvhdSecs") else null,
            fileTouched = o.optBoolean("fileTouched"),
        )
    }
}

/** Historial de lotes corregidos (almacenamiento privado de la app). */
class BackupStore(ctx: Context) {
    private val file = File(ctx.filesDir, "backups.json")

    private fun load(): MutableList<List<Backup>> {
        if (!file.exists()) return mutableListOf()
        val arr = JSONArray(file.readText())
        return MutableList(arr.length()) { i ->
            val b = arr.getJSONArray(i)
            List(b.length()) { Backup.fromJson(b.getJSONObject(it)) }
        }
    }

    private fun save(batches: List<List<Backup>>) {
        val arr = JSONArray()
        batches.forEach { batch -> arr.put(JSONArray().also { a -> batch.forEach { a.put(it.toJson()) } }) }
        file.writeText(arr.toString())
    }

    fun push(batch: List<Backup>) { if (batch.isNotEmpty()) save(load().apply { add(batch) }) }
    fun peek(): List<Backup>? = load().lastOrNull()
    fun pop() { save(load().apply { if (isNotEmpty()) removeAt(lastIndex) }) }
    fun count(): Int = load().size
}

class MetadataWriter(ctx: Context) {
    private val cr = ctx.contentResolver

    /** Pone la fecha del nombre como fecha de captura. Debe llamarse con permiso de escritura ya concedido. */
    fun apply(e: MediaEntry): Backup {
        val target = e.nameDate.dateTime
        val backup = Backup(e.uri.toString(), e.kind.name, dateTakenMs = queryDateTaken(e.uri))

        if (e.canWriteFile) {
            if (e.kind == Kind.PHOTO) writeExif(e.uri, target, backup) else writeMp4(e.uri, target, backup)
            backup.fileTouched = true
        }
        val ms = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        cr.update(e.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, ms) }, null, null)
        return backup
    }

    fun restore(b: Backup) {
        val uri = Uri.parse(b.uri)
        if (b.fileTouched) {
            cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                if (b.kind == Kind.PHOTO.name) {
                    ExifInterface(pfd.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, b.exifOriginal)
                        setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, b.exifDigitized)
                        setAttribute(ExifInterface.TAG_DATETIME, b.exifDateTime)
                        setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, b.exifOffset)
                        saveAttributes()
                    }
                } else b.mvhdSecs?.let { Mp4Dates.patch(pfd.fileDescriptor, it) }
            }
        }
        val v = ContentValues()
        if (b.dateTakenMs != null) v.put(MediaStore.MediaColumns.DATE_TAKEN, b.dateTakenMs!!)
        else v.putNull(MediaStore.MediaColumns.DATE_TAKEN)
        cr.update(uri, v, null, null)
    }

    private fun writeExif(uri: Uri, target: LocalDateTime, backup: Backup) {
        cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
            val ex = ExifInterface(pfd.fileDescriptor)
            backup.exifOriginal = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            backup.exifDigitized = ex.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            backup.exifDateTime = ex.getAttribute(ExifInterface.TAG_DATETIME)
            backup.exifOffset = ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val s = target.format(ExifDates.fmt)
            val offset = ZoneId.systemDefault().rules.getOffset(target).toString().replace("Z", "+00:00")
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s)
            ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, s)
            ex.setAttribute(ExifInterface.TAG_DATETIME, s)
            ex.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            ex.saveAttributes()
        }
    }

    private fun writeMp4(uri: Uri, target: LocalDateTime, backup: Backup) {
        cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
            backup.mvhdSecs = Mp4Dates.patch(pfd.fileDescriptor, Mp4Dates.toMp4Secs(target))
        }
    }

    private fun queryDateTaken(uri: Uri): Long? =
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
}
