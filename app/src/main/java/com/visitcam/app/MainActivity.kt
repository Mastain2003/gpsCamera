package com.visitcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var paused = false
    private var videoMode = false
    private var back = true
    private lateinit var fused: FusedLocationProviderClient
    private var loc: Location? = null
    private var gpsText = "Waiting for GPS…"
    private var mock = false
    private val io = Executors.newSingleThreadExecutor()
       private val perms: Array<String> = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION
    ) + (if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray<String>())
    private val brand get() = getString(R.string.brand_name)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        fused = LocationServices.getFusedLocationProviderClient(this)
        findViewById<View>(R.id.tabPhoto).setOnClickListener { if (recording == null) setMode(false) }
        findViewById<View>(R.id.tabVideo).setOnClickListener { if (recording == null) setMode(true) }
        findViewById<View>(R.id.btnGallery).setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        findViewById<View>(R.id.btnFlip).setOnClickListener { if (recording == null) { back = !back; bindCamera() } }
        findViewById<View>(R.id.shutter).setOnClickListener {
            if (!videoMode) capture() else if (recording == null) startRecording() else recording?.stop()
        }
        findViewById<TextView>(R.id.btnPause).setOnClickListener {
            val r = recording ?: return@setOnClickListener
            if (paused) r.resume() else r.pause()
            paused = !paused
            (it as TextView).text = if (paused) "▶" else "⏸"
        }
        updateUi()
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) start()
        else ActivityCompat.requestPermissions(this, perms, 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else toast("Camera, microphone and location permission are required")
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        fused.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                loc = r.lastLocation
                loc?.let {
                    mock = isMockLoc(it)
                    gpsText = "GPS: %.6f, %.6f  (±%.0f m)".format(it.latitude, it.longitude, it.accuracy)
                    if (mock && recording != null) { recording?.stop(); toast("Recording stopped: mock location detected") }
                    updateStatus()
                }
            }
        }, mainLooper)
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider) }
        val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        p.unbindAll()
        try {
            if (videoMode) {
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                videoCapture = VideoCapture.withOutput(rec)
                p.bindToLifecycle(this, sel, preview, videoCapture)
            } else {
                imageCapture = ImageCapture.Builder().build()
                p.bindToLifecycle(this, sel, preview, imageCapture)
            }
        } catch (e: Exception) { toast("This camera is not available") }
    }

    private fun setMode(video: Boolean) { videoMode = video; updateUi(); bindCamera() }

    private fun updateUi() {
        findViewById<View>(R.id.tabPhoto).alpha = if (videoMode) 0.5f else 1f
        findViewById<View>(R.id.tabVideo).alpha = if (videoMode) 1f else 0.5f
        val sh = findViewById<TextView>(R.id.shutter)
        sh.setBackgroundResource(if (videoMode) R.drawable.shutter_rec else R.drawable.shutter)
        sh.text = if (recording != null) "■" else ""
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        if (loc == null) return toast("GPS fix not ready yet")
        if (!canCapture()) return
        val (nm, _) = mediaName("VID")
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, nm)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$brand/Video")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        recording = vc.output.prepareRecording(this, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Status -> {
                        val sec = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                        findViewById<TextView>(R.id.timer).text = "%02d:%02d".format(sec / 60, sec % 60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null; paused = false; recordingUi(false)
                        if (!ev.hasError()) showResult(ev.outputResults.outputUri, true) else toast("Video failed")
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
        findViewById<View>(R.id.btnFlip).visibility = if (on) View.INVISIBLE else View.VISIBLE
        updateUi()
    }

    private fun capture() {
        val l = loc ?: return toast("GPS fix not ready yet")
        if (!canCapture()) return
        val ic = imageCapture ?: return
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val raw = image.toBitmap()
                val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                image.close()
                var region = ""; var address = ""
                try {
                    @Suppress("DEPRECATION")
                    val a = Geocoder(this@MainActivity, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                    if (a != null) {
                        address = a.getAddressLine(0) ?: ""
                        region = listOf(a.locality, a.subAdminArea, a.adminArea, a.countryName)
                            .filter { !it.isNullOrBlank() }.distinct().joinToString(", ").uppercase()
                    }
                } catch (e: Exception) { }
                val time = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH).apply { timeZone = TrustedTime.IST }.format(Date(TrustedTime.now())) + " IST"
                save(stamp(bmp, region, l, address, time))
            }
            override fun onError(e: ImageCaptureException) = runOnUiThread { toast("Capture failed") }
        })
    }

    private fun stamp(src: Bitmap, region: String, l: Location, addr: String, time: String): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val s = out.width / 900f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * s }
        val addrLines = if (addr.isBlank()) listOf("Address unavailable") else wrapText(addr, tp, out.width - 40 * s).take(2)
        val lines = listOf(region.ifBlank { "LOCATION UNAVAILABLE" }) + addrLines +
            "Long %.6f  Lat %.6f".format(l.longitude, l.latitude) + time +
            "Note: Captured by " + getString(R.string.app_name)
        val lh = 34 * s
        val hdr = 56 * s
        val top = out.height - (lh * lines.size + 24 * s + hdr)
        val band = ColorUtils.setAlphaComponent(ContextCompat.getColor(this@MainActivity, R.color.brand_primary), 190)
        c.drawRect(0f, top, out.width.toFloat(), out.height.toFloat(), Paint().apply { color = band })
        ContextCompat.getDrawable(this@MainActivity, R.drawable.brand_logo)?.let {
            it.setBounds((20 * s).toInt(), (top + 8 * s).toInt(), (64 * s).toInt(), (top + 52 * s).toInt()); it.draw(c)
        }
        tp.isFakeBoldText = true; tp.textSize = 32 * s
        c.drawText(brand, 78 * s, top + 42 * s, tp)
        tp.textSize = 26 * s
        lines.forEachIndexed { i, t -> tp.isFakeBoldText = i == 0; c.drawText(t, 20 * s, top + hdr + 34 * s + lh * i, tp) }
        return out
    }

    private fun wrapText(t: String, p: Paint, w: Float): List<String> {
        val out = mutableListOf<String>(); var cur = ""
        for (word in t.split(" ")) {
            val n = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(n) > w && cur.isNotEmpty()) { out.add(cur); cur = word } else cur = n
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun save(bmp: Bitmap) {
        val (nm, _) = mediaName("IMG")
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$nm.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$brand/Image")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, o) } }
        runOnUiThread { if (uri != null) showResult(uri, false) else toast("Save failed") }
    }

    override fun onResume() {
        super.onResume(); refreshThumb()
        if (TrustedTime.ageMs() > 300_000) syncTime()
    }

    @Suppress("DEPRECATION")
    private fun isMockLoc(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    private fun syncTime() { TrustedTime.sync { runOnUiThread { updateStatus() } } }

    private fun updateStatus() {
        val t = if (TrustedTime.ok) "Time: server IST ✓" else "Time: not synced (check internet)"
        val tv = findViewById<TextView>(R.id.status)
        tv.setTextColor(if (mock) 0xFFFF5252.toInt() else 0xFFCCCCCC.toInt())
        tv.text = if (mock) "⚠ MOCK LOCATION DETECTED - turn it off\n$t" else "$gpsText\n$t"
    }

    private fun canCapture(): Boolean {
        if (mock) { toast("Mock location detected. Turn it off to capture."); return false }
        if (!TrustedTime.ok) { toast("Server time not synced. Check internet."); syncTime(); return false }
        return true
    }

    private fun mediaName(kind: String): Pair<String, String> {
        val f = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.ENGLISH).apply { timeZone = TrustedTime.IST }
            .format(Date(TrustedTime.now()))
        val r = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        return "${brand}_${kind}_${f}_$r" to f.substringBefore('_')
    }

    private fun refreshThumb() {
        io.execute {
            val m = loadMedia(this).firstOrNull()
            val bm = m?.let { thumbOf(this, it.uri, it.video, 200) }
            runOnUiThread { findViewById<ImageView>(R.id.btnGallery).setImageBitmap(bm) }
        }
    }

    private fun showResult(uri: Uri, video: Boolean) {
        refreshThumb()
        val iv = ImageView(this).apply { adjustViewBounds = true; setPadding(24, 24, 24, 0) }
        io.execute { val bm = thumbOf(this, uri, video, 640); runOnUiThread { bm?.let { iv.setImageBitmap(it) } } }
        AlertDialog.Builder(this)
            .setTitle(if (video) "Video saved" else "Photo saved").setView(iv)
            .setPositiveButton("Share") { _, _ -> shareUri(this, uri, video) }
            .setNeutralButton("Gallery") { _, _ -> startActivity(Intent(this, GalleryActivity::class.java)) }
            .setNegativeButton("Close", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
