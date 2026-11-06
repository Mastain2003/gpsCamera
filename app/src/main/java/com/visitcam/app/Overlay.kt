package com.visitcam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/** Small map picture. fx/fy = where the location sits inside the tile (0..1). */
class MapTile(val bmp: Bitmap, val fx: Float, val fy: Float)

/** Downloads one OpenStreetMap tile. Call from a background thread. For heavy use, switch to your own/paid tile provider. */
fun fetchMap(lat: Double, lng: Double): MapTile? {
    val z = 17
    val n = 1 shl z
    val xf = (lng + 180) / 360 * n
    val r = Math.toRadians(lat)
    val yf = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n
    val x = xf.toInt(); val y = yf.toInt()
    return try {
        val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VisitGpsCamera/1.0")
        c.connectTimeout = 5000; c.readTimeout = 5000
        val bmp = BitmapFactory.decodeStream(c.inputStream)
        c.disconnect()
        if (bmp == null) null else MapTile(bmp, (xf - x).toFloat(), (yf - y).toFloat())
    } catch (e: Exception) { null }
}

/** Short Plus Code, like J4H6+XM3 */
fun plusCode(lat: Double, lng: Double): String {
    val a = "23456789CFGHJMPQRVWX"
    var la = Math.floor((Math.min(90.0, Math.max(-90.0, lat)) + 90) * 40000).toLong()
    var ln = Math.floor((lng + 180) * 32000).toLong()
    var code = a[((la % 5) * 4 + (ln % 4)).toInt()].toString()
    la /= 5; ln /= 4
    for (i in 0 until 5) {
        code = "" + a[(la % 20).toInt()] + a[(ln % 20).toInt()] + code
        la /= 20; ln /= 20
    }
    return (code.substring(0, 8) + "+" + code.substring(8)).substring(4)
}

fun flagOf(cc: String?): String {
    if (cc == null || cc.length != 2) return ""
    val u = cc.uppercase()
    return String(Character.toChars(0x1F1E6 + (u[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (u[1] - 'A')))
}
