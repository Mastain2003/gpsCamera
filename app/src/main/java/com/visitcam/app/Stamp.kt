package com.visitcam.app

import android.graphics.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

val STYLE_NAMES = arrayOf("Classic", "Verified strip", "Light stamp")

class Place(val region: String, val flag: String, val address: String, val map: MapTile?)

class StampData(
    val style: Int, val brand: String, val appName: String, val logo: Bitmap?,
    val region: String, val flag: String, val address: String,
    val lat: Double, val lng: Double, val acc: Float, val gpsOk: Boolean, val timeOk: Boolean,
    val timeLine: String, val shortTime: String, val dateOnly: String, val epochSec: Long,
    val id: String, val map: MapTile?, val heading: Float,
    val customs: List<String> = emptyList(), val camLabel: String? = null,
    val alpha: Int = 150, val scale: Float = 1f, val orient: Int = 0
)

fun wrapText(t: String, p: Paint, w: Float): List<String> {
    val out = mutableListOf<String>(); var cur = ""
    for (word in t.split(" ")) {
        val n = if (cur.isEmpty()) word else "$cur $word"
        if (p.measureText(n) > w && cur.isNotEmpty()) { out.add(cur); cur = word } else cur = n
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

private fun paint(size: Float, bold: Boolean = false, alpha: Int = 255) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.alpha = alpha; textSize = size; isFakeBoldText = bold }

@Volatile private var darkAlpha = 150
private fun dark() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(darkAlpha, 0, 0, 0) }

private fun fit(t: String, p: Paint, w: Float): String {
    if (p.measureText(t) <= w) return t
    var s = t
    while (s.length > 1 && p.measureText("$s…") > w) s = s.dropLast(1)
    return "$s…"
}

private fun drawLogo(c: Canvas, bmp: Bitmap?, left: Float, top: Float, size: Float) {
    if (bmp != null) c.drawBitmap(bmp, null, RectF(left, top, left + size, top + size), Paint(Paint.FILTER_BITMAP_FLAG))
}

private fun dirOf(deg: Float): String =
    arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) / 45f).toInt()) % 8]

private var qrKey = ""
private var qrBm: BitMatrix? = null

@Synchronized
private fun qr(text: String): BitMatrix {
    val cached = qrBm
    if (cached != null && qrKey == text) return cached
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    qrKey = text; qrBm = m
    return m
}

/** Draws the chosen stamp on any canvas (photo bitmap or video frame). w and h = upright size. */
fun drawStamp(c: Canvas, w: Float, h: Float, d: StampData) {
    darkAlpha = d.alpha
    if (d.style != 0) watermark(c, w, h, d)
    val sw = when (d.orient) { 1 -> minOf(w, h); 2 -> maxOf(w, h); else -> w }
    c.save()
    c.translate((w - sw) / 2f, 0f)
    if (d.scale != 1f) c.scale(d.scale, d.scale, sw / 2f, h)
    when (d.style) { 0 -> classic(c, sw, h, d); 1 -> verified(c, sw, h, d); else -> light(c, sw, h, d) }
    c.restore()
    d.camLabel?.let {
        val p = paint(0.026f * w, true); val m = 0.03f * w
        val pw = p.measureText(it) + 0.04f * w; val ph = 0.05f * w
        c.drawRoundRect(RectF(w - m - pw, m, w - m, m + ph), ph / 2, ph / 2, dark())
        c.drawText(it, w - m - pw + 0.02f * w, m + ph * 0.68f, p)
    }
}

private fun watermark(c: Canvas, w: Float, h: Float, d: StampData) {
    val p = paint(0.028f * w, false, 70).apply { setShadowLayer(2f, 0f, 0f, Color.argb(90, 0, 0, 0)) }
    val t = "${d.brand} · ${d.dateOnly} · %.4f, %.4f   ".format(Locale.US, d.lat, d.lng)
    val tw = p.measureText(t)
    c.save()
    c.rotate(-25f, w / 2, h / 2)
    var y = -h * 0.5f
    var row = 0
    while (y < h * 1.5f) {
        var x = -w * 0.5f - (if (row % 2 == 0) 0f else tw / 2)
        while (x < w * 1.5f) { c.drawText(t, x, y, p); x += tw }
        y += 0.14f * w; row++
    }
    c.restore()
}

