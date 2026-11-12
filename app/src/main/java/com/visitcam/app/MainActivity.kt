package com.visitcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : BaseActivity(), SensorEventListener {
    companion object {
        const val REQ_LOC = 77
        val WB_MODES = intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE)
        val WB_K = intArrayOf(2700, 4000, 5500, 6500, 7500)
        val OPT_KEYS = listOf("timer", "ratio", "grid", "focus", "bright", "wb", "temp", "level", "mirror", "sound", "stab", "quality")
    }

    private var provider: ProcessCameraProvider? = null
    private var cam: Camera? = null
    private var previewUc: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var paused = false
    private var videoMode = false
    private var back = true
    private var flashMode = 0
    private var timerSec = 0
    private var ratio = 0
    private var gridMode = 0
    private var wbIdx = -1
    private var focusMode = 0
    private var focusDist = 0f
    private var minFocus = 0f
    private var evSupported = false
    private var evLower = 0
    private var evUpper = 0
    private var evIndex = 0
    private var evStep = 1f
    private var optSel = "timer"
    private var started = false
    private var promptLoc = true
    private var internalNav = false
    private var fullAspect = 0.5f
    @Volatile private var vidAspect = 0f
    private var topLogoBmp: Bitmap? = null
    private var counting = false
    private var lastSig = ""
    private var dlgUri: Uri? = null
    private var askingLoc = false
    private var locOff = false
    private lateinit var fused: FusedLocationProviderClient
    @Volatile private var loc: Location? = null
    private var gpsText = "…"
    private var mock = false
    private val io = Executors.newSingleThreadExecutor()
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var place: Place? = null
    private var lastPlaceAt = 0L
    @Volatile private var heading = 0f
    @Volatile private var currentVideoId: String? = null
    @Volatile private var sensorRot = 90
    @Volatile private var recRot = 90
    @Volatile private var devSurface = Surface.ROTATION_0
    private var logoBmp: Bitmap? = null
    private val ovThread = HandlerThread("overlay").apply { start() }
    private val snd by lazy { MediaActionSound() }
    private val perms: Array<String> = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ) + (if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray<String>())
    private val brand get() = getString(R.string.brand_name)
    private val hasGms get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

    private val lmListener = object : android.location.LocationListener {
        override fun onLocationChanged(l: Location) { onNewLocation(l) }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val tick = object : Runnable {
        override fun run() { findViewById<View>(R.id.stampView)?.invalidate(); ui.postDelayed(this, 1000) }
    }

    private val oel by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val s = when { o in 45..134 -> Surface.ROTATION_270; o in 135..224 -> Surface.ROTATION_180; o in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (s == devSurface) return
                devSurface = s
                imageCapture?.targetRotation = s
                if (recording == null) {
                    videoCapture?.targetRotation = s
                    findViewById<StampView>(R.id.stampView)?.rotDeg = when (s) { Surface.ROTATION_270 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_90 -> 270; else -> 0 }
                }
            }
        }
    }

    private fun rotRel(surface: Int): Int = (((sensorRot - surface * 90 * (if (back) 1 else -1)) % 360) + 360) % 360

    private val overlay: OverlayEffect by lazy {
        val fx = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(ovThread.looper), androidx.core.util.Consumer<Throwable> { })
        fx.setOnDrawListener { frame ->
            val cv = frame.overlayCanvas
            cv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val l = loc; val id = currentVideoId
            if (l != null && id != null) {
                val bw = cv.width.toFloat(); val bh = cv.height.toFloat()
                cv.save()
                val uw: Float; val uh: Float
                when (recRot) {
                    90 -> { cv.translate(0f, bh); cv.rotate(-90f); uw = bh; uh = bw }
                    270 -> { cv.translate(bw, 0f); cv.rotate(90f); uw = bh; uh = bw }
                    180 -> { cv.translate(bw, bh); cv.rotate(180f); uw = bw; uh = bh }
                    else -> { uw = bw; uh = bh }
                }
                var dw = uw; var dh = uh; var ox = 0f; var oy = 0f
                val va = vidAspect
                if (va > 0f) {
                    val a = if (uw > uh) 1f / va else va
                    if (uw / uh > a) { dw = uh * a; ox = (uw - dw) / 2f } else { dh = uw / a; oy = (uh - dh) / 2f }
                }
                cv.translate(ox, oy)
                drawStamp(cv, dw, dh, buildData(l, id))
                cv.restore()
            }
            true
        }
        fx
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        logoBmp = ContextCompat.getDrawable(this, R.drawable.brand_logo)?.toBitmap(128, 128)
        topLogoBmp = ContextCompat.getDrawable(this, R.drawable.sanchi_logo)?.toBitmap(256, 256)
        fused = LocationServices.getFusedLocationProviderClient(this)
        ratio = getI("ratio", 0); gridMode = getI("gridMode", 0); timerSec = getI("timer", 0)
        lastSig = sig()
        wire()
        if (permsGranted()) start() else ActivityCompat.requestPermissions(this, perms, 1)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Camera + (precise or approximate) location. Microphone is asked only when you first open VIDEO. */
    private fun permsGranted() = granted(Manifest.permission.CAMERA) &&
        (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) &&
        (Build.VERSION.SDK_INT > 28 || granted(Manifest.permission.WRITE_EXTERNAL_STORAGE))

    override fun startActivity(intent: Intent?) {
        if (intent?.component?.packageName == packageName) internalNav = true
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (intent?.component?.packageName == packageName) internalNav = true
        super.startActivity(intent, options)
    }

    override fun onStart() {
        super.onStart()
        if (!internalNav) promptLoc = true
        internalNav = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rec = recording != null
        setContentView(R.layout.activity_main)
        wire()
        if (rec) { previewUc?.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider); recordingUi(true) }
        else bindCamera()
        refreshThumb()
    }

    private fun click(id: Int, f: () -> Unit) = findViewById<View>(id).setOnClickListener { f() }

    private fun wire() {
        click(R.id.btnFlash) { flashMode = (flashMode + 1) % 4; applyFlash(); updateTop() }
        click(R.id.btnOptions) {
            val p = findViewById<View>(R.id.panelOptions)
            if (p.visibility == View.VISIBLE) p.visibility = View.GONE else { buildOptions(); p.visibility = View.VISIBLE }
        }
        click(R.id.btnFlip) { if (recording == null) { back = !back; bindCamera() } }
        click(R.id.btnSettings) { if (recording == null) startActivity(Intent(this, SettingsActivity::class.java)) }
        click(R.id.btnZoom1) { setZoom(1f) }
        click(R.id.btnZoom2) { setZoom(2f) }
        click(R.id.tabPhoto) { if (recording == null) setMode(false) }
        click(R.id.tabVideo) { if (recording == null) setMode(true) }
        click(R.id.tabReports) { startActivity(Intent(this, ReportActivity::class.java)) }
        click(R.id.btnGallery) { startActivity(Intent(this, GalleryActivity::class.java)) }
        click(R.id.cellLocation) { startActivity(Intent(this, LocationActivity::class.java)) }
        click(R.id.cellStorage) { openStorage() }
        click(R.id.cellTemplates) { pickStamp(this) { } }
        click(R.id.shutter) { shutterClick() }
        click(R.id.btnPause) {
            val r = recording
            if (r != null) {
                if (paused) r.resume() else r.pause()
                paused = !paused
                findViewById<TextView>(R.id.btnPause).text = if (paused) "▶" else "⏸"
            }
        }
        val scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = cam?.cameraInfo?.zoomState?.value ?: return true
                cam?.cameraControl?.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        val tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                when (getS("tapAction", "focus")) { "focus" -> focusAt(e.x, e.y, false); "capture" -> shutterClick() }
                return true
            }
            override fun onLongPress(e: MotionEvent) { focusAt(e.x, e.y, true) }
        })
        findViewById<PreviewView>(R.id.preview).setOnTouchListener { _, ev -> scaleDet.onTouchEvent(ev); tapDet.onTouchEvent(ev); true }
        findViewById<StampView>(R.id.stampView).provider = { loc?.let { l -> buildData(l, liveId()) } }
        findViewById<View>(R.id.previewFrame).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutCamArea() }
        applyTexts(); updateTop(); updateUi()
    }

    private fun portraitAspect(): Float = when (ratio) { 0 -> 0.75f; 1 -> 0.5625f; 2 -> 1f; else -> 0f }

    private fun layoutCamArea() {
        val frame = findViewById<View>(R.id.previewFrame) ?: return
        val area = findViewById<View>(R.id.camArea) ?: return
        val fw = frame.width; val fh = frame.height
        if (fw == 0 || fh == 0) return
        fullAspect = minOf(fw, fh).toFloat() / maxOf(fw, fh)
        var ar = portraitAspect()
        if (ar > 0f && fw > fh && ar != 1f) ar = 1f / ar
        var cw = fw; var ch = fh
        if (ar > 0f) { if (fw.toFloat() / fh > ar) cw = (fh * ar).toInt() else ch = (fw / ar).toInt() }
        val lp = area.layoutParams as FrameLayout.LayoutParams
        if (lp.width != cw || lp.height != ch) { lp.width = cw; lp.height = ch; lp.gravity = Gravity.CENTER; area.layoutParams = lp }
    }

    private fun sig() = getS("photoQ", "high") + getS("videoQ", "hd") + getB("stab", false)

    private fun liveId() = brand + "_IMG_" + fmtIst("ddMMyyyy_HHmmss", if (TrustedTime.ok) TrustedTime.now() else System.currentTimeMillis()) + "_----"

    private fun applyTexts() {
        findViewById<TextView>(R.id.tabPhoto).text = tr("PHOTO", "फोटो")
        findViewById<TextView>(R.id.tabVideo).text = tr("VIDEO", "वीडियो")
        findViewById<TextView>(R.id.tabReports).apply { text = tr("REPORTS", "रिपोर्ट्स"); visibility = if (getB("reportsTab", true)) View.VISIBLE else View.GONE }
        findViewById<TextView>(R.id.lblGallery).text = tr("Preview", "प्रीव्यू")
        findViewById<TextView>(R.id.lblLocation).text = tr("Location", "लोकेशन")
        findViewById<TextView>(R.id.lblStorage).text = tr("Storage", "स्टोरेज")
        findViewById<TextView>(R.id.lblTemplates).text = tr("Templates", "टेम्पलेट")
        updateStatus()
    }

    private fun updateTop() {
        findViewById<TextView>(R.id.btnFlash).text = arrayOf("⚡✕", "⚡A", "⚡", "🔦")[flashMode]
        findViewById<GridLines>(R.id.gridLines).apply { mode = gridMode; visibility = if (gridMode == 0) View.GONE else View.VISIBLE }
        layoutCamArea()
        findViewById<View>(R.id.levelView).visibility = if (getB("level", false)) View.VISIBLE else View.GONE
    }

    private fun updateUi() {
        findViewById<View>(R.id.tabPhoto).alpha = if (videoMode) 0.5f else 1f
        findViewById<View>(R.id.tabVideo).alpha = if (videoMode) 1f else 0.5f
        val sh = findViewById<TextView>(R.id.shutter)
        sh.setBackgroundResource(if (videoMode) R.drawable.shutter_rec else R.drawable.shutter)
        sh.text = if (recording != null) "■" else ""
    }

    private fun updateZoomUi(z: Float) {
        val b1 = findViewById<TextView>(R.id.btnZoom1); val b2 = findViewById<TextView>(R.id.btnZoom2); val bx = findViewById<TextView>(R.id.btnZoomX)
        fun mark(v: TextView, on: Boolean) { v.setBackgroundResource(if (on) R.drawable.round_btn_active else R.drawable.round_btn); v.setTextColor(if (on) Color.BLACK else Color.WHITE) }
        val is1 = Math.abs(z - 1f) < 0.05f; val is2 = Math.abs(z - 2f) < 0.05f
        mark(b1, is1); mark(b2, is2)
        bx.visibility = if (is1 || is2) View.GONE else View.VISIBLE
        if (!is1 && !is2) { bx.text = "%.1fx".format(Locale.US, z); mark(bx, true) }
    }

    // ------------------------------------------------------------------ camera options panel

    private fun optIcon(k: String) = when (k) {
        "timer" -> "⏱"; "ratio" -> "▭"; "grid" -> "▦"; "focus" -> "◎"; "bright" -> "☀"; "wb" -> "WB"
        "temp" -> "🌡"; "level" -> "📐"; "mirror" -> "⇆"; "sound" -> "🔊"; "stab" -> "🎥"; else -> "HD"
    }

    private fun optLabel(k: String) = when (k) {
        "timer" -> tr("Timer", "टाइमर"); "ratio" -> tr("Ratio", "रेशियो"); "grid" -> tr("Grid", "ग्रिड")
        "focus" -> tr("Focus", "फ़ोकस"); "bright" -> tr("Brightness", "ब्राइटनेस"); "wb" -> tr("White bal.", "व्हाइट बैल.")
        "temp" -> tr("Temp", "तापमान"); "level" -> tr("Level", "लेवल"); "mirror" -> tr("Mirror", "मिरर")
        "sound" -> tr("Sound", "आवाज़"); "stab" -> tr("Stabilise", "स्टेबलाइज़"); else -> tr("Quality", "क्वालिटी")
    }

    private fun buildOptions() {
        val root = findViewById<LinearLayout>(R.id.panelOptions)
        root.removeAllViews()
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        OPT_KEYS.forEach { k ->
            row.addView(TextView(this).apply {
                text = optIcon(k) + "\n" + optLabel(k); textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (k == optSel) 0xFFFFCC00.toInt() else Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { optSel = k; buildOptions() }
            })
        }
        hs.addView(row); root.addView(hs)
        root.addView(optDetail(optSel))
    }

    private fun note(t: String): View = TextView(this).apply { text = t; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(8)) }

    private fun chips(items: List<String>, sel: Int, pick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, t ->
            row.addView(TextView(this).apply {
                text = t; textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (i == sel) 0xFFFFCC00.toInt() else Color.WHITE)
                setOnClickListener { pick(i); buildOptions() }
            })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun slider(max: Int, prog: Int, label: (Int) -> String, onChange: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val tv = TextView(this).apply { text = label(prog); setTextColor(Color.WHITE); textSize = 13f }
        val sb = SeekBar(this).apply { this.max = max; progress = prog }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { tv.text = label(p); if (user) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        col.addView(tv); col.addView(sb)
        return col
    }

    private fun onOff(key: String, def: Boolean, after: () -> Unit = {}): View =
        chips(listOf(tr("Off", "बंद"), tr("On", "चालू")), if (getB(key, def)) 1 else 0) { putB(key, it == 1); after() }

    private fun optDetail(k: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (k) {
            "timer" -> col.addView(chips(listOf(tr("Off", "बंद"), "3 s", "5 s", "10 s"), intArrayOf(0, 3, 5, 10).indexOf(timerSec).coerceAtLeast(0)) {
                timerSec = intArrayOf(0, 3, 5, 10)[it]; putI("timer", timerSec) })
            "ratio" -> col.addView(chips(listOf("4:3", "16:9", "1:1", "Full"), ratio) {
                if (recording == null) { ratio = it; putI("ratio", it); updateTop(); bindCamera() } })
            "grid" -> col.addView(chips(listOf(tr("Off", "बंद"), "3×3", "4×4", tr("Golden", "गोल्डन"), tr("Diagonal", "तिरछा"), tr("Center", "केंद्र")), gridMode) {
                gridMode = it; putI("gridMode", it); updateTop() })
            "focus" -> {
                col.addView(chips(listOf(tr("Auto", "ऑटो"), tr("Lock", "लॉक"), tr("Manual", "मैनुअल")), focusMode) {
                    focusMode = it; if (it == 1) focusAt(findViewById<View>(R.id.preview).width / 2f, findViewById<View>(R.id.preview).height / 2f, true); applyCam2() })
                if (focusMode == 2) {
                    if (minFocus > 0f) col.addView(slider(100, (focusDist / minFocus * 100).toInt(),
                        { p -> tr("Distance", "दूरी") + ": " + (if (p == 0) "∞" else tr("near", "पास") + " $p%") }) { p -> focusDist = minFocus * p / 100f; applyCam2() })
                    else col.addView(note(tr("Manual focus is not supported on this camera", "इस कैमरे में मैनुअल फ़ोकस उपलब्ध नहीं")))
                }
            }
            "bright" -> if (evSupported) col.addView(slider(evUpper - evLower, evIndex - evLower,
                { p -> tr("Exposure", "एक्सपोज़र") + ": %+.1f EV".format(Locale.US, (p + evLower) * evStep) }) { p ->
                evIndex = p + evLower; cam?.cameraControl?.setExposureCompensationIndex(evIndex) })
            else col.addView(note(tr("Not supported on this camera", "इस कैमरे में उपलब्ध नहीं")))
            "wb" -> col.addView(chips(listOf(tr("Auto", "ऑटो"), tr("Incandescent", "बल्ब"), tr("Fluorescent", "ट्यूबलाइट"), tr("Daylight", "दिन"), tr("Cloudy", "बादल"), tr("Shade", "छाया")), wbIdx + 1) {
                wbIdx = it - 1; applyCam2() })
            "temp" -> {
                col.addView(chips(listOf(tr("Auto", "ऑटो"), tr("Manual", "मैनुअल")), if (wbIdx < 0) 0 else 1) { wbIdx = if (it == 0) -1 else 2; applyCam2() })
                if (wbIdx >= 0) col.addView(slider(4, wbIdx, { p ->
                    tr("Temperature", "तापमान") + ": ${WB_K[p]}K  " + (if (p < 2) tr("warm", "गर्म") else if (p > 2) tr("cool", "ठंडा") else tr("neutral", "सामान्य"))
                }) { p -> wbIdx = p; applyCam2() })
            }
            "level" -> col.addView(onOff("level", false) { updateTop() })
            "mirror" -> { col.addView(onOff("mirror", false)); col.addView(note(tr("Selfie photos are saved mirrored", "सेल्फी फोटो मिरर होकर सेव होती हैं"))) }
            "sound" -> col.addView(onOff("sound", true))
            "stab" -> { col.addView(onOff("stab", false) { if (videoMode && recording == null) bindCamera() }); col.addView(note(tr("Video stabilisation", "वीडियो स्टेबलाइज़ेशन"))) }
            else -> {
                col.addView(note(tr("Photo quality", "फोटो क्वालिटी")))
                col.addView(chips(listOf(tr("High", "उच्च"), tr("Medium", "मध्यम"), tr("Low", "कम")), listOf("high", "medium", "low").indexOf(getS("photoQ", "high")).coerceAtLeast(0)) {
                    putS("photoQ", listOf("high", "medium", "low")[it]) })
                col.addView(note(tr("Video quality", "वीडियो क्वालिटी")))
                col.addView(chips(listOf("SD", "HD", "Full HD", "4K"), listOf("sd", "hd", "fhd", "uhd").indexOf(getS("videoQ", "hd")).coerceAtLeast(0)) {
                    putS("videoQ", listOf("sd", "hd", "fhd", "uhd")[it]) })
            }
        }
        return col
    }

    // ------------------------------------------------------------------ lifecycle / sensors

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c != 1) return
        if (permsGranted()) { start(); return }
        val permanent = perms.any { !granted(it) && !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (permanent) {
            AlertDialog.Builder(this).setCancelable(false)
                .setTitle(tr("Permission needed", "अनुमति ज़रूरी है"))
                .setMessage(tr("Allow Camera and Location in app settings to use this app.", "ऐप इस्तेमाल करने के लिए सेटिंग में कैमरा और लोकेशन की अनुमति दें।"))
                .setPositiveButton(tr("Open settings", "सेटिंग खोलें")) { _, _ ->
                    internalNav = true
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(tr("Later", "बाद में"), null).show()
        } else toast(tr("Camera and location permission are required", "कैमरा और लोकेशन की अनुमति ज़रूरी है"))
    }

    override fun onResume() {
        super.onResume(); refreshThumb(); applyTexts(); updateTop()
        if (TrustedTime.ageMs() > 300_000) syncTime()
        if (sig() != lastSig) { lastSig = sig(); if (recording == null) bindCamera() }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (oel.canDetectOrientation()) oel.enable()
        ui.removeCallbacks(tick); ui.post(tick)
        if (permsGranted()) {
            if (!started) start()
            locOff = !locationEnabled(); updateStatus()
            if (promptLoc) { promptLoc = false; ensureLocationOn() }
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        oel.disable(); ui.removeCallbacks(tick)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            findViewById<LevelView>(R.id.levelView)?.angle = Math.toDegrees(Math.atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            return
        }
        val rm = FloatArray(9); val out = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_X, SensorManager.AXIS_Z, out)
        SensorManager.getOrientation(out, o)
        heading = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (getS("volAction", "photo")) {
                "photo" -> { if (event.repeatCount == 0) shutterClick(); return true }
                "zoom" -> {
                    val z = cam?.cameraInfo?.zoomState?.value
                    if (z != null) cam?.cameraControl?.setZoomRatio(
                        (z.zoomRatio + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.25f else -0.25f).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ location

    private fun start() {
        if (started) return
        started = true
        startLocation()
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (hasGms) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
            fused.requestLocationUpdates(req, object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { onNewLocation(it) } }
            }, mainLooper)
        } else {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, lmListener, Looper.getMainLooper()) } catch (e: Exception) { }
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, lmListener, Looper.getMainLooper()) } catch (e: Exception) { }
        }
    }

    private fun onNewLocation(l: Location) {
        loc = l; Shared.loc = l
        mock = isMockLoc(l)
        gpsText = "GPS: %.6f, %.6f (±%.0f m)".format(l.latitude, l.longitude, l.accuracy)
        if (mock && recording != null) { recording?.stop(); toast(tr("Recording stopped: mock location detected", "रिकॉर्डिंग बंद: मॉक लोकेशन मिली")) }
        val now = SystemClock.elapsedRealtime()
        if (place == null || now - lastPlaceAt > 30_000) { lastPlaceAt = now; bg.execute { refreshPlace(l) } }
        updateStatus()
    }

    private fun locationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun ensureLocationOn() {
        if (locationEnabled()) { locOff = false; updateStatus(); return }
        locOff = true; updateStatus()
        if (askingLoc) return
        askingLoc = true
        if (hasGms) {
            val req = LocationSettingsRequest.Builder()
                .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()).setAlwaysShow(true).build()
            LocationServices.getSettingsClient(this).checkLocationSettings(req)
                .addOnSuccessListener { askingLoc = false }
                .addOnFailureListener { e ->
                    if (e is ResolvableApiException) {
                        try { e.startResolutionForResult(this, REQ_LOC) } catch (x: Exception) { askingLoc = false; showLocDialog() }
                    } else { askingLoc = false; showLocDialog() }
                }
        } else showLocDialog()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOC) { askingLoc = false; if (!locationEnabled()) showLocDialog() else { locOff = false; updateStatus() } }
    }

    private fun showLocDialog() {
        askingLoc = true
        AlertDialog.Builder(this).setCancelable(false)
            .setTitle(tr("Location is OFF", "लोकेशन बंद है"))
            .setMessage(tr("Turn on location (GPS) to capture verified photos and videos.", "सत्यापित फोटो और वीडियो के लिए लोकेशन (GPS) चालू करें।"))
            .setPositiveButton(tr("Open settings", "सेटिंग खोलें")) { _, _ -> askingLoc = false; internalNav = true; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setNegativeButton(tr("Later", "बाद में")) { _, _ -> askingLoc = false }
            .show()
    }

    private fun refreshPlace(l: Location) {
        var region = ""; var address = ""; var flag = ""
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(this, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            if (a != null) {
                address = a.getAddressLine(0) ?: ""
                region = listOf(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                    .filter { !it.isNullOrBlank() }.distinct().joinToString(", ")
                flag = a.countryCode ?: ""
            }
        } catch (e: Exception) { }
        val p = Place(region, flag, address, fetchMap(l.latitude, l.longitude))
        place = p; Shared.place = p
    }

    private fun buildData(l: Location, id: String): StampData {
        val p = place
        val ms = TrustedTime.now()
        val customs = if (getB("customOnStamp", false)) listOf(getS("c1", ""), getS("c2", ""), getS("c3", "")).filter { it.isNotBlank() } else emptyList()
        val qr = if (getS("qrMode", "verify") == "maps") "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, l.latitude, l.longitude)
        else "$brand|$id|%.6f,%.6f|${ms / 1000}".format(Locale.US, l.latitude, l.longitude)
        return StampData(
            getI("style", 0), brand, getString(R.string.app_name), if (getB("logo", true)) logoBmp else null,
            p?.region ?: "", p?.address ?: "",
            l.latitude, l.longitude, l.accuracy, l.accuracy <= 30f, TrustedTime.ok,
            fmtIst("EEEE, dd/MM/yyyy hh:mm a", ms) + " GMT +05:30",
            fmtIst("dd/MM/yyyy  hh:mm a", ms) + " IST",
            fmtIst("dd/MM/yyyy", ms), ms / 1000, id, p?.map, heading, headingLine(heading),
            l.altitude, l.speed * 3.6f, qr,
            customs, if (getB("camInd", false)) (if (back) "REAR" else "FRONT") else null,
            when (getS("opacity", "medium")) { "low" -> 90; "high" -> 210; else -> 150 },
            if (getS("size", "normal") == "small") 0.8f else 1f,
            getS("orient", "auto") == "landscape",
            if (getB("topLogo", true)) topLogoBmp else null,
            when (getS("logoSize", "medium")) { "small" -> 0.11f; "large" -> 0.22f; else -> 0.16f }
        )
    }

    // ------------------------------------------------------------------ camera

    private fun bindCamera() {
        val p = provider ?: return
        if (!bindWith(p, true) && videoMode) {
            if (bindWith(p, false)) toast(tr("Stamp on video is not supported on this phone", "इस फ़ोन पर वीडियो में स्टैम्प उपलब्ध नहीं"))
            else toast(tr("This camera is not available", "यह कैमरा उपलब्ध नहीं है"))
        } else if (!videoMode && cam == null) toast(tr("This camera is not available", "यह कैमरा उपलब्ध नहीं है"))
    }

    private fun bindWith(p: ProcessCameraProvider, withEffect: Boolean): Boolean {
        val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val ar = if (videoMode || ratio == 1 || ratio == 3) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy(ar, AspectRatioStrategy.FALLBACK_RULE_AUTO)).build()
        val pv = findViewById<PreviewView>(R.id.preview)
        pv.scaleType = PreviewView.ScaleType.FILL_CENTER
        val preview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        previewUc = preview
        p.unbindAll()
        try {
            val gb = UseCaseGroup.Builder().addUseCase(preview)
            if (videoMode) {
                val q = when (getS("videoQ", "hd")) { "sd" -> Quality.SD; "fhd" -> Quality.FHD; "uhd" -> Quality.UHD; else -> Quality.HD }
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vb = VideoCapture.Builder(rec).setTargetRotation(devSurface)
                if (getB("stab", false)) vb.setVideoStabilizationEnabled(true)
                val vc = vb.build()
                videoCapture = vc
                gb.addUseCase(vc)
                if (withEffect) gb.addEffect(overlay)
            } else {
                val ic = ImageCapture.Builder().setResolutionSelector(rs).setTargetRotation(devSurface)
                    .setCaptureMode(if (getS("photoQ", "high") == "high") ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(when (flashMode) { 1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF })
                    .build()
                imageCapture = ic
                gb.addUseCase(ic)
            }
            val vp: ViewPort? = when {
                videoMode && ratio == 2 -> ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build()
                videoMode && ratio == 0 -> ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build()
                else -> null
            }
            if (vp != null) gb.setViewPort(vp)
            val c = p.bindToLifecycle(this, sel, gb.build())
            cam = c
            sensorRot = c.cameraInfo.sensorRotationDegrees
            c.cameraInfo.zoomState.removeObservers(this)
            c.cameraInfo.zoomState.observe(this) { z -> updateZoomUi(z.zoomRatio) }
            val es = c.cameraInfo.exposureState
            evSupported = es.isExposureCompensationSupported
            if (evSupported) { evLower = es.exposureCompensationRange.lower; evUpper = es.exposureCompensationRange.upper; evStep = es.exposureCompensationStep.toFloat(); evIndex = es.exposureCompensationIndex }
            minFocus = try { Camera2CameraInfo.from(c.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f } catch (e: Exception) { 0f }
            applyFlash(); applyCam2()
            return true
        } catch (e: Exception) {
            cam = null
            return false
        }
    }

    private fun applyFlash() {
        imageCapture?.flashMode = when (flashMode) { 1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
        try { cam?.cameraControl?.enableTorch(flashMode == 3) } catch (e: Exception) { }
    }

    private fun applyCam2() {
        val c = cam ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, if (wbIdx < 0) CaptureRequest.CONTROL_AWB_MODE_AUTO else WB_MODES[wbIdx])
            if (focusMode == 2 && minFocus > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
            }
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) { }
    }

    private fun setZoom(r: Float) {
        val z = cam?.cameraInfo?.zoomState?.value ?: return
        cam?.cameraControl?.setZoomRatio(r.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val c = cam ?: return
        val pv = findViewById<PreviewView>(R.id.preview)
        val b = FocusMeteringAction.Builder(pv.meteringPointFactory.createPoint(x, y))
        if (lock) b.disableAutoCancel()
        try { c.cameraControl.startFocusAndMetering(b.build()) } catch (e: Exception) { }
        val ring = findViewById<View>(R.id.focusRing)
        ring.animate().cancel()
        val area = findViewById<View>(R.id.camArea)
        ring.x = area.left + x - ring.width / 2f; ring.y = area.top + y - ring.height / 2f
        ring.alpha = 1f; ring.visibility = View.VISIBLE
        ring.animate().alpha(0f).setStartDelay(if (lock) 3000 else 800).setDuration(300).start()
        if (lock) toast(tr("Focus locked", "फ़ोकस लॉक"))
    }

    private fun setMode(video: Boolean) {
        videoMode = video; updateUi(); bindCamera()
        if (video && !granted(Manifest.permission.RECORD_AUDIO) && !getB("audioAsked", false)) {
            putB("audioAsked", true)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    private fun shutterClick() {
        if (videoMode) { if (recording == null) startRecording() else recording?.stop(); return }
        if (counting) { ui.removeCallbacksAndMessages(null); ui.post(tick); counting = false; findViewById<View>(R.id.countdown).visibility = View.GONE; return }
        if (timerSec > 0 && canCapture()) startCountdown(timerSec) else capture()
    }

    private fun startCountdown(sec: Int) {
        counting = true
        val tv = findViewById<TextView>(R.id.countdown)
        fun step(n: Int) {
            if (n == 0) { tv.visibility = View.GONE; counting = false; ui.post(tick); capture() }
            else { tv.text = "$n"; tv.visibility = View.VISIBLE; ui.postDelayed({ step(n - 1) }, 1000) }
        }
        step(sec)
    }

    // ------------------------------------------------------------------ video

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val l = loc ?: return toast(tr("GPS fix not ready yet", "GPS फ़िक्स अभी नहीं मिला"))
        if (!canCapture()) return
        val nm = mediaName("VID", l)
        recRot = rotRel(devSurface)
        vidAspect = if (ratio == 2) 1f else if (ratio == 0) 0.75f else 0f
        vc.targetRotation = devSurface
        currentVideoId = nm
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, nm)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$brand/Video")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build()
        if (getB("sound", true)) snd.play(MediaActionSound.START_VIDEO_RECORDING)
        val pending = vc.output.prepareRecording(this, opts)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled()
        recording = pending
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Status -> {
                        val sec = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                        findViewById<TextView>(R.id.timer).text = "%02d:%02d".format(sec / 60, sec % 60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null; paused = false; currentVideoId = null; recordingUi(false)
                        if (getB("sound", true)) snd.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        if (!ev.hasError()) showVideoResult(ev.outputResults.outputUri) else toast(tr("Video failed", "वीडियो नहीं बना"))
                    }
                    else -> {}
                }
            }
        recordingUi(true)
    }

    private fun recordingUi(on: Boolean) {
        findViewById<View>(R.id.timer).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.timer).text = "00:00"
        val pb = findViewById<TextView>(R.id.btnPause)
        pb.text = "⏸"; pb.visibility = if (on) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.cellStorage).visibility = if (on) View.INVISIBLE else View.VISIBLE
        updateUi()
    }

    // ------------------------------------------------------------------ photo

    private fun capture() {
        val l = loc ?: return toast(tr("GPS fix not ready yet", "GPS फ़िक्स अभी नहीं मिला"))
        if (!canCapture()) return
        val ic = imageCapture ?: return
        val nm = mediaName("IMG", l)
        val q = getS("photoQ", "high")
        val maxSide = when (q) { "high" -> 4096; "medium" -> 2560; else -> 1600 }
        val jq = when (q) { "high" -> 90; "medium" -> 85; else -> 78 }
        val original = getB("autoOrig", false)
        val showDlg = getB("sharePhoto", true)
        val mirror = !back && getB("mirror", false)
        if (getB("sound", true)) snd.play(MediaActionSound.SHUTTER_CLICK)
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    val rot = image.imageInfo.rotationDegrees
                    val crop = Rect(image.cropRect)
                    image.close()
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
                    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
                    val swap = rot == 90 || rot == 270
                    val upW = if (swap) src.height else src.width; val upH = if (swap) src.width else src.height
                    var cwU = upW; var chU = upH
                    val pa = if (ratio == 3) fullAspect else portraitAspect()
                    if (pa > 0f) {
                        val ar = if (upW > upH) 1f / pa else pa
                        if (upW.toFloat() / upH > ar) cwU = (upH * ar).toInt() else chU = (upW / ar).toInt()
                    }
                    val cw = if (swap) chU else cwU; val ch = if (swap) cwU else chU
                    val cl = (src.width - cw) / 2; val ct = (src.height - ch) / 2
                    val cr = Rect(cl, ct, cl + cw, ct + ch)
                    val out = Bitmap.createBitmap(if (swap) ch else cw, if (swap) cw else ch, Bitmap.Config.ARGB_8888)
                    val c = Canvas(out)
                    val m = Matrix(); m.postRotate(rot.toFloat())
                    when (rot) { 90 -> m.postTranslate(ch.toFloat(), 0f); 180 -> m.postTranslate(cw.toFloat(), ch.toFloat()); 270 -> m.postTranslate(0f, cw.toFloat()) }
                    if (mirror) { m.postScale(-1f, 1f); m.postTranslate(out.width.toFloat(), 0f) }
                    c.setMatrix(m)
                    c.drawBitmap(src, cr, RectF(0f, 0f, cw.toFloat(), ch.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    src.recycle()
                    c.setMatrix(null)
                    if (original) saveBitmap(out, nm + "_ORG", jq)
                    if (place == null) refreshPlace(l)
                    drawStamp(c, out.width.toFloat(), out.height.toFloat(), buildData(l, nm))
                    val tw = 900; val th = (out.height * (tw / out.width.toFloat())).toInt().coerceAtLeast(1)
                    val thumb = Bitmap.createScaledBitmap(out, tw, th, true)
                    var dlg: AlertDialog? = null
                    runOnUiThread {
                        findViewById<ImageView>(R.id.btnGallery).setImageBitmap(thumb)
                        if (showDlg) dlg = showPreview(thumb) else toast(tr("Saving…", "सहेज रहे हैं…"))
                    }
                    val uri = saveBitmap(out, nm, jq)
                    out.recycle()
                    runOnUiThread {
                        dlgUri = uri
                        if (uri == null) { toast(tr("Save failed", "सेव नहीं हुआ")); dlg?.dismiss() }
                        else {
                            dlg?.setTitle(tr("Photo saved", "फोटो सेव हुई")) ?: toast(tr("Photo saved", "फोटो सेव हुई"))
                            val em = getS("email", "")
                            if (em.isNotBlank()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg")
                                .putExtra(Intent.EXTRA_EMAIL, arrayOf(em)).putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "$brand photo").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Email"))
                        }
                    }
                } catch (e: Exception) { runOnUiThread { toast(tr("Capture failed", "फोटो नहीं ली जा सकी")) } }
            }
            override fun onError(e: ImageCaptureException) = runOnUiThread { toast(tr("Capture failed", "फोटो नहीं ली जा सकी")) }
        })
    }

    private fun showPreview(bm: Bitmap): AlertDialog {
        dlgUri = null
        val iv = ImageView(this).apply { setImageBitmap(bm); adjustViewBounds = true; setPadding(24, 24, 24, 0) }
        val d = AlertDialog.Builder(this).setTitle(tr("Saving…", "सहेज रहे हैं…")).setView(iv)
            .setPositiveButton(tr("Share", "शेयर"), null)
            .setNeutralButton(tr("Gallery", "गैलरी")) { _, _ -> startActivity(Intent(this, GalleryActivity::class.java)) }
            .setNegativeButton(tr("Close", "बंद करें"), null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val u = dlgUri
            if (u != null) shareUri(this, u, false) else toast(tr("Saving…", "सहेज रहे हैं…"))
        }
        return d
    }

    private fun saveBitmap(bmp: Bitmap, name: String, q: Int): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$brand/Image")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, q, o) } }
        return uri
    }

    private fun showVideoResult(uri: Uri) {
        refreshThumb()
        if (!getB("sharePhoto", true)) { toast(tr("Video saved", "वीडियो सेव हुआ")); return }
        val iv = ImageView(this).apply { adjustViewBounds = true; setPadding(24, 24, 24, 0) }
        io.execute { val bm = thumbOf(this, uri, true, 900); runOnUiThread { bm?.let { iv.setImageBitmap(it) } } }
        AlertDialog.Builder(this).setTitle(tr("Video saved", "वीडियो सेव हुआ")).setView(iv)
            .setPositiveButton(tr("Share", "शेयर")) { _, _ -> shareUri(this, uri, true) }
            .setNeutralButton(tr("Gallery", "गैलरी")) { _, _ -> startActivity(Intent(this, GalleryActivity::class.java)) }
            .setNegativeButton(tr("Close", "बंद करें"), null).show()
    }

    private fun openStorage() {
        try {
            val u = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/$brand")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("DCIM/$brand"); startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    // ------------------------------------------------------------------ helpers

    @Suppress("DEPRECATION")
    private fun isMockLoc(l: Location): Boolean = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    private fun syncTime() { TrustedTime.sync { runOnUiThread { updateStatus() } } }

    private fun updateStatus() {
        val tv = findViewById<TextView>(R.id.status) ?: return
        val t = if (TrustedTime.ok) "" else tr("Server time not synced (check internet)", "सर्वर टाइम सिंक नहीं (इंटरनेट देखें)")
        val msg = when {
            locOff -> tr("⚠ Location is OFF - turn on GPS", "⚠ लोकेशन बंद है - GPS चालू करें")
            mock -> tr("⚠ MOCK LOCATION DETECTED - turn it off", "⚠ मॉक लोकेशन मिली - इसे बंद करें")
            else -> ""
        }
        val text = listOf(msg, t).filter { it.isNotBlank() }.joinToString("\n")
        tv.setTextColor(0xFFFF5252.toInt()); tv.text = text
        tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun canCapture(): Boolean {
        if (locOff) { toast(tr("Turn on location to capture.", "कैप्चर के लिए लोकेशन चालू करें।")); return false }
        if (mock) { toast(tr("Mock location detected. Turn it off to capture.", "मॉक लोकेशन मिली। कैप्चर के लिए इसे बंद करें।")); return false }
        if (!TrustedTime.ok) { toast(tr("Server time not synced. Check internet.", "सर्वर टाइम सिंक नहीं। इंटरनेट देखें।")); syncTime(); return false }
        return true
    }

    private fun mediaName(kind: String, l: Location?): String {
        val seq = if ("SEQ" in getS("fnOn", "DATETIME").split(",")) { val n = getI("seq", 0) + 1; putI("seq", n); n } else 0
        return fileNameFor(this, kind, TrustedTime.now(), l?.latitude, l?.longitude, place?.address ?: "", seq)
    }

    private fun refreshThumb() {
        io.execute {
            val m = loadMedia(this).firstOrNull()
            val bm = m?.let { thumbOf(this, it.uri, it.video, 200) }
            runOnUiThread { findViewById<ImageView>(R.id.btnGallery)?.setImageBitmap(bm) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
