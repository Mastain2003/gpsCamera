package com.visitcam.app

import android.app.DatePickerDialog
import android.content.ContentValues
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Calendar

class ReportActivity : BaseActivity() {
    private val cal: Calendar = Calendar.getInstance(TrustedTime.IST)
    private lateinit var status: TextView

    private fun dateText() = "%02d/%02d/%04d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))

    private fun photos(): List<MediaItem> {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis / 1000
        return loadMedia(this).filter { !it.video && !it.name.contains("_ORG") && it.date in start until start + 86400 }.sortedBy { it.date }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = Screen(this, tr("Photo Report", "फोटो रिपोर्ट"))
        s.row(tr("Date", "तारीख"), tr("Photos of this day go into the PDF", "इस दिन की फोटो PDF में जाएँगी"), dateText()) { tv ->
            DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); tv.text = dateText(); refresh() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        status = TextView(this).apply { textSize = 16f; setTextColor(s.fg); setPadding(0, dp(12), 0, 0) }
        s.list.addView(status)
        s.button(tr("Create PDF", "PDF बनाएँ")) { create() }
        refresh()
    }

    private fun refresh() { status.text = tr("Photos found: ", "मिली फोटो: ") + photos().size }

    private fun create() {
        if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, tr("Needs Android 10 or newer", "Android 10 या नया चाहिए"), Toast.LENGTH_LONG).show(); return }
        val list = photos()
        if (list.isEmpty()) { Toast.makeText(this, tr("No photos on this day", "इस दिन कोई फोटो नहीं"), Toast.LENGTH_SHORT).show(); return }
        status.text = tr("Creating PDF…", "PDF बन रहा है…")
        Thread {
            try {
                val doc = PdfDocument()
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
                list.forEachIndexed { i, m ->
                    val bmp = contentResolver.openInputStream(m.uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    } ?: return@forEachIndexed
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                    val sc = minOf(547f / bmp.width, 740f / bmp.height)
                    val w = bmp.width * sc; val h = bmp.height * sc
                    page.canvas.drawBitmap(bmp, null, RectF(24f, 24f, 24f + w, 24f + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    page.canvas.drawText(m.name, 24f, 24f + h + 16f, tp)
                    doc.finishPage(page); bmp.recycle()
                }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${getString(R.string.brand_name)}_Report_${dateText().replace("/", "")}.pdf")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                uri?.let { contentResolver.openOutputStream(it)?.use { o -> doc.writeTo(o) } }
                doc.close()
                runOnUiThread {
                    refresh()
                    if (uri != null) AlertDialog.Builder(this).setTitle(tr("Report saved in Downloads", "रिपोर्ट Downloads में सेव हुई"))
                        .setPositiveButton(tr("Share", "शेयर")) { _, _ -> shareMime(this, uri, "application/pdf") }
                        .setNegativeButton(tr("Close", "बंद करें"), null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { refresh(); Toast.makeText(this, tr("Could not create PDF", "PDF नहीं बन सका"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