private fun classic(c: Canvas, w: Float, h: Float, d: StampData) {
    val m = 0.06f * w; val mapSz = 0.25f * w; val pad = 0.019f * w
    val cardL = m + mapSz + 0.025f * w; val cardR = w - m
    val big = paint(0.037f * w); val sm = paint(0.026f * w)
    val tw = cardR - cardL - 2 * pad
    val code = plusCode(d.lat, d.lng)
    val rows = mutableListOf<Pair<String, Paint>>()
    wrapText(d.region.ifBlank { "Location unavailable" }, big, tw).take(2).forEach { rows.add(it to big) }
    if (d.flag.isNotBlank()) rows.add(d.flag to big)
    wrapText(if (d.address.isBlank()) code else "$code, ${d.address}", sm, tw).take(2).forEach { rows.add(it to sm) }
    d.customs.forEach { t -> wrapText(t, sm, tw).take(1).forEach { rows.add(it to sm) } }
    rows.add("Lat %.6f° Long %.6f°".format(Locale.US, d.lat, d.lng) to sm)
    wrapText(d.timeLine, sm, tw).take(2).forEach { rows.add(it to sm) }
    wrapText("Note : Captured by " + d.appName, sm, tw).take(2).forEach { rows.add(it to sm) }
    val lhBig = 0.054f * w; val lhSm = 0.04f * w
    var cardH = 2 * pad
    rows.forEach { cardH += if (it.second === big) lhBig else lhSm }
    val cardB = h - 0.02f * w; val cardT = cardB - cardH
    val r = 0.02f * w
    c.drawRoundRect(RectF(cardL, cardT, cardR, cardB), r, r, dark())
    var y = cardT + pad
    rows.forEach { (t, p) ->
        val lh = if (p === big) lhBig else lhSm
        c.drawText(t, cardL + pad, y + lh * 0.78f, p); y += lh
    }
    val label = d.appName
    val tp = paint(0.026f * w)
    val tagH = 0.05f * w; val tagW = 0.064f * w + tp.measureText(label)
    val tagT = cardT - tagH - 0.008f * w
    c.drawRoundRect(RectF(cardR - tagW, tagT, cardR, tagT + tagH), r / 2, r / 2, dark())
    drawLogo(c, d.logo, cardR - tagW + 0.008f * w, tagT + 0.007f * w, 0.036f * w)
    c.drawText(label, cardR - tagW + 0.052f * w, tagT + tagH * 0.66f, tp)
    val box = RectF(m, cardB - mapSz, m + mapSz, cardB)
    c.save()
    c.clipPath(Path().apply { addRoundRect(box, r, r, Path.Direction.CW) })
    if (d.map != null) c.drawBitmap(d.map.bmp, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
    else c.drawRect(box, Paint().apply { color = 0xFFCFD8DC.toInt() })
    c.restore()
    val px = if (d.map != null) box.left + d.map.fx * mapSz else box.centerX()
    val py = if (d.map != null) box.top + d.map.fy * mapSz else box.centerY()
    val pr = 0.014f * w
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    c.drawPath(Path().apply { moveTo(px, py); lineTo(px - pr * 0.8f, py - pr * 1.6f); lineTo(px + pr * 0.8f, py - pr * 1.6f); close() }, red)
    c.drawCircle(px, py - pr * 2.2f, pr, red)
    c.drawCircle(px, py - pr * 2.2f, pr * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.004f * w; color = Color.WHITE })
    val at = paint(0.016f * w).apply { setShadowLayer(3f, 0f, 0f, Color.BLACK) }
    c.drawText(if (d.map != null) "© OpenStreetMap" else "Map unavailable", box.left + 0.01f * w, box.bottom - 0.01f * w, at)
}

