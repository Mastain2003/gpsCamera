package com.visitcam.app

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/** Server time (HTTP Date header) kept on the monotonic clock, so changing the phone's date/time has no effect. */
object TrustedTime {
    /** Change this URL to your own server later. */
    const val TIME_URL = "https://www.google.com"
    val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    @Volatile var ok = false
    @Volatile private var base = 0L
    @Volatile private var at = 0L

    fun now(): Long = base + (SystemClock.elapsedRealtime() - at)
    fun ageMs(): Long = if (ok) SystemClock.elapsedRealtime() - at else Long.MAX_VALUE

    fun sync(done: () -> Unit) {
        Thread {
            try {
                val c = URL(TIME_URL).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 6000; c.readTimeout = 6000
                val d = c.getHeaderFieldDate("Date", 0L)
                c.disconnect()
                if (d > 0) { base = d; at = SystemClock.elapsedRealtime(); ok = true }
            } catch (e: Exception) { }
            done()
        }.start()
    }
}
