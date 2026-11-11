package com.visitcam.app

import android.graphics.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

val STYLE_NAMES = arrayOf("Classic", "Verified strip", "Light stamp", "Scannable QR", "Compass")

class Place(val region: String, val flag: String, val address: String, val map: MapTile?)

class StampData(
    val style: Int, val brand: String, val appName: String, val logo: Bitmap?,
    val region: String, val address: String,
    val lat: Double, val lng: Double, val acc: Float, val gpsOk: Boolean, val timeOk: Boolean,
    val timeLine: String, val shortTime: String, val dateOnly: String, val epochSec: Long,
    val id: String, val map: MapTile?, val heading: Float, val headingText: String,
    val altitude: Double, val speedKmh: Float, val qrText: String,
    val customs: List<String> = emptyList(), val camLabel: String? = null,
    val alpha: Int = 150, val scale: Float = 1f, val wide: Boolean = false
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

private fun paint(size: Float, bold: Boolean = false, alpha: Int = 255, center: Boolean = false, color: Int = Color.WHITE) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.alpha = alpha; textSize = size; isFakeBoldText = bold
        if (center) textAlign = Paint.Align.CENTER
    }

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

private fun drawQr(c: Canvas, text: String, x: Float, y: Float, size: Float) {
    val bm = qr(text); val n = bm.width; val ms = size / n
    val blk = Paint().apply { color = Color.BLACK }
    for (yy in 0 until n) for (xx in 0 until n) if (bm.get(xx, yy))
        c.drawRect(x + xx * ms, y + yy * ms, x + (xx + 1) * ms + 0.5f, y + (yy + 1) * ms + 0.5f, blk)
}

/** Draws the chosen stamp on any canvas (photo bitmap or video frame). w and h = upright size. */
fun drawStamp(c: Canvas, w: Float, h: Float, d: StampData) {
    darkAlpha = d.alpha
    if (d.style == 1 || d.style == 2) watermark(c, w, h, d)
    val sw = if (d.wide) w else minOf(w, h)
    c.save()
    c.translate((w - sw) / 2f, 0f)
    if (d.scale != 1f) c.scale(d.scale, d.scale, sw / 2f, h)
    when (d.style) {
        0 -> cardStamp(c, sw, h, d, 0)
        1 -> verified(c, sw, h, d)
        2 -> light(c, sw, h, d)
        3 -> cardStamp(c, sw, h, d, 3)
        else -> cardStamp(c, sw, h, d, 4)
    }
    c.restore()
    d.camLabel?.let {
        val p = paint(0.026f * sw, true); val m = 0.03f * sw
        val pw = p.measureText(it) + 0.04f * sw; val ph = 0.05f * sw
        val x0 = (w - sw) / 2f
        c.drawRoundRect(RectF(x0 + sw - m - pw, m, x0 + sw - m, m + ph), ph / 2, ph / 2, dark())
        c.drawText(it, x0 + sw - m - pw + 0.02f * sw, m + ph * 0.68f, p)
    }
}

private fun watermark(c: Canvas, w: Float, h: Float, d: StampData) {
    val base = minOf(w, h)
    val p = paint(0.028f * base, false, 70).apply { setShadowLayer(2f, 0f, 0f, Color.argb(90, 0, 0, 0)) }
    val t = "${d.brand} · ${d.dateOnly} · %.4f, %.4f   ".format(Locale.US, d.lat, d.lng)
    val tw = p.measureText(t)
    c.save()
    c.rotate(-25f, w / 2, h / 2)
    var y = -h * 0.5f
    var row = 0
    val far = maxOf(w, h)
    while (y < h + far * 0.5f) {
        var x = -far * 0.5f - (if (row % 2 == 0) 0f else tw / 2)
        while (x < w + far * 0.5f) { c.drawText(t, x, y, p); x += tw }
        y += 0.14f * base; row++
    }
    c.restore()
}

// ---------- Classic / Scannable / Compass share one card layout (card height = thumbnail height) ----------

private class Row(val t: String, val p: Paint, val lh: Float)

