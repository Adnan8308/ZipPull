package com.zippull

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class Item(
    val name: String, val method: Int, val flags: Int, val crc: Long,
    val comp: Long, val size: Long, val offset: Long
)

private fun ByteBuffer.u16(i: Int) = getShort(i).toInt() and 0xFFFF
private fun ByteBuffer.u32(i: Int) = getInt(i).toLong() and 0xFFFFFFFFL

private class Limited(private val src: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = src.read()
        if (r >= 0) left--
        return r
    }
    override fun read(b: ByteArray, o: Int, l: Int): Int {
        if (left <= 0) return -1
        val r = src.read(b, o, minOf(l.toLong(), left).toInt())
        if (r > 0) left -= r
        return r
    }
}

object Zip {
    private fun open(url: String, start: Long, end: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ZipPull")
        c.setRequestProperty("Range", "bytes=$start-$end")
        val code = c.responseCode
        if (code != 206) {
            c.disconnect()
            throw IOException("Server did not accept Range request (HTTP $code). This host can't be streamed.")
        }
        return c
    }

    private fun totalSize(url: String): Long {
        val c = open(url, 0, 0)
        try {
            val cr = c.getHeaderField("Content-Range") ?: throw IOException("No Content-Range header")
            return cr.substringAfter('/').trim().toLongOrNull() ?: throw IOException("Unknown file size")
        } finally { c.disconnect() }
    }

    private fun read(url: String, start: Long, end: Long): ByteArray {
        val c = open(url, start, end)
        try {
            val buf = ByteArray((end - start + 1).toInt())
            DataInputStream(c.inputStream).readFully(buf)
            return buf
        } finally { c.disconnect() }
    }

    /** Reads only the ZIP's central directory. Returns (zip size, entries). */
    fun list(url: String): Pair<Long, List<Item>> {
        val total = totalSize(url)
        val tailLen = minOf(total, 131072L)
        val tail = read(url, total - tailLen, total - 1)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var e = tail.size - 22
        while (e >= 0 && tb.getInt(e) != 0x06054b50) e--
        if (e < 0) throw IOException("Not a ZIP file (end record not found)")

        var count = tb.u16(e + 10).toLong()
        var cdSize = tb.u32(e + 12)
        var cdOff = tb.u32(e + 16)
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            val l = e - 20
            if (l < 0 || tb.getInt(l) != 0x07064b50) throw IOException("ZIP64 locator not found")
            val z64 = tb.getLong(l + 8)
            val z = ByteBuffer.wrap(read(url, z64, z64 + 55)).order(ByteOrder.LITTLE_ENDIAN)
            if (z.getInt(0) != 0x06064b50) throw IOException("Bad ZIP64 end record")
            count = z.getLong(32); cdSize = z.getLong(40); cdOff = z.getLong(48)
        }
        if (cdSize > 256L * 1024 * 1024) throw IOException("Central directory too large")
        val cd = read(url, cdOff, cdOff + cdSize - 1)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

        val out = ArrayList<Item>()
        var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
            val flags = b.u16(p + 8)
            val method = b.u16(p + 10)
            val crc = b.u32(p + 16)
            var comp = b.u32(p + 20)
            var size = b.u32(p + 24)
            val nl = b.u16(p + 28)
            val el = b.u16(p + 30)
            val cl = b.u16(p + 32)
            var off = b.u32(p + 42)
            val name = String(cd, p + 46, nl, Charsets.UTF_8)
            var q = p + 46 + nl
            val qEnd = q + el
            while (q + 4 <= qEnd) {
                val id = b.u16(q)
                val len = b.u16(q + 2)
                if (id == 1) {
                    var r = q + 4
                    if (size == 0xFFFFFFFFL) { size = b.getLong(r); r += 8 }
                    if (comp == 0xFFFFFFFFL) { comp = b.getLong(r); r += 8 }
                    if (off == 0xFFFFFFFFL) { off = b.getLong(r); r += 8 }
                }
                q += 4 + len
            }
            if (!name.endsWith("/")) out.add(Item(name, method, flags, crc, comp, size, off))
            p += 46 + nl + el + cl
        }
        return Pair(total, out)
    }

    /** Streams one entry from the server, inflates it on the fly and writes it to [sink]. */
    fun extract(
        url: String, total: Long, e: Item, sink: OutputStream,
        cancelled: () -> Boolean, onBytes: (Int) -> Unit
    ) {
        val end = minOf(total - 1, e.offset + 30L + 131072L + e.comp)
        val c = open(url, e.offset, end)
        val inf = Inflater(true)
        try {
            val ins = DataInputStream(BufferedInputStream(c.inputStream, 1 shl 16))
            val hdr = ByteArray(30)
            ins.readFully(hdr)
            val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            if (hb.getInt(0) != 0x04034b50) throw IOException("Bad local header")
            ins.readFully(ByteArray(hb.u16(26) + hb.u16(28)))

            val limited = Limited(ins, e.comp)
            val src: InputStream = if (e.method == 8) InflaterInputStream(limited, inf, 65536) else limited
            val crc = CRC32()
            val buf = ByteArray(256 * 1024)
            var written = 0L
            while (true) {
                if (cancelled()) throw IOException("cancelled")
                val n = src.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                crc.update(buf, 0, n)
                written += n
                onBytes(n)
            }
            if (written != e.size) throw IOException("Size mismatch")
            if (crc.value != e.crc) throw IOException("CRC mismatch")
        } finally {
            inf.end()
            c.disconnect()
        }
    }
}
