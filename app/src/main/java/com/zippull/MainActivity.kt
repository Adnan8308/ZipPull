package com.zippull

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.widget.*

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun fmtTime(s: Long): String = "%dh %02dm".format(s / 3600, (s % 3600) / 60)

class MainActivity : Activity() {
    private lateinit var urlBox: EditText
    private lateinit var list: ListView
    private lateinit var folderLbl: TextView
    private lateinit var info: TextView
    private lateinit var bar: ProgressBar
    private lateinit var goBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private var shown: List<Item> = emptyList()
    private var statusShown = true
    private var lastBytes = 0L
    private var lastT = 0L
    private var speed = 0.0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val accent = Color.parseColor("#7C6CF0")
        val muted = Color.parseColor("#9A98B0")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12111A"))
            setPadding(dp(16), dp(40), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "ZipPull"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Download what's inside a ZIP - not the ZIP."; setTextColor(muted)
        })
        urlBox = EditText(this).apply {
            hint = "Direct link to .zip"; setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); setSingleLine(true)
        }
        root.addView(urlBox)

        val row1 = LinearLayout(this)
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        row1.addView(Button(this).apply { text = "Load files"; setOnClickListener { load() } }, lp())
        row1.addView(Button(this).apply {
            text = "Choose folder"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1) }
        }, lp())
        root.addView(row1)

        folderLbl = TextView(this).apply { text = "Save to: (not chosen)"; setTextColor(muted) }
        root.addView(folderLbl)

        val row2 = LinearLayout(this)
        row2.addView(Button(this).apply {
            text = "Select all"; setOnClickListener { for (i in shown.indices) list.setItemChecked(i, true) }
        }, lp())
        row2.addView(Button(this).apply {
            text = "Select none"; setOnClickListener { for (i in shown.indices) list.setItemChecked(i, false) }
        }, lp())
        root.addView(row2)

        list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(accent)
        }
        root.addView(bar)
        info = TextView(this).apply { setTextColor(Color.WHITE); text = "Paste a direct ZIP link, then tap Load files." }
        root.addView(info)
        goBtn = Button(this).apply { text = "Download selected"; setOnClickListener { start() } }
        root.addView(goBtn)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) {
            val u = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            State.tree = u
            folderLbl.text = "Save to: " + Uri.decode(u.lastPathSegment ?: u.toString())
        }
    }

    private fun load() {
        val u = urlBox.text.toString().trim()
        if (u.isEmpty()) return
        info.text = "Reading ZIP index..."
        Thread {
            try {
                val (size, items) = Zip.list(u)
                State.url = u
                State.zipSize = size
                val sorted = items.sortedBy { it.name }
                runOnUiThread {
                    shown = sorted
                    list.adapter = ArrayAdapter(
                        this, android.R.layout.simple_list_item_multiple_choice,
                        sorted.map { "${it.name}\n${fmt(it.size)}" }
                    )
                    for (i in sorted.indices) list.setItemChecked(i, true)
                    info.text = "${sorted.size} files, ${fmt(sorted.sumOf { it.size })} extracted (ZIP is ${fmt(size)})"
                }
            } catch (t: Throwable) {
                runOnUiThread { info.text = "Couldn't read ZIP: ${t.message}" }
            }
        }.start()
    }

    private fun start() {
        if (State.running) { State.cancel = true; return }
        if (State.tree == null) { info.text = "Choose a destination folder first"; return }
        val sel = shown.filterIndexed { i, _ -> list.isItemChecked(i) }
        if (sel.isEmpty()) { info.text = "Nothing selected"; return }
        State.selected = sel
        State.totalBytes = sel.sumOf { it.size }
        State.filesTotal = sel.size
        State.done.set(0); State.filesDone.set(0)
        State.failed.clear(); State.active.clear()
        State.cancel = false; State.status = ""
        State.running = true
        statusShown = false; speed = 0.0; lastBytes = 0; lastT = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        startForegroundService(Intent(this, DownloadService::class.java))
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (State.running) {
                val now = SystemClock.elapsedRealtime()
                val d = State.done.get()
                val dt = (now - lastT) / 1000.0
                if (dt > 0) {
                    val inst = (d - lastBytes) / dt
                    speed = if (speed == 0.0) inst else speed * 0.8 + inst * 0.2
                }
                lastBytes = d; lastT = now
                val tot = State.totalBytes
                bar.progress = if (tot > 0) (d * 1000 / tot).toInt() else 0
                val eta = if (speed > 1) fmtTime(((tot - d) / speed).toLong()) else "..."
                val sb = StringBuilder()
                sb.append("${fmt(d)} of ${fmt(tot)} (${bar.progress / 10}%)\n")
                sb.append("${fmt(speed.toLong())}/s - $eta left - files ${State.filesDone.get()}/${State.filesTotal}\n")
                for ((n, a) in State.active.entries.take(4)) {
                    val pct = if (a.size > 0) a.got.get() * 100 / a.size else 0
                    sb.append("${n.substringAfterLast('/')}  $pct%\n")
                }
                info.text = sb
                goBtn.text = "Cancel"
            } else {
                goBtn.text = "Download selected"
                if (!statusShown && State.status.isNotEmpty()) {
                    info.text = State.status
                    statusShown = true
                }
            }
            ui.postDelayed(this, 500)
        }
    }
}
