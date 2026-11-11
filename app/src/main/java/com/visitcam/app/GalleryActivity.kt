package com.visitcam.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MediaItem(val uri: Uri, val video: Boolean, val date: Long, val name: String = "")

fun shareUri(ctx: Context, uri: Uri, video: Boolean) = shareMime(ctx, uri, if (video) "video/mp4" else "image/jpeg")

fun shareMime(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

@Suppress("DEPRECATION")
fun thumbOf(ctx: Context, uri: Uri, video: Boolean, size: Int): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(size, size), null)
    else {
        val id = ContentUris.parseId(uri)
        if (video) MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }
} catch (e: Exception) { null }

fun loadMedia(ctx: Context): List<MediaItem> {
    val brand = ctx.getString(R.string.brand_name)
    val list = mutableListOf<MediaItem>()
    fun q(base: Uri, video: Boolean) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME)
        val sel: String; val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) { sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"; args = arrayOf("%/$brand/%") }
        else { sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"; args = arrayOf("${brand}_%") }
        ctx.contentResolver.query(base, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) list.add(MediaItem(ContentUris.withAppendedId(base, c.getLong(0)), video, c.getLong(1), c.getString(2) ?: ""))
        }
    }
    q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return list.sortedByDescending { it.date }
}

class GalleryActivity : BaseActivity() {
    private sealed class Row {
        class Header(val label: String, val items: List<MediaItem>) : Row()
        class Cell(val item: MediaItem) : Row()
    }

    private var all = listOf<MediaItem>()
    private var tabVideo = false
    private var selMode = false
    private val selected = linkedSetOf<String>()
    private val rows = mutableListOf<Row>()
    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val cache = android.util.LruCache<String, Bitmap>(90)
    private lateinit var rv: androidx.recyclerview.widget.RecyclerView
    private lateinit var titleTv: TextView
    private lateinit var selAllTv: TextView
    private lateinit var shareTv: TextView
    private lateinit var delTv: TextView
    private lateinit var tabPhotos: TextView
    private lateinit var tabVideos: TextView
    private lateinit var emptyTv: TextView
    private var span = 3