private fun verified(c: Canvas, w: Float, h: Float, d: StampData) {
    val m = 0.02f * w; val pad = 0.025f * w; val qrS = 0.2f * w; val r = 0.02f * w
    val big = paint(0.036f * w, true); val sm = paint(0.026f * w)
    val lhBig = 0.05f * w; val lhSm = 0.037f * w; val chipRow = 0.05f * w
    val cust = d.customs.joinToString(" | ")
    val stripH = lhBig + lhSm * (if (cust.isBlank()) 3 else 4) + chipRow + 2 * pad
    val stripT = h - m - stripH
    c.drawRoundRect(RectF(m, stripT, w - m, stripT + stripH), r, r, dark())
    val qrX = m + pad; val qrY = stripT + (stripH - qrS) / 2; val q = 0.008f * w
    c.drawRoundRect(RectF(qrX - q, qrY - q, qrX + qrS + q, qrY + qrS + q), q, q, Paint().apply { color = Color.WHITE })
    val payload = "${d.brand}|${d.id}|%.6f,%.6f|${d.epochSec}".format(Locale.US, d.lat, d.lng)
    val bm = qr(payload); val n = bm.width; val ms = qrS / n
    val blk = Paint().apply { color = Color.BLACK }
    for (yy in 0 until n) for (xx in 0 until n) if (bm.get(xx, yy))
        c.drawRect(qrX + xx * ms, qrY + yy * ms, qrX + (xx + 1) * ms + 0.5f, qrY + (yy + 1) * ms + 0.5f, blk)
    val logoS = 0.07f * w
    val tx = qrX + qrS + q + pad
    val tw = (w - m - pad - logoS - 0.01f * w) - tx
    var y = stripT + pad
    c.drawText(fit(d.shortTime, big, tw), tx, y + lhBig * 0.78f, big); y += lhBig
    c.drawText(fit(d.region.ifBlank { "Location unavailable" }, sm, tw), tx, y + lhSm * 0.78f, sm); y += lhSm
    c.drawText(fit("%.6f, %.6f  ±%.0f m".format(Locale.US, d.lat, d.lng, d.acc), sm, tw), tx, y + lhSm * 0.78f, sm); y += lhSm
    if (cust.isNotBlank()) { c.drawText(fit(cust, sm, tw), tx, y + lhSm * 0.78f, sm); y += lhSm }
    val cs = paint(0.024f * w); val chipH = 0.04f * w
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F7A4D.toInt() }
    var cx = tx
    listOf(if (d.gpsOk) "GPS ✓" else "GPS ±%.0fm".format(Locale.US, d.acc), if (d.timeOk) "Time ✓" else "Time ✗", "No mock ✓").forEach { t ->
        val cw = cs.measureText(t) + 0.03f * w
        c.drawRoundRect(RectF(cx, y + 0.005f * w, cx + cw, y + 0.005f * w + chipH), chipH / 2, chipH / 2, green)
        c.drawText(t, cx + 0.015f * w, y + 0.005f * w + chipH * 0.7f, cs)
        cx += cw + 0.01f * w
    }
    y += chipRow
    c.drawText(fit("ID " + d.id, sm, tw), tx, y + lhSm * 0.78f, sm)
    drawLogo(c, d.logo, w - m - pad - logoS, stripT + pad, logoS)
}

private fun light(c: Canvas, w: Float, h: Float, d: StampData) {
    val m = 0.03f * w
    val tp = paint(0.03f * w, true)
    val pillH = 0.06f * w; val logoS = 0.045f * w
    val pw = logoS + tp.measureText(d.shortTime) + 0.04f * w
    c.drawRoundRect(RectF(m, m, m + pw, m + pillH), pillH / 2, pillH / 2, dark())
    drawLogo(c, d.logo, m + 0.008f * w, m + (pillH - logoS) / 2, logoS)
    c.drawText(d.shortTime, m + logoS + 0.02f * w, m + pillH * 0.66f, tp)
    val b1 = paint(0.034f * w, true); val b2 = paint(0.026f * w)
    val l1 = d.region.ifBlank { "Location unavailable" }
    val l2 = "%.6f, %.6f · ±%.0f m".format(Locale.US, d.lat, d.lng, d.acc)
    val chipW = 0.2f * w
    val maxW = w - 2 * m - chipW - 0.02f * w
    val boxW = minOf(maxOf(b1.measureText(l1), b2.measureText(l2)) + 0.04f * w, maxW)
    val cust = d.customs.joinToString(" | ")
    val boxH = (if (cust.isBlank()) 0.1f else 0.13f) * w; val boxB = h - m; val boxT = boxB - boxH; val r = 0.02f * w
    c.drawRoundRect(RectF(m, boxT, m + boxW, boxB), r, r, dark())
    c.drawText(fit(l1, b1, boxW - 0.04f * w), m + 0.02f * w, boxT + 0.045f * w, b1)
    c.drawText(fit(l2, b2, boxW - 0.04f * w), m + 0.02f * w, boxT + 0.078f * w, b2)
    if (cust.isNotBlank()) c.drawText(fit(cust, b2, boxW - 0.04f * w), m + 0.02f * w, boxT + 0.108f * w, b2)
    val chipL = w - m - chipW
    c.drawRoundRect(RectF(chipL, boxT, w - m, boxB), r, r, dark())
    val ccx = chipL + 0.05f * w; val ccy = boxT + boxH / 2; val cr = 0.028f * w
    c.drawCircle(ccx, ccy, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.003f * w; color = Color.WHITE })
    c.save(); c.rotate(-d.heading, ccx, ccy)
    c.drawPath(Path().apply { moveTo(ccx, ccy - cr * 0.9f); lineTo(ccx - cr * 0.35f, ccy + cr * 0.3f); lineTo(ccx + cr * 0.35f, ccy + cr * 0.3f); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
    c.restore()
    c.drawText(dirOf(d.heading), chipL + 0.1f * w, boxT + 0.045f * w, paint(0.03f * w, true))
    c.drawText("%.0f°".format(Locale.US, d.heading), chipL + 0.1f * w, boxT + 0.078f * w, paint(0.026f * w))
}