private fun buildRows(d: StampData, kind: Int, w: Float, k: Float, tw: Float): List<Row> {
    val big = paint(0.031f * w * k); val sm = paint(0.0235f * w * k)
    val lb = 0.041f * w * k; val ls = 0.032f * w * k
    val rows = mutableListOf<Row>()
    wrapText(d.region.ifBlank { "Location unavailable" }, big, tw).take(2).forEach { rows.add(Row(it, big, lb)) }
    val code = plusCode(d.lat, d.lng)
    val addr = if (d.address.isBlank()) code else "$code, ${d.address}"
    wrapText(addr, sm, tw).take(if (kind == 4) 1 else 2).forEach { rows.add(Row(it, sm, ls)) }
    d.customs.forEach { t -> wrapText(t, sm, tw).take(1).forEach { rows.add(Row(it, sm, ls)) } }
    rows.add(Row("Lat %.6f° Long %.6f°".format(Locale.US, d.lat, d.lng), sm, ls))
    if (kind == 4) rows.add(Row("Alt %.0f m · Speed %.0f km/h · ±%.0f m".format(Locale.US, d.altitude, d.speedKmh, d.acc), sm, ls))
    wrapText(d.timeLine, sm, tw).take(2).forEach { rows.add(Row(it, sm, ls)) }
    val last = if (kind == 3) (if (d.qrText.startsWith("http")) "Scan QR to open the map" else "Scan QR to verify this photo")
    else "Note : Captured by " + d.appName
    wrapText(last, sm, tw).take(2).forEach { rows.add(Row(it, sm, ls)) }
    return rows
}

private fun cardStamp(c: Canvas, w: Float, h: Float, d: StampData, kind: Int) {
    val m = 0.06f * w; val sz = 0.25f * w; val r = 0.02f * w
    val cardL = m + sz + 0.025f * w; val cardR = w - m
    val cardB = h - 0.02f * w; val cardT = cardB - sz
    c.drawRoundRect(RectF(cardL, cardT, cardR, cardB), r, r, dark())
    val padX = 0.02f * w; val padY = 0.012f * w
    val tw = cardR - cardL - 2 * padX; val avail = sz - 2 * padY
    var k = 1f
    var rows = buildRows(d, kind, w, k, tw)
    while (rows.sumOf { it.lh.toDouble() } > avail && k > 0.6f) { k *= 0.92f; rows = buildRows(d, kind, w, k, tw) }
    val total = rows.sumOf { it.lh.toDouble() }.toFloat()
    var y = cardT + padY + (avail - total) / 2f
    rows.forEach { c.drawText(it.t, cardL + padX, y + it.lh * 0.78f, it.p); y += it.lh }
    // brand tag above the card
    val label = d.appName
    val tp = paint(0.026f * w)
    val tagH = 0.05f * w; val tagW = 0.064f * w + tp.measureText(label)
    val tagT = cardT - tagH - 0.008f * w
    c.drawRoundRect(RectF(cardR - tagW, tagT, cardR, tagT + tagH), r / 2, r / 2, dark())
    drawLogo(c, d.logo, cardR - tagW + 0.008f * w, tagT + 0.007f * w, 0.036f * w)
    c.drawText(label, cardR - tagW + 0.052f * w, tagT + tagH * 0.66f, tp)
    val box = RectF(m, cardT, m + sz, cardB)
    when (kind) { 0 -> mapBox(c, box, d, w, r); 3 -> qrBox(c, box, d, w, r); else -> compassBox(c, box, d, w, r) }
}

private fun mapBox(c: Canvas, box: RectF, d: StampData, w: Float, r: Float) {
    val sz = box.width()
    c.save()
    c.clipPath(Path().apply { addRoundRect(box, r, r, Path.Direction.CW) })
    if (d.map != null) c.drawBitmap(d.map.bmp, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
    else c.drawRect(box, Paint().apply { color = 0xFFCFD8DC.toInt() })
    c.restore()
    val px = if (d.map != null) box.left + d.map.fx * sz else box.centerX()
    val py = if (d.map != null) box.top + d.map.fy * sz else box.centerY()
    val pr = 0.014f * w
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    c.drawPath(Path().apply { moveTo(px, py); lineTo(px - pr * 0.8f, py - pr * 1.6f); lineTo(px + pr * 0.8f, py - pr * 1.6f); close() }, red)
    c.drawCircle(px, py - pr * 2.2f, pr, red)
    c.drawCircle(px, py - pr * 2.2f, pr * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.004f * w; color = Color.WHITE })
    val at = paint(0.016f * w).apply { setShadowLayer(3f, 0f, 0f, Color.BLACK) }
    c.drawText(if (d.map != null) "© OpenStreetMap" else "Map unavailable", box.left + 0.01f * w, box.bottom - 0.01f * w, at)
}

