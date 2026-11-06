package com.visitcam.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class LocationActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = Screen(this, tr("Location", "लोकेशन"))
        val l = Shared.loc
        if (l == null) { s.info(tr("Status", "स्थिति"), tr("GPS fix not ready yet", "GPS फ़िक्स अभी नहीं मिला")); return }
        val p = Shared.place
        val ms = if (TrustedTime.ok) TrustedTime.now() else System.currentTimeMillis()
        val coords = "%.6f, %.6f".format(java.util.Locale.US, l.latitude, l.longitude)
        s.info(tr("Place", "स्थान"), p?.region?.ifBlank { "—" } ?: "—")
        s.info(tr("Address", "पता"), p?.address?.ifBlank { "—" } ?: "—")
        s.info(tr("Latitude, Longitude", "अक्षांश, देशांतर"), coords)
        s.info(tr("Plus Code", "प्लस कोड"), plusCode(l.latitude, l.longitude))
        s.info(tr("Accuracy", "सटीकता"), "±%.0f m".format(l.accuracy))
        s.info(tr("Time (IST)", "समय (IST)"), fmtIst("EEEE, dd/MM/yyyy hh:mm a", ms))
        val text = "${p?.address ?: ""}\n$coords\nhttps://maps.google.com/?q=${l.latitude},${l.longitude}"
        s.button(tr("Copy", "कॉपी")) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("location", text))
            Toast.makeText(this, tr("Copied", "कॉपी हो गया"), Toast.LENGTH_SHORT).show()
        }
        s.button(tr("Share", "शेयर")) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
        }
        s.button(tr("Open in Maps", "मैप्स में खोलें")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}"))) }
            catch (e: Exception) { Toast.makeText(this, "No maps app", Toast.LENGTH_SHORT).show() }
        }
    }
}
