package com.fechafoto

import android.system.Os
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneId

/** Parchea in situ (sin recodificar) las fechas de creación de mvhd / tkhd / mdhd de un MP4/MOV/3GP. */
object Mp4Dates {
    private const val EPOCH_1904_TO_1970 = 2_082_844_800L

    fun toMp4Secs(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toEpochSecond() + EPOCH_1904_TO_1970

    /** Devuelve la fecha de creación original del mvhd (segundos desde 1904) o null si no hay. */
    fun patch(fd: FileDescriptor, secs: Long): Long? {
        var firstOld: Long? = null

        fun read(pos: Long, len: Int): ByteBuffer? {
            val a = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = Os.pread(fd, a, got, len - got, pos + got)
                if (n <= 0) return null
                got += n
            }
            return ByteBuffer.wrap(a)
        }

        fun write(pos: Long, b: ByteArray) {
            var done = 0
            while (done < b.size) done += Os.pwrite(fd, b, done, b.size - done, pos + done)
        }

        fun walk(start: Long, end: Long) {
            var pos = start
            while (pos + 8 <= end) {
                val h = read(pos, 8) ?: return
                var size = h.int.toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { h.get(it) }.toString(Charsets.US_ASCII)
                var hdr = 8L
                if (size == 1L) {
                    hdr = 16
                    size = read(pos + 8, 8)?.long ?: return
                } else if (size == 0L) size = end - pos
                if (size < hdr || pos + size > end) return

                when (type) {
                    "moov", "trak", "mdia" -> walk(pos + hdr, pos + size)
                    "mvhd", "tkhd", "mdhd" -> {
                        val body = pos + hdr
                        val version = read(body, 1)?.get(0)?.toInt() ?: return
                        val off = body + 4 // tras version(1)+flags(3)
                        if (version == 1) {
                            if (type == "mvhd" && firstOld == null) firstOld = read(off, 8)?.long
                            write(off, ByteBuffer.allocate(16).putLong(secs).putLong(secs).array())
                        } else {
                            if (type == "mvhd" && firstOld == null)
                                firstOld = read(off, 4)?.int?.toLong()?.and(0xFFFFFFFFL)
                            write(off, ByteBuffer.allocate(8).putInt(secs.toInt()).putInt(secs.toInt()).array())
                        }
                    }
                }
                pos += size
            }
        }

        walk(0, Os.fstat(fd).st_size)
        return firstOld
    }
}
