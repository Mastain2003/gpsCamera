package com.visitcam.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class FileNameActivity : BaseActivity() {
    private lateinit var s: Screen
    private lateinit var preview: TextView
    private lateinit var rows: LinearLayout
    private var order = mutableListOf<String>()
    private var on = mutableSetOf<String>()

    private fun label(k: String) = when (k) {
        "DATETIME" -> tr("Date and Time", "तारीख और समय")
        "SEQ" -> tr("Sequence Number", "क्रम संख्या")
        "C1" -> tr("Custom Name 1", "कस्टम नाम 1")
        "C2" -> tr("Custom Name 2", "कस्टम नाम 2")
        "C3" -> tr("Custom Name 3", "कस्टम नाम 3")
        "ADDR" -> tr("Full Address", "पूरा पता")
        "LATLNG" -> tr("Latitude/Longitude", "अक्षांश/देशांतर")
        "PLUS" -> tr("Plus Code", "प्लस कोड")
        else -> tr("Time Zone", "समय क्षेत्र")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Screen(this, tr("File Name", "फ़ाइल नाम"))
        order = getS("fnOrder", FN_ORDER).split(",").toMutableList()
        FN_ORDER.split(",").forEach { if (it !in order) order.add(it) }
        on = getS("fnOn", "DATETIME").split(",").toMutableSet()
        preview = TextView(this).apply {
            textSize = 16f; setTextColor(s.fg); setPadding(dp(12), dp(12), dp(12), dp(12)); minLines = 3
            setBackgroundColor(if (isDark()) 0xFF1E1E1E.toInt() else 0xFFF3F3F3.toInt())
        }
        s.list.addView(preview)
        s.list.addView(TextView(this).apply {
            text = tr("Brand, IMG/VID and a 4-letter unique code are always added.", "ब्रांड, IMG/VID और 4 अक्षर का यूनिक कोड हमेशा जुड़ते हैं।")
            textSize = 12f; setTextColor(s.gray); setPadding(0, dp(6), 0, dp(6))
        })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.list.addView(rows)
        render()
    }

    private fun save() {
        putS("fnOrder", order.joinToString(","))
        putS("fnOn", on.joinToString(","))
    }

    private fun render() {
        save()
        val l = Shared.loc
        val ms = if (TrustedTime.ok) TrustedTime.now() else System.currentTimeMillis()
        preview.text = fileNameFor(this, "IMG", ms, l?.latitude ?: 23.62995, l?.longitude ?: 75.111643,
            Shared.place?.address ?: "Sample address", getI("seq", 0) + 1) + ".jpg"
        rows.removeAllViews()
        order.forEachIndexed { i, k ->
            val cb = CheckBox(this).apply {
                text = label(k) + (if (k.startsWith("C")) "  (" + getS(k.lowercase(), "").ifBlank { "—" } + ")" else "")
                textSize = 16f; setTextColor(s.fg); isChecked = k in on
                setOnCheckedChangeListener { _, v -> if (v) on.add(k) else on.remove(k); render() }
            }
            fun arrow(t: String, d: Int) = TextView(this).apply {
                text = t; textSize = 22f; setTextColor(s.fg); setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    val j = i + d
                    if (j in order.indices) { val x = order[i]; order[i] = order[j]; order[j] = x; render() }
                }
            }
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(cb, LinearLayout.LayoutParams(0, -2, 1f)); addView(arrow("▲", -1)); addView(arrow("▼", 1))
            })
        }
    }
}
