package com.visitcam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

fun sampleData(ctx: Context, style: Int): StampData {
    val l = Shared.loc; val p = Shared.place
    val brand = ctx.getString(R.string.brand_name)
    val ms = if (TrustedTime.ok) TrustedTime.now() else System.currentTimeMillis()
    val lat = l?.latitude ?: 23.62995; val lng = l?.longitude ?: 75.111643
    val id = brand + "_IMG_" + fmtIst("ddMMyyyy_HHmmss", ms) + "_K7M2"
    return StampData(
        style, brand, ctx.getString(R.string.app_name), ContextCompat.getDrawable(ctx, R.drawable.brand_logo)?.toBitmap(128, 128),
        p?.region?.ifBlank { null } ?: "Jaora, Madhya Pradesh, India",
        p?.address?.ifBlank { null } ?: "Mohan Nagar, Jaora, Madhya Pradesh 457226, India",
        lat, lng, l?.accuracy ?: 8f, true, true,
        fmtIst("EEEE, dd/MM/yyyy hh:mm a", ms) + " GMT +05:30", fmtIst("dd/MM/yyyy  hh:mm a", ms) + " IST",
        fmtIst("dd/MM/yyyy", ms), ms / 1000, id, p?.map, 135f, headingLine(135f), l?.altitude ?: 495.0, 0f,
        "$brand|$id|%.6f,%.6f|${ms / 1000}".format(java.util.Locale.US, lat, lng)
    )
}

fun previewBitmap(ctx: Context, style: Int): Bitmap {
    val w = 540; val h = 720
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bm)
    val p = Paint()
    c.drawColor(0xFFD5D8DC.toInt())
    p.color = 0xFF9AA0A6.toInt(); c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
    p.color = 0xFFC58BC9.toInt(); c.drawRect(w * 0.12f, h * 0.10f, w * 0.88f, h * 0.18f, p)
    p.color = 0xFF5A4635.toInt(); c.drawRect(w * 0.12f, h * 0.22f, w * 0.88f, h * 0.52f, p)
    p.color = 0xFFE9DCC0.toInt(); c.drawRect(w * 0.35f, h * 0.30f, w * 0.55f, h * 0.45f, p)
    p.color = 0xFF2F63B5.toInt(); c.drawRect(0f, 0f, w * 0.08f, h.toFloat(), p); c.drawRect(w * 0.92f, 0f, w.toFloat(), h.toFloat(), p)
    drawStamp(c, w.toFloat(), h.toFloat(), sampleData(ctx, style))
    return bm
}

/** Dialog that shows every template as a real preview. */
fun pickStamp(a: BaseActivity, onPicked: () -> Unit) {
    val cur = a.getI("style", 0)
    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8)) }
    var dlg: AlertDialog? = null
    STYLE_NAMES.forEachIndexed { i, name ->
        val iv = ImageView(a).apply { adjustViewBounds = true; setImageBitmap(previewBitmap(a, i)); maxHeight = a.dp(330) }
        val label = TextView(a).apply {
            text = (if (i == cur) "✓  " else "") + name; textSize = 16f; setPadding(0, a.dp(4), 0, a.dp(14))
            setTextColor(if (i == cur) 0xFF1B1FC9.toInt() else (if (a.isDark()) Color.WHITE else Color.BLACK)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(iv); addView(label)
            setOnClickListener { a.putI("style", i); dlg?.dismiss(); onPicked() }
        })
    }
    val sv = ScrollView(a); sv.addView(col)
    dlg = AlertDialog.Builder(a).setTitle(tr("Choose stamp template", "स्टैम्प टेम्पलेट चुनें")).setView(sv)
        .setNegativeButton(tr("Close", "बंद करें"), null).create()
    dlg.show()
}
