package com.zippull

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

object Mode { @Volatile var sevenZ = false }

private fun httpOpen(url: String, start: Long, end: Long): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 20000
    c.readTimeout = 30000
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ZipPull")
    c.setRequestProperty("Range", if (end < 0) "bytes=$start-" else "bytes=$start-$end")
    val code = c.responseCode
    if (code != 206) {
        c.disconnect()
        throw IOException("Server did not accept Range request (HTTP $code). This host can't be streamed.")
    }
    return c
}

/** Lets the 7z reader treat a remote file like a local one. Reconnects by itself if the network drops. */
class HttpChannel(private val url: String, private val total: Long) : SeekableByteChannel {
    private var pos = 0L
    private var conn: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var streamPos = -1L
    private var opened = true

    private fun closeStream() {
        try { conn?.disconnect() } catch (ex: Exception) {}
        conn = null
        stream = null
        streamPos = -1
    }

    override fun read(dst: ByteBuffer): Int {
        if (pos >= total) return -1
        val want = minOf(dst.remaining().toLong(), total - pos).toInt()
        if (want <= 0) return 0
        var tries = 0
        while (true) {
            try {
                if (stream == null || streamPos != pos) {
                    closeStream()
                    val c = httpOpen(url, pos, -1)
                    conn = c
                    stream = BufferedInputStream(c.inputStream, 1 shl 20)
                    streamPos = pos
                }
                val buf = ByteArray(want)
                val n = stream!!.read(buf, 0, want)
                if (n < 0) throw IOException("Connection ended early")
                dst.put(buf, 0, n)
                pos += n
                streamPos += n
                return n
            } catch (e: IOException) {
                closeStream()
                if (State.cancel || ++tries > 6) throw e
                Thread.sleep(2000L * tries)
            }
        }
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel { pos = newPosition; return this }
    override fun size(): Long = total
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = opened
    override fun close() { opened = false; closeStream() }
}

object Sz {
    private fun probe(url: String): Pair<Long, ByteArray> {
        val c = httpOpen(url, 0, 5)
        try {
            val cr = c.getHeaderField("Content-Range") ?: throw IOException("No Content-Range header")
            val total = cr.substringAfter('/').trim().toLongOrNull() ?: throw IOException("Unknown file size")
            val b = ByteArray(6)
            DataInputStream(c.inputStream).readFully(b)
            return Pair(total, b)
        } finally { c.disconnect() }
    }

    /** Works for both ZIP and 7z links. */
    fun listAny(url: String): Pair<Long, List<Item>> {
        val (total, h) = probe(url)
        val is7z = h[0] == 0x37.toByte() && h[1] == 0x7A.toByte() && h[2] == 0xBC.toByte() &&
                h[3] == 0xAF.toByte() && h[4] == 0x27.toByte() && h[5] == 0x1C.toByte()
        if (!is7z) {
            val r = Zip.list(url)
            Mode.sevenZ = false
            return r
        }
        val items = ArrayList<Item>()
        SevenZFile(HttpChannel(url, total)).use { sz ->
            for (e in sz.entries) {
                if (!e.isDirectory) items.add(Item(e.name, 100, 0, e.crcValue, 0, e.size, 0))
            }
        }
        Mode.sevenZ = true
        return Pair(total, items)
    }

    fun run(ctx: Context) {
        val cr = ctx.contentResolver
        val saf = Saf(cr, State.tree!!)
        val wanted = HashMap<String, Item>()
        for (w in State.selected) wanted[w.name] = w
        SevenZFile(HttpChannel(State.url, State.zipSize)).use { sz ->
            val buf = ByteArray(256 * 1024)
            while (!State.cancel) {
                val en = sz.nextEntry ?: break
                if (en.isDirectory) continue
                val item = wanted[en.name] ?: continue
                copyOne(cr, saf, sz, item, buf)
            }
        }
        State.status = when {
            State.cancel -> "Cancelled"
            State.failed.isNotEmpty() ->
                "Finished with ${State.failed.size} failed file(s):\n" + State.failed.joinToString("\n")
            else -> "Done - all files saved"
        }
    }

    private fun copyOne(cr: ContentResolver, saf: Saf, sz: SevenZFile, e: Item, buf: ByteArray) {
        val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return
        val fname = parts.last()
        val act = Act(e.size)
        State.active[e.name] = act
        var docId: String? = null
        try {
            val parent = saf.dirFor(parts.dropLast(1))
            val old = saf.find(parent, fname)
            if (old != null && !old.isDir) {
                if (old.size == e.size) {   // already complete, skip it
                    State.done.addAndGet(e.size)
                    act.got.set(e.size)
                    State.filesDone.incrementAndGet()
                    return
                }
                saf.delete(old.id)
            }
            val uri = saf.create(parent, fname)
            docId = DocumentsContract.getDocumentId(uri)
            cr.openOutputStream(uri, "w")!!.use { os ->
                val bo = BufferedOutputStream(os, 1 shl 16)
                while (true) {
                    if (State.cancel) throw IOException("cancelled")
                    val n = sz.read(buf)
                    if (n < 0) break
                    bo.write(buf, 0, n)
                    act.got.addAndGet(n.toLong())
                    State.done.addAndGet(n.toLong())
                }
                bo.flush()
            }
            State.filesDone.incrementAndGet()
        } catch (ex: Exception) {
            State.done.addAndGet(-act.got.getAndSet(0))
            docId?.let { runCatching { saf.delete(it) } }
            if (!State.cancel) State.failed.add("${e.name} (${ex.message ?: ex.javaClass.simpleName})")
        } finally {
            State.active.remove(e.name)
        }
    }
}