    private fun tv(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(android.graphics.Color.WHITE)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun groupLabel(dateSec: Long): String {
        val nowMs = if (TrustedTime.ok) TrustedTime.now() else System.currentTimeMillis()
        val off = 19800000L
        val diff = ((nowMs + off) / 86400000L - (dateSec * 1000 + off) / 86400000L).toInt()
        return when {
            diff <= 0 -> tr("Today", "आज")
            diff == 1 -> tr("Yesterday", "कल")
            diff <= 30 -> fmtIst("dd/MM/yyyy (EEEE)", dateSec * 1000)
            diff <= 365 -> fmtIst("MMMM yyyy", dateSec * 1000)
            else -> fmtIst("yyyy", dateSec * 1000)
        }
    }

    private fun current() = all.filter { it.video == tabVideo }.sortedByDescending { it.date }

    private fun rebuild() {
        rows.clear()
        var label = ""; var cur = mutableListOf<MediaItem>()
        fun flush() { if (cur.isNotEmpty()) { rows.add(Row.Header(label, cur.toList())); cur.forEach { rows.add(Row.Cell(it)) } } }
        for (m in current()) {
            val l = groupLabel(m.date)
            if (l != label) { flush(); label = l; cur = mutableListOf() }
            cur.add(m)
        }
        flush()
        rv.adapter?.notifyDataSetChanged()
        emptyTv.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        updateBar()
    }

    private fun updateBar() {
        val yellow = 0xFFFFCC00.toInt()
        tabPhotos.setTextColor(if (!tabVideo) yellow else android.graphics.Color.WHITE)
        tabVideos.setTextColor(if (tabVideo) yellow else android.graphics.Color.WHITE)
        tabPhotos.text = tr("Photos", "फोटो") + " (" + all.count { !it.video } + ")"
        tabVideos.text = tr("Videos", "वीडियो") + " (" + all.count { it.video } + ")"
        titleTv.text = if (selMode) selected.size.toString() + " " + tr("selected", "चुने गए") else tr("Gallery", "गैलरी")
        val vis = if (selMode) View.VISIBLE else View.GONE
        selAllTv.visibility = vis; shareTv.visibility = vis; delTv.visibility = vis
        val cur = current()
        selAllTv.text = if (cur.isNotEmpty() && cur.all { it.uri.toString() in selected }) tr("None", "कोई नहीं") else tr("All", "सभी")
    }

    private fun toggle(key: String) {
        if (!selected.remove(key)) selected.add(key)
        if (selected.isEmpty()) selMode = false
        rv.adapter?.notifyDataSetChanged(); updateBar()
    }

    private fun exitSel() { selMode = false; selected.clear(); rv.adapter?.notifyDataSetChanged(); updateBar() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        span = (resources.displayMetrics.widthPixels / dp(120)).coerceAtLeast(3)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK); fitsSystemWindows = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@GalleryActivity, R.color.brand_primary)); setPadding(dp(4), dp(8), dp(8), dp(8))
        }
        val back = tv("←", 24f).apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { onBackPressedDispatcher.onBackPressed() } }
        titleTv = tv("", 18f, true)
        selAllTv = tv("", 15f, true).apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        shareTv = tv(tr("Share", "शेयर"), 15f, true).apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        delTv = tv(tr("Delete", "हटाएँ"), 15f, true).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setTextColor(0xFFFF8A80.toInt()) }
        bar.addView(back); bar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(selAllTv); bar.addView(shareTv); bar.addView(delTv)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabPhotos = tv("", 15f, true).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { if (tabVideo) { tabVideo = false; selected.clear(); selMode = false; rebuild() } } }
        tabVideos = tv("", 15f, true).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { if (!tabVideo) { tabVideo = true; selected.clear(); selMode = false; rebuild() } } }
        tabs.addView(tabPhotos, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tabVideos, LinearLayout.LayoutParams(0, -2, 1f))
        emptyTv = tv(tr("No items yet", "अभी कुछ नहीं है"), 16f).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(40), 0, 0); visibility = View.GONE }
        rv = androidx.recyclerview.widget.RecyclerView(this)
        val lm = androidx.recyclerview.widget.GridLayoutManager(this, span)
        lm.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(p: Int) = if (rows[p] is Row.Header) span else 1
        }
        rv.layoutManager = lm
        rv.adapter = Adapter()
        root.addView(bar); root.addView(tabs); root.addView(emptyTv); root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        selAllTv.setOnClickListener {
            val cur = current()
            if (cur.all { it.uri.toString() in selected }) selected.clear() else cur.forEach { selected.add(it.uri.toString()) }
            if (selected.isEmpty()) selMode = false
            rv.adapter?.notifyDataSetChanged(); updateBar()
        }
        shareTv.setOnClickListener {
            val uris = ArrayList<Uri>(selected.map { Uri.parse(it) })
            if (uris.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(if (tabVideo) "video/*" else "image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
        }
        delTv.setOnClickListener {
            val n = selected.size
            if (n > 0) AlertDialog.Builder(this).setMessage(tr("Delete $n item(s)? This cannot be undone.", "$n आइटम हटाएँ? यह वापस नहीं होगा।"))
                .setPositiveButton(tr("Delete", "हटाएँ")) { _, _ ->
                    val keys = selected.toList()
                    io.execute {
                        var ok = 0
                        keys.forEach { try { if (contentResolver.delete(Uri.parse(it), null, null) > 0) ok++ } catch (e: Exception) { } }
                        ui.post {
                            selected.clear(); selMode = false
                            Toast.makeText(this, tr("Deleted: $ok", "हटाए गए: $ok"), Toast.LENGTH_SHORT).show()
                            reload()
                        }
                    }
                }.setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (selMode) exitSel() else finish() }
        })
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        io.execute { val list = loadMedia(this); ui.post { all = list; rebuild() } }
    }

    private inner class Adapter : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (type == 0) {
                val lbl = tv("", 15f, true); val chk = tv("", 20f)
                val h = LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(14), dp(12), dp(6))
                    addView(lbl, LinearLayout.LayoutParams(0, -2, 1f)); addView(chk)
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2)
                }
                h.tag = arrayOf(lbl, chk)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(h) {}
            }
            val sz = resources.displayMetrics.widthPixels / span
            val cell = FrameLayout(this@GalleryActivity).apply { layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(sz, sz).apply { setMargins(1, 1, 1, 1) } }
            val iv = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt()) }
            val play = tv("▶", 18f).apply { layoutParams = FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, 10, 6) } }
            val chk = tv("✓", 16f, true).apply {
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF1B1FC9.toInt()); setStroke(3, android.graphics.Color.WHITE) }
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), android.view.Gravity.TOP or android.view.Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) }
            }
            cell.addView(iv); cell.addView(play); cell.addView(chk)
            cell.tag = arrayOf(iv, play, chk)
            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(cell) {}
        }

        @Suppress("UNCHECKED_CAST")
        override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, p: Int) {
            val row = rows[p]
            if (row is Row.Header) {
                val v = h.itemView.tag as Array<TextView>
                v[0].text = row.label + "  (" + row.items.size + ")"
                val all = row.items.all { it.uri.toString() in selected }
                v[1].visibility = if (selMode) View.VISIBLE else View.GONE
                v[1].text = if (all) "●" else "○"
                v[1].setOnClickListener {
                    if (all) row.items.forEach { selected.remove(it.uri.toString()) } else row.items.forEach { selected.add(it.uri.toString()) }
                    if (selected.isEmpty()) selMode = false
                    notifyDataSetChanged(); updateBar()
                }
                return
            }
            row as Row.Cell
            val m = row.item; val key = m.uri.toString()
            val v = h.itemView.tag as Array<View>
            val iv = v[0] as ImageView
            iv.tag = key
            val cached = cache.get(key)
            if (cached != null) iv.setImageBitmap(cached)
            else {
                iv.setImageDrawable(null)
                io.execute {
                    val bm = thumbOf(this@GalleryActivity, m.uri, m.video, 300)
                    if (bm != null) { cache.put(key, bm); ui.post { if (iv.tag == key) iv.setImageBitmap(bm) } }
                }
            }
            v[1].visibility = if (m.video) View.VISIBLE else View.GONE
            val on = key in selected
            v[2].visibility = if (selMode && on) View.VISIBLE else View.GONE
            iv.alpha = if (selMode && on) 0.55f else 1f
            h.itemView.setOnClickListener {
                if (selMode) toggle(key)
                else startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).putExtra("uri", key).putExtra("video", m.video))
            }
            h.itemView.setOnLongClickListener { selMode = true; toggle(key); true }
        }
    }
}

