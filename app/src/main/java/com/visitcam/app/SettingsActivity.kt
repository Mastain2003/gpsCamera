package com.visitcam.app

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class SettingsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = Screen(this, tr("Settings", "सेटिंग्स"))
        s.list.addView(ImageView(this).apply { setImageResource(R.drawable.brand_logo) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        s.list.addView(TextView(this).apply {
            text = tr(getString(R.string.brand_full), getString(R.string.brand_full_hi)); textSize = 15f
            setTextColor(s.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, 0)
        })

        s.section(tr("General", "सामान्य"))
        s.choice(tr("Language", "भाषा"), "lang", "en", listOf("en" to "English", "hi" to "हिन्दी")) { recreate() }
        s.choice(tr("App Theme", "ऐप थीम"), "theme", "light", listOf(
            "light" to tr("Light mode", "लाइट मोड"), "dark" to tr("Dark mode", "डार्क मोड"), "system" to tr("System", "सिस्टम"))) { recreate() }

        s.section(tr("Camera", "कैमरा"))
        s.choice(tr("Photo Quality", "फोटो क्वालिटी"), "photoQ", "high", listOf(
            "high" to tr("High", "उच्च"), "medium" to tr("Medium", "मध्यम"), "low" to tr("Low (fast)", "कम (तेज़)")))
        s.choice(tr("Video Quality", "वीडियो क्वालिटी"), "videoQ", "hd", listOf(
            "sd" to "SD", "hd" to "HD", "fhd" to "Full HD", "uhd" to "4K"))
        s.choice(tr("Volume Button Action", "वॉल्यूम बटन एक्शन"), "volAction", "photo", listOf(
            "photo" to tr("Photo capture", "फोटो लें"), "zoom" to tr("Zoom", "ज़ूम"), "none" to tr("None", "कोई नहीं")))
        s.choice(tr("Screen Tap Action", "स्क्रीन टैप एक्शन"), "tapAction", "focus", listOf(
            "focus" to tr("Focus", "फ़ोकस"), "capture" to tr("Capture", "फोटो लें"), "none" to tr("None", "कोई नहीं")))

        s.section(tr("Stamp", "स्टैम्प"))
        s.row(tr("Stamp Template", "स्टैम्प टेम्पलेट"), tr("Tap to see previews", "प्रीव्यू देखने के लिए दबाएँ"), STYLE_NAMES[getI("style", 0)]) { tv ->
            pickStamp(this) { tv.text = STYLE_NAMES[getI("style", 0)] }
        }
        s.choice(tr("Stamp Width", "स्टैम्प चौड़ाई"), "orient", "auto", listOf(
            "auto" to tr("Compact (fits any photo)", "कॉम्पैक्ट (हर फोटो में फिट)"), "landscape" to tr("Full width", "पूरी चौड़ाई")))
        s.choice(tr("QR Code Content", "QR कोड में क्या हो"), "qrMode", "verify", listOf(
            "verify" to tr("Verification info", "सत्यापन जानकारी"), "maps" to tr("Google Maps link", "गूगल मैप्स लिंक")))
        s.choice(tr("Stamp Size", "स्टैम्प साइज़"), "size", "normal", listOf(
            "normal" to tr("Normal", "सामान्य"), "small" to tr("Small", "छोटा")))
        s.choice(tr("Stamp Opacity", "स्टैम्प पारदर्शिता"), "opacity", "medium", listOf(
            "low" to tr("Light", "हल्का"), "medium" to tr("Medium", "मध्यम"), "high" to tr("Dark", "गहरा")))
        s.toggle(tr("Show App Logo", "ऐप लोगो दिखाएँ"), tr("Brand logo on stamp", "स्टैम्प पर ब्रांड लोगो"), "logo", true)
        s.toggle(tr("Show Camera Indicator", "कैमरा संकेत दिखाएँ"), tr("Front/rear camera label on stamp", "स्टैम्प पर फ्रंट/रियर कैमरा लेबल"), "camInd", false)
        s.toggle(tr("Show Custom Names on Stamp", "स्टैम्प पर कस्टम नाम दिखाएँ"), tr("Custom Name 1 to 3 below", "नीचे कस्टम नाम 1 से 3"), "customOnStamp", false)

        s.section(tr("File Name", "फ़ाइल नाम"))
        s.row(tr("File Name Format", "फ़ाइल नाम फ़ॉर्मेट"), tr("Choose and reorder name parts", "नाम के हिस्से चुनें और क्रम बदलें"), "›") {
            startActivity(Intent(this, FileNameActivity::class.java))
        }
        s.text(tr("Custom Name 1", "कस्टम नाम 1"), "c1", tr("e.g. Staff name", "जैसे स्टाफ का नाम"))
        s.text(tr("Custom Name 2", "कस्टम नाम 2"), "c2", tr("e.g. Distributor code", "जैसे डिस्ट्रीब्यूटर कोड"))
        s.text(tr("Custom Name 3", "कस्टम नाम 3"), "c3", tr("e.g. Route", "जैसे रूट"))

        s.section(tr("Storage", "स्टोरेज"))
        s.toggle(tr("Auto Save Original Photos", "ओरिजिनल फोटो अपने-आप सेव करें"),
            tr("Save two photos: stamped and original", "दो फोटो सेव करें: स्टैम्प वाली और ओरिजिनल"), "autoOrig", false)

        s.section(tr("Report After Capture", "कैप्चर के बाद रिपोर्ट"))
        s.text(tr("Email Photo", "फोटो ईमेल करें"), "email", tr("Email address (empty = off)", "ईमेल पता (खाली = बंद)"), true)
        s.row(tr("Photo Report", "फोटो रिपोर्ट"), tr("Make a PDF from stamped photos", "स्टैम्प वाली फोटो से PDF बनाएँ"), "›") {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        s.toggle(tr("Share Photo", "फोटो शेयर करें"), tr("Open share options after each capture", "हर कैप्चर के बाद शेयर विकल्प खोलें"), "sharePhoto", true)
        s.toggle(tr("Reports", "रिपोर्ट्स"), tr("Show reports option on camera", "कैमरा पर रिपोर्ट विकल्प दिखाएँ"), "reportsTab", true)

        s.section(tr("Help & Support", "सहायता"))
        s.row(tr("How to Use", "कैसे इस्तेमाल करें"), null, "›") {
            info(tr("How to Use", "कैसे इस्तेमाल करें"), tr(
                "1. Allow camera, microphone and location.\n2. Wait for GPS and 'Time: server IST ✓'.\n3. Tap the round button for a photo, or switch to VIDEO.\n4. Tap the thumbnail to open the gallery; swipe to see more.\n5. Use ⚙ for settings and the file name format.",
                "1. कैमरा, माइक्रोफ़ोन और लोकेशन की अनुमति दें।\n2. GPS और 'Time: server IST ✓' का इंतज़ार करें।\n3. फोटो के लिए गोल बटन दबाएँ, या VIDEO चुनें।\n4. गैलरी के लिए थंबनेल दबाएँ; आगे-पीछे के लिए स्वाइप करें।\n5. सेटिंग्स और फ़ाइल नाम फ़ॉर्मेट के लिए ⚙ दबाएँ।"))
        }
        s.row("FAQ", null, "›") {
            info("FAQ", tr(
                "Why can't I capture?\nTurn off any mock-location app, switch on internet (for server time) and wait for the GPS fix.\n\nWhere are my files?\nDCIM/${getString(R.string.brand_name)}/Image and /Video.",
                "कैप्चर क्यों नहीं हो रहा?\nमॉक-लोकेशन ऐप बंद करें, इंटरनेट चालू करें (सर्वर टाइम के लिए) और GPS फ़िक्स का इंतज़ार करें।\n\nमेरी फ़ाइलें कहाँ हैं?\nDCIM/${getString(R.string.brand_name)}/Image और /Video।"))
        }

        s.section(tr("About", "जानकारी"))
        s.row(tr("Share App", "ऐप शेयर करें"), null, "›") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.app_name) + " - verified GPS photos and videos"), "Share"))
        }
        s.row(tr("Privacy Policy", "प्राइवेसी पॉलिसी"), null, "›") {
            info(tr("Privacy Policy", "प्राइवेसी पॉलिसी"), tr(
                "Photos, videos and location stay on your phone. The app uses the internet only for server time, the address and map picture.",
                "फोटो, वीडियो और लोकेशन आपके फ़ोन में ही रहते हैं। ऐप इंटरनेट का उपयोग सिर्फ़ सर्वर टाइम, पता और मैप चित्र के लिए करता है।"))
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        s.row(tr("App Version", "ऐप वर्ज़न"), null, ver) { }
    }

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
