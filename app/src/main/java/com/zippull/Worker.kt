package com.zippull

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Act(val size: Long) { val got = AtomicLong(0) }

object State {
    @Volatile var url = ""
    @Volatile var zipSize = 0L
    @Volatile var tree: Uri? = null
    @Volatile var selected: List<Item> = emptyList()
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var status = ""
    @Volatile var totalBytes = 0L
    @Volatile var filesTotal = 0
    val done = AtomicLong(0)
    val filesDone = AtomicInteger(0)
    val active = ConcurrentHashMap<String, Act>()
    val failed = CopyOnWriteArrayList<String>()
}

class Saf(private val cr: ContentResolver, private val tree: Uri) {
    class Child(val id: String, val size: Long, val isDir: Boolean)

    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val dirs = HashMap<String, String>()

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun find(parentId: String, name: String): Child? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return Child(
                        c.getString(0),
                        if (c.isNull(2)) -1L else c.getLong(2),
                        c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                }
            }
        }
        return null
    }

    @Synchronized
    fun dirFor(parts: List<String>): String {
        var id = rootId
        var path = ""
        for (p in parts) {
            path += "/$p"
            val parent = id
            id = dirs.getOrPut(path) {
                val ex = find(parent, p)
                if (ex != null && ex.isDir) ex.id
                else DocumentsContract.getDocumentId(
                    DocumentsContract.createDocument(
                        cr, docUri(parent), DocumentsContract.Document.MIME_TYPE_DIR, p
                    )!!
                )
            }
        }
        return id
    }

    fun create(parentId: String, name: String): Uri =
        DocumentsContract.createDocument(cr, docUri(parentId), "application/octet-stream", name)!!

    fun delete(id: String) {
        DocumentsContract.deleteDocument(cr, docUri(id))
    }
}

object Worker {
    fun run(ctx: Context) {
        if (Mode.sevenZ) { Sz.run(ctx); return }
        val cr = ctx.contentResolver
        val saf = Saf(cr, State.tree!!)
        val pool = Executors.newFixedThreadPool(3)
        for (e in State.selected) pool.execute { one(cr, saf, e) }
        pool.shutdown()
        pool.awaitTermination(7, TimeUnit.DAYS)
        State.status = when {
            State.cancel -> "Cancelled"
            State.failed.isNotEmpty() ->
                "Finished with ${State.failed.size} failed file(s):\n" + State.failed.joinToString("\n")
            else -> "Done - all files saved"
        }
    }

    private fun one(cr: ContentResolver, saf: Saf, e: Item) {
        val parts = e.name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty() || State.cancel) return
        val fname = parts.last()
        val act = Act(e.size)
        State.active[e.name] = act
        try {
            if ((e.flags and 1) != 0 || (e.method != 0 && e.method != 8)) {
                State.failed.add("${e.name} (unsupported: encrypted or odd compression)")
                return
            }
            var attempt = 0
            while (!State.cancel) {
                var docId: String? = null
                try {
                    val parent = saf.dirFor(parts.dropLast(1))
                    val old = saf.find(parent, fname)
                    if (old != null && !old.isDir) {
                        if (old.size == e.size) {   // already complete -> resume by skipping
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
                        Zip.extract(State.url, State.zipSize, e, bo, { State.cancel }) { n ->
                            act.got.addAndGet(n.toLong())
                            State.done.addAndGet(n.toLong())
                        }
                        bo.flush()
                    }
                    State.filesDone.incrementAndGet()
                    return
                } catch (ex: Exception) {
                    State.done.addAndGet(-act.got.getAndSet(0))
                    docId?.let { runCatching { saf.delete(it) } }
                    if (State.cancel) return
                    attempt++
                    if (attempt >= 4) {
                        State.failed.add("${e.name} (${ex.message ?: ex.javaClass.simpleName})")
                        return
                    }
                    Thread.sleep(3000L * attempt)
                }
            }
        } finally {
            State.active.remove(e.name)
        }
    }
}