private fun qrBox(c: Canvas, box: RectF, d: StampData, w: Float, r: Float) {
    c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    val pad = 0.014f * w
    drawQr(c, d.qrText, box.left + pad, box.top + pad, box.width() - 2 * pad)
}

private fun compassBox(c: Canvas, box: RectF, d: StampData, w: Float, r: Float) {
    c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121212.toInt() })
    c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.004f * w; color = Color.WHITE })
    val cx = box.centerX(); val R = box.width() * 0.36f; val cy = box.top + box.height() * 0.43f
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.003f * w; color = Color.WHITE }
    c.drawCircle(cx, cy, R, white)
    for (i in 0 until 12) {
        val a = Math.toRadians(i * 30.0)
        val len = if (i % 3 == 0) 0.14f else 0.07f
        c.drawLine(cx + (R * Math.sin(a)).toFloat(), cy - (R * Math.cos(a)).toFloat(),
            cx + (R * (1 - len) * Math.sin(a)).toFloat(), cy - (R * (1 - len) * Math.cos(a)).toFloat(), white)
    }
    val lp = paint(0.021f * w, true, 255, true); val lr = R * 0.70f; val off = lp.textSize * 0.35f
    c.drawText("N", cx, cy - lr + off, paint(0.021f * w, true, 255, true, 0xFFE53935.toInt()))
    c.drawText("E", cx + lr, cy + off, lp); c.drawText("S", cx, cy + lr + off, lp); c.drawText("W", cx - lr, cy + off, lp)
    val dp = paint(0.0165f * w, false, 255, true, 0xFFBBBBBB.toInt()); val dr = R * 0.70f * 0.72f
    val offD = dp.textSize * 0.35f
    c.drawText("45", cx + dr, cy - dr + offD, dp); c.drawText("135", cx + dr, cy + dr + offD, dp)
    c.drawText("225", cx - dr, cy + dr + offD, dp); c.drawText("315", cx - dr, cy - dr + offD, dp)
    c.save(); c.rotate(d.heading, cx, cy)
    c.drawPath(Path().apply { moveTo(cx, cy - R * 0.84f); lineTo(cx + R * 0.13f, cy); lineTo(cx - R * 0.13f, cy); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
    c.drawPath(Path().apply { moveTo(cx, cy + R * 0.84f); lineTo(cx + R * 0.13f, cy); lineTo(cx - R * 0.13f, cy); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    c.restore()
    c.drawCircle(cx, cy, R * 0.36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    c.drawCircle(cx, cy, R * 0.36f, white)
    val deg = paint(0.0215f * w, true, 255, true)
    c.drawText("%.0f°".format(Locale.US, d.heading), cx, cy + deg.textSize * 0.35f, deg)
    val ht = paint(0.0215f * w, false, 255, true)
    c.drawText(fit(d.headingText, ht, box.width() - 0.02f * w), cx, box.bottom - 0.022f * w, ht)
}

// ---------- Verified strip ----------

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
    drawQr(c, d.qrText, qrX, qrY, qrS)
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

// ---------- Light stamp ----------

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
    c.save(); c.rotate(d.heading, ccx, ccy)
    c.drawPath(Path().apply { moveTo(ccx, ccy - cr * 0.9f); lineTo(ccx - cr * 0.35f, ccy + cr * 0.3f); lineTo(ccx + cr * 0.35f, ccy + cr * 0.3f); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
    c.restore()
    val dir = d.headingText.substringAfter(": ").take(10)
    c.drawText(dir, chipL + 0.1f * w, boxT + 0.045f * w, paint(0.024f * w, true))
    c.drawText("%.0f°".format(Locale.US, d.heading), chipL + 0.1f * w, boxT + 0.078f * w, paint(0.026f * w))
}