class ViewerActivity : BaseActivity() {
    private var items = mutableListOf<MediaItem>()
    private var idx = 0
    private lateinit var gd: GestureDetector

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_viewer)
        val vid = intent.getBooleanExtra("video", false)
        items = loadMedia(this).filter { it.video == vid }.toMutableList()
        val uri = intent.getStringExtra("uri")
        idx = items.indexOfFirst { it.uri.toString() == uri }.coerceAtLeast(0)
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 1.5f) { go(if (dx < 0) 1 else -1); return true }
                return false
            }
        })
        findViewById<View>(R.id.share).setOnClickListener { items.getOrNull(idx)?.let { shareUri(this, it.uri, it.video) } }
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.delete).setOnClickListener {
            val m = items.getOrNull(idx) ?: return@setOnClickListener
            AlertDialog.Builder(this).setMessage("Delete this item?")
                .setPositiveButton("Delete") { _, _ ->
                    try { contentResolver.delete(m.uri, null, null) } catch (e: Exception) { }
                    items.removeAt(idx)
                    if (items.isEmpty()) finish() else { idx = minOf(idx, items.size - 1); show() }
                }.setNegativeButton("Cancel", null).show()
        }
        if (items.isEmpty()) finish() else show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun go(d: Int) {
        val n = idx + d
        if (n in items.indices) { idx = n; show() }
    }

    private fun show() {
        val m = items[idx]
        findViewById<TextView>(R.id.counter).text = "${idx + 1} / ${items.size}   (swipe left or right)"
        val iv = findViewById<ImageView>(R.id.img)
        val vv = findViewById<VideoView>(R.id.vid)
        vv.stopPlayback()
        if (m.video) {
            iv.visibility = View.GONE; vv.visibility = View.VISIBLE
            vv.setVideoURI(m.uri)
            vv.setMediaController(MediaController(this).also { it.setAnchorView(vv) })
            vv.setOnPreparedListener { vv.start() }
        } else {
            vv.visibility = View.GONE; iv.visibility = View.VISIBLE
            iv.setImageURI(m.uri)
        }
    }
}
