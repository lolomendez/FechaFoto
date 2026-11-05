package com.fechafoto

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class MediaScanner(private val ctx: Context) {
    private val cr = ctx.contentResolver

    private data class Candidate(val uri: Uri, val name: String, val mime: String, val kind: Kind, val nameDate: NameDate)

    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            val candidates = query(Kind.PHOTO) + query(Kind.VIDEO)
            val out = ArrayList<MediaEntry>(candidates.size)
            candidates.forEachIndexed { i, c ->
                ensureActive()
                onProgress(i, candidates.size)
                val meta = if (c.kind == Kind.PHOTO) photoDate(c.uri) else videoDate(c.uri)
                out += MediaEntry(
                    uri = c.uri, name = c.name, kind = c.kind, nameDate = c.nameDate,
                    metaDate = meta, status = status(c.nameDate, meta),
                    canWriteFile = canWrite(c.kind, c.mime),
                )
            }
            onProgress(candidates.size, candidates.size)
            out
        }

    private fun query(kind: Kind): List<Candidate> {
        val coll = if (kind == Kind.PHOTO)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = mutableListOf<Candidate>()
        cr.query(
            coll,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val nd = NameDateParser.parse(name) ?: continue   // sin fecha en el nombre: se ignora
                out += Candidate(ContentUris.withAppendedId(coll, c.getLong(0)), name, c.getString(2) ?: "", kind, nd)
            }
        }
        return out
    }

    private fun photoDate(uri: Uri): LocalDateTime? = runCatching {
        cr.openInputStream(uri)?.use { ins ->
            val ex = ExifInterface(ins)
            listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME)
                .firstNotNullOfOrNull { ExifDates.parse(ex.getAttribute(it)) }
        }
    }.getOrNull()

    private fun videoDate(uri: Uri): LocalDateTime? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            LocalDateTime.parse(s.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                .takeIf { it.year >= 1990 }
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun status(nd: NameDate, meta: LocalDateTime?): Status = when {
        meta == null -> Status.NO_METADATA
        !nd.hasTime ->
            if (meta.toLocalDate() == nd.dateTime.toLocalDate()) Status.MATCH else Status.MISMATCH
        else ->
            if (abs(Duration.between(meta, nd.dateTime).seconds) <= 120) Status.MATCH else Status.MISMATCH
    }

    private fun canWrite(kind: Kind, mime: String) = when (kind) {
        Kind.PHOTO -> mime in setOf("image/jpeg", "image/png", "image/webp")
        Kind.VIDEO -> mime in setOf("video/mp4", "video/quicktime", "video/3gpp", "video/x-m4v")
    }
}
