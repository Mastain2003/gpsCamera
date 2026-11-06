package com.visitcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraControl
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
import com.google.android.gms.location.*
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : BaseActivity(), SensorEventListener {
    companion object {
        val WB_MODES = intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE)
        val WB_K = intArrayOf(2700, 4000, 5500, 6500, 7500)
    }

    private var provider: ProcessCameraProvider? = null
    private var cam: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var paused = false
    private var videoMode = false
    private var back = true
    private var flashMode = 0
    private var timerSec = 0
    private var ratio = 0
    private var gridOn = false
    private var wbIdx = -1
    private var evLower = 0
    private var evStep = 1f
    private var counting = false
    private var lastSig = ""
    private var dlgUri: Uri? = null
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
    private var logoBmp: Bitmap? = null
    private val ovThread = HandlerThread("overlay").apply { start() }
    private val perms: Array<String> = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION
    ) + (if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray<String>())
    private val brand get() = getString(R.string.brand_name)

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
                when (sensorRot) {
                    90 -> { cv.translate(0f, bh); cv.rotate(-90f); uw = bh; uh = bw }
                    270 -> { cv.translate(bw, 0f); cv.rotate(90f); uw = bh; uh = bw }
                    180 -> { cv.translate(bw, bh); cv.rotate(180f); uw = bw; uh = bh }
                    else -> { uw = bw; uh = bh }
                }
                drawStamp(cv, uw, uh, buildData(l, id))
                cv.restore()
            }
            true
        }
        fx
    }

    private fun seekL(f: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) = f(p, user)
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        logoBmp = ContextCompat.getDrawable(this, R.drawable.brand_logo)?.toBitmap(128, 128)
        fused = LocationServices.getFusedLocationProviderClient(this)
        ratio = getI("ratio", 0); gridOn = getB("grid", false); timerSec = getI("timer", 0)
        lastSig = sig()

        click(R.id.btnFlash) { flashMode = (flashMode + 1) % 4; applyFlash(); updateTop() }
        click(R.id.btnTimer) { timerSec = when (timerSec) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }; putI("timer", timerSec); updateTop() }
        click(R.id.btnRatio) { if (recording == null) { ratio = (ratio + 1) % 4; putI("ratio", ratio); updateTop(); bindCamera() } }
        click(R.id.btnGridToggle) { gridOn = !gridOn; putB("grid", gridOn); updateTop() }
        click(R.id.btnTune) {
            val p = findViewById<View>(R.id.panelTune)
            p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        click(R.id.cellTemplates) {
            AlertDialog.Builder(this).setTitle(tr("Templates", "टेम्पलेट"))
                .setSingleChoiceItems(STYLE_NAMES, getI("style", 0)) { d, w -> putI("style", w); d.dismiss() }.show()
        }
        click(R.id.shutter) { shutterClick() }
        click(R.id.btnPause) {
            val r = recording
            if (r != null) {
                if (paused) r.resume() else r.pause()
                paused = !paused
                findViewById<TextView>(R.id.btnPause).text = if (paused) "▶" else "⏸"
            }
        }
        click(R.id.btnWbAuto) { wbIdx = -1; applyWb(); updateTemp() }
        findViewById<SeekBar>(R.id.seekTemp).setOnSeekBarChangeListener(seekL { p, user ->
            if (user) { wbIdx = p; applyWb(); updateTemp() }
        })
        findViewById<SeekBar>(R.id.seekEv).setOnSeekBarChangeListener(seekL { p, user ->
            if (user) cam?.cameraControl?.setExposureCompensationIndex(p + evLower)
            findViewById<TextView>(R.id.tvEv).text = tr("Exposure", "एक्सपोज़र") + ": %+.1f EV".format(Locale.US, (p + evLower) * evStep)
        })

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

        applyTexts(); updateTop(); updateUi(); updateTemp()
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) start()
        else ActivityCompat.requestPermissions(this, perms, 1)
    }

    private fun click(id: Int, f: () -> Unit) = findViewById<View>(id).setOnClickListener { f() }

    private fun sig() = getS("photoQ", "high") + getS("videoQ", "hd")

    private fun applyTexts() {
        findViewById<TextView>(R.id.tabPhoto).text = tr("PHOTO", "फोटो")
        findViewById<TextView>(R.id.tabVideo).text = tr("VIDEO", "वीडियो")
        findViewById<TextView>(R.id.tabReports).apply { text = tr("REPORTS", "रिपोर्ट्स"); visibility = if (getB("reportsTab", true)) View.VISIBLE else View.GONE }
        findViewById<TextView>(R.id.lblGallery).text = tr("Preview", "प्रीव्यू")
        findViewById<TextView>(R.id.lblLocation).text = tr("Location", "लोकेशन")
        findViewById<TextView>(R.id.lblStorage).text = tr("Storage", "स्टोरेज")
        findViewById<TextView>(R.id.lblTemplates).text = tr("Templates", "टेम्पलेट")
        findViewById<TextView>(R.id.btnWbAuto).text = tr("Auto", "ऑटो")
        updateStatus()
    }

    private fun updateTop() {
        findViewById<TextView>(R.id.btnFlash).text = arrayOf("⚡✕", "⚡A", "⚡", "🔦")[flashMode]
        findViewById<TextView>(R.id.btnTimer).text = if (timerSec == 0) "⏱" else "${timerSec}s"
        findViewById<TextView>(R.id.btnRatio).text = arrayOf("4:3", "16:9", "1:1", "Full")[ratio]
        findViewById<View>(R.id.btnGridToggle).alpha = if (gridOn) 1f else 0.5f
        findViewById<View>(R.id.gridLines).visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun updateTemp() {
        findViewById<TextView>(R.id.tvTemp).text =
            if (wbIdx < 0) tr("White balance: Auto", "व्हाइट बैलेंस: ऑटो")
            else tr("Temperature", "तापमान") + ": ${WB_K[wbIdx]}K  " + (if (wbIdx < 2) tr("warm", "गर्म") else if (wbIdx > 2) tr("cool", "ठंडा") else tr("neutral", "सामान्य"))
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else toast(tr("Camera, microphone and location permission are required", "कैमरा, माइक्रोफ़ोन और लोकेशन की अनुमति ज़रूरी है"))
    }

    override fun onResume() {
        super.onResume(); refreshThumb(); applyTexts()
        if (TrustedTime.ageMs() > 300_000) syncTime()
        if (sig() != lastSig) { lastSig = sig(); if (recording == null) bindCamera() }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
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

    @SuppressLint("MissingPermission")
    private fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        fused.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val l = r.lastLocation ?: return
                loc = l; Shared.loc = l
                mock = isMockLoc(l)
                gpsText = "GPS: %.6f, %.6f (±%.0f m)".format(l.latitude, l.longitude, l.accuracy)
                if (mock && recording != null) { recording?.stop(); toast(tr("Recording stopped: mock location detected", "रिकॉर्डिंग बंद: मॉक लोकेशन मिली")) }
                val now = SystemClock.elapsedRealtime()
                if (place == null || now - lastPlaceAt > 30_000) { lastPlaceAt = now; bg.execute { refreshPlace(l) } }
                updateStatus()
            }
        }, mainLooper)
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
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
                flag = flagOf(a.countryCode)
            }
        } catch (e: Exception) { }
        val p = Place(region, flag, address, fetchMap(l.latitude, l.longitude))
        place = p; Shared.place = p
    }

    private fun buildData(l: Location, id: String): StampData {
        val p = place
        val ms = TrustedTime.now()
        val customs = if (getB("customOnStamp", false)) listOf(getS("c1", ""), getS("c2", ""), getS("c3", "")).filter { it.isNotBlank() } else emptyList()
        return StampData(
            getI("style", 0), brand, getString(R.string.app_name), if (getB("logo", true)) logoBmp else null,
            p?.region ?: "", p?.flag ?: "", p?.address ?: "",
            l.latitude, l.longitude, l.accuracy, l.accuracy <= 30f, TrustedTime.ok,
            fmtIst("EEEE, dd/MM/yyyy hh:mm a", ms) + " GMT +05:30",
            fmtIst("dd/MM/yyyy  hh:mm a", ms) + " IST",
            fmtIst("dd/MM/yyyy", ms), ms / 1000, id, p?.map, heading,
            customs, if (getB("camInd", false)) (if (back) "REAR" else "FRONT") else null,
            when (getS("opacity", "medium")) { "low" -> 90; "high" -> 210; else -> 150 },
            if (getS("size", "normal") == "small") 0.8f else 1f,
            when (getS("orient", "auto")) { "portrait" -> 1; "landscape" -> 2; else -> 0 }
        )
    }

    private fun bindCamera() {
        val p = provider ?: return
        val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val ar = if (videoMode || ratio == 1 || ratio == 3) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy(ar, AspectRatioStrategy.FALLBACK_RULE_AUTO)).build()
        val pv = findViewById<PreviewView>(R.id.preview)
        pv.scaleType = if (!videoMode && ratio == 3) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        val preview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        p.unbindAll()
        try {
            val gb = UseCaseGroup.Builder().addUseCase(preview)
            if (videoMode) {
                val q = when (getS("videoQ", "hd")) { "sd" -> Quality.SD; "fhd" -> Quality.FHD; "uhd" -> Quality.UHD; else -> Quality.HD }
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc
                gb.addUseCase(vc).addEffect(overlay)
            } else {
                val ic = ImageCapture.Builder().setResolutionSelector(rs)
                    .setCaptureMode(if (getS("photoQ", "high") == "high") ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(when (flashMode) { 1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF })
                    .build()
                imageCapture = ic
                gb.addUseCase(ic)
                if (ratio == 2) gb.setViewPort(ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build())
            }
            val c = p.bindToLifecycle(this, sel, gb.build())
            cam = c
            sensorRot = c.cameraInfo.sensorRotationDegrees
            val es = c.cameraInfo.exposureState
            val seek = findViewById<SeekBar>(R.id.seekEv)
            if (es.isExposureCompensationSupported) {
                val r = es.exposureCompensationRange
                evLower = r.lower; evStep = es.exposureCompensationStep.toFloat()
                seek.isEnabled = true; seek.max = r.upper - r.lower; seek.progress = es.exposureCompensationIndex - r.lower
            } else seek.isEnabled = false
            applyFlash(); applyWb()
        } catch (e: Exception) { toast(tr("This camera is not available", "यह कैमरा उपलब्ध नहीं है")) }
    }

    private fun applyFlash() {
        imageCapture?.flashMode = when (flashMode) { 1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
        try { cam?.cameraControl?.enableTorch(flashMode == 3) } catch (e: Exception) { }
    }

    private fun applyWb() {
        val c = cam ?: return
        val mode = if (wbIdx < 0) CaptureRequest.CONTROL_AWB_MODE_AUTO else WB_MODES[wbIdx]
        try {
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode).build())
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
        ring.x = x - ring.width / 2f; ring.y = y - ring.height / 2f
        ring.alpha = 1f; ring.visibility = View.VISIBLE
        ring.animate().alpha(0f).setStartDelay(if (lock) 3000 else 800).setDuration(300).start()
        if (lock) toast(tr("Focus locked", "फ़ोकस लॉक"))
    }

    private fun setMode(video: Boolean) { videoMode = video; updateUi(); bindCamera() }

    private fun updateUi() {
        findViewById<View>(R.id.tabPhoto).alpha = if (videoMode) 0.5f else 1f
        findViewById<View>(R.id.tabVideo).alpha = if (videoMode) 1f else 0.5f
        val sh = findViewById<TextView>(R.id.shutter)
        sh.setBackgroundResource(if (videoMode) R.drawable.shutter_rec else R.drawable.shutter)
        sh.text = if (recording != null) "■" else ""
    }

    private fun shutterClick() {
        if (videoMode) { if (recording == null) startRecording() else recording?.stop(); return }
        if (counting) { ui.removeCallbacksAndMessages(null); counting = false; findViewById<View>(R.id.countdown).visibility = View.GONE; return }
        if (timerSec > 0 && canCapture()) startCountdown(timerSec) else capture()
    }

    private fun startCountdown(sec: Int) {
        counting = true
        val tv = findViewById<TextView>(R.id.countdown)
        fun step(n: Int) {
            if (n == 0) { tv.visibility = View.GONE; counting = false; capture() }
            else { tv.text = "$n"; tv.visibility = View.VISIBLE; ui.postDelayed({ step(n - 1) }, 1000) }
        }
        step(sec)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val l = loc ?: return toast(tr("GPS fix not ready yet", "GPS फ़िक्स अभी नहीं मिला"))
        if (!canCapture()) return
        val nm = mediaName("VID", l)
        currentVideoId = nm
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, nm)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$brand/Video")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build()
        recording = vc.output.prepareRecording(this, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Status -> {
                        val sec = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                        findViewById<TextView>(R.id.timer).text = "%02d:%02d".format(sec / 60, sec % 60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null; paused = false; currentVideoId = null; recordingUi(false)
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
                    var cr = Rect(crop.left / s, crop.top / s, crop.right / s, crop.bottom / s)
                    if (cr.width() <= 0 || cr.height() <= 0 || cr.right > src.width || cr.bottom > src.height) cr = Rect(0, 0, src.width, src.height)
                    val cw = cr.width(); val ch = cr.height()
                    val swap = rot == 90 || rot == 270
                    val out = Bitmap.createBitmap(if (swap) ch else cw, if (swap) cw else ch, Bitmap.Config.ARGB_8888)
                    val c = Canvas(out)
                    val m = Matrix(); m.postRotate(rot.toFloat())
                    when (rot) { 90 -> m.postTranslate(ch.toFloat(), 0f); 180 -> m.postTranslate(cw.toFloat(), ch.toFloat()); 270 -> m.postTranslate(0f, cw.toFloat()) }
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

    @Suppress("DEPRECATION")
    private fun isMockLoc(l: Location): Boolean = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    private fun syncTime() { TrustedTime.sync { runOnUiThread { updateStatus() } } }

    private fun updateStatus() {
        val t = if (TrustedTime.ok) "Time: server IST ✓" else tr("Time: not synced (check internet)", "समय सिंक नहीं (इंटरनेट देखें)")
        val tv = findViewById<TextView>(R.id.status)
        tv.setTextColor(if (mock) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
        tv.text = if (mock) tr("⚠ MOCK LOCATION DETECTED - turn it off", "⚠ मॉक लोकेशन मिली - इसे बंद करें") + "\n$t" else "$gpsText\n$t"
    }

    private fun canCapture(): Boolean {
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
            runOnUiThread { findViewById<ImageView>(R.id.btnGallery).setImageBitmap(bm) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
