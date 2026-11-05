package com.fechafoto

import android.net.Uri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Kind { PHOTO, VIDEO }
enum class Status { MATCH, MISMATCH, NO_METADATA }

data class NameDate(val dateTime: LocalDateTime, val hasTime: Boolean)

data class MediaEntry(
    val uri: Uri,
    val name: String,
    val kind: Kind,
    val nameDate: NameDate,
    val folder: String,
    val metaDate: LocalDateTime?,
    val status: Status,
    /** true si se puede escribir el metadato dentro del fichero (si no, solo se actualiza la galería) */
    val canWriteFile: Boolean,
)

data class FolderInfo(val path: String, val count: Int)

object ExifDates {
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    fun parse(s: String?): LocalDateTime? =
        s?.trim()?.let { runCatching { LocalDateTime.parse(it, fmt) }.getOrNull() }
            ?.takeIf { it.year >= 1990 }
}
