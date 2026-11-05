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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MediaItem(val uri: Uri, val video: Boolean, val date: Long)

fun shareUri(ctx: Context, uri: Uri, video: Boolean) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = if (video) "video/mp4" else "image/jpeg"
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
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val sel: String; val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) { sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"; args = arrayOf("%/$brand/%") }
        else { sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"; args = arrayOf("${brand}_%") }
        ctx.contentResolver.query(base, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) list.add(MediaItem(ContentUris.withAppendedId(base, c.getLong(0)), video, c.getLong(1)))
        }
    }
    q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return list.sortedByDescending { it.date }
}

class GalleryActivity : AppCompatActivity() {
    private var items = listOf<MediaItem>()
    private val io = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, g: ViewGroup): View {
            val sz = resources.displayMetrics.widthPixels / 3
            val cell = (v as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                layoutParams = AbsListView.LayoutParams(sz, sz)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt()) })
                addView(TextView(context).apply {
                    text = "▶"; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 10, 6) }
                })
            }
            val m = items[p]
            val iv = cell.getChildAt(0) as ImageView
            iv.tag = m.uri; iv.setImageDrawable(null)
            cell.getChildAt(1).visibility = if (m.video) View.VISIBLE else View.GONE
            io.execute {
                val bm = thumbOf(this@GalleryActivity, m.uri, m.video, 300)
                ui.post { if (iv.tag == m.uri && bm != null) iv.setImageBitmap(bm) }
            }
            return cell
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_gallery)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val grid = findViewById<GridView>(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ViewerActivity::class.java)
                .putExtra("uri", items[pos].uri.toString()).putExtra("video", items[pos].video))
        }
    }

    override fun onResume() {
        super.onResume()
        items = load()
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun load() = loadMedia(this)
}

class ViewerActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_viewer)
        val uri = Uri.parse(intent.getStringExtra("uri"))
        val video = intent.getBooleanExtra("video", false)
        val iv = findViewById<ImageView>(R.id.img)
        val vv = findViewById<VideoView>(R.id.vid)
        if (video) {
            iv.visibility = View.GONE; vv.visibility = View.VISIBLE
            vv.setVideoURI(uri)
            vv.setMediaController(MediaController(this).also { it.setAnchorView(vv) })
            vv.setOnPreparedListener { vv.start() }
        } else {
            vv.visibility = View.GONE; iv.setImageURI(uri)
        }
        findViewById<View>(R.id.share).setOnClickListener { shareUri(this, uri, video) }
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.delete).setOnClickListener {
            AlertDialog.Builder(this).setMessage("Delete this item?")
                .setPositiveButton("Delete") { _, _ ->
                    try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                    finish()
                }.setNegativeButton("Cancel", null).show()
        }
    }
}
