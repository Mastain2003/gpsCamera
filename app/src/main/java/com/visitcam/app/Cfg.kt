package com.visitcam.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Lang { @Volatile var hi = false }
fun tr(en: String, hi: String) = if (Lang.hi) hi else en

object Shared {
    @Volatile var loc: Location? = null
    @Volatile var place: Place? = null
}

fun Context.sp(): SharedPreferences = getSharedPreferences("cfg", Context.MODE_PRIVATE)
fun Context.getS(k: String, d: String): String = sp().getString(k, d) ?: d
fun Context.putS(k: String, v: String) = sp().edit().putString(k, v).apply()
fun Context.getB(k: String, d: Boolean): Boolean = sp().getBoolean(k, d)
fun Context.putB(k: String, v: Boolean) = sp().edit().putBoolean(k, v).apply()
fun Context.getI(k: String, d: Int): Int = sp().getInt(k, d)
fun Context.putI(k: String, v: Int) = sp().edit().putInt(k, v).apply()
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.isDark(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        Lang.hi = getS("lang", "en") == "hi"
        AppCompatDelegate.setDefaultNightMode(
            when (getS("theme", "light")) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        super.onCreate(b)
    }
}

const val FN_ORDER = "DATETIME,SEQ,C1,C2,C3,ADDR,LATLNG,PLUS,TZ"

fun fmtIst(p: String, ms: Long): String =
    SimpleDateFormat(p, Locale.ENGLISH).apply { timeZone = TrustedTime.IST }.format(Date(ms))

/** File name = BRAND_IMG_<chosen parts>_<4 char unique code> */
fun fileNameFor(ctx: Context, kind: String, ms: Long, lat: Double?, lng: Double?, address: String, seq: Int): String {
    val brand = ctx.getString(R.string.brand_name)
    val order = ctx.getS("fnOrder", FN_ORDER).split(",")
    val on = ctx.getS("fnOn", "DATETIME").split(",")
    val parts = mutableListOf(brand, kind)
    for (k in order) {
        if (k !in on) continue
        when (k) {
            "DATETIME" -> parts.add(fmtIst("ddMMyyyy_HHmmss", ms))
            "SEQ" -> parts.add("%04d".format(seq))
            "C1" -> parts.add(ctx.getS("c1", ""))
            "C2" -> parts.add(ctx.getS("c2", ""))
            "C3" -> parts.add(ctx.getS("c3", ""))
            "ADDR" -> parts.add(address.take(40))
            "LATLNG" -> if (lat != null && lng != null) parts.add("%.5f_%.5f".format(Locale.US, lat, lng))
            "PLUS" -> if (lat != null && lng != null) parts.add(plusCode(lat, lng).replace("+", ""))
            "TZ" -> parts.add("IST")
        }
    }
    parts.add((1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString(""))
    return parts.filter { it.isNotBlank() }.joinToString("_")
        .replace(Regex("[^A-Za-z0-9_.-]"), "_").replace(Regex("_+"), "_")
}

/** Small helper to build settings-style screens in code. */
class Screen(val a: BaseActivity, title: String) {
    val bg = if (a.isDark()) 0xFF121212.toInt() else Color.WHITE
    val fg = if (a.isDark()) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    val gray = 0xFF888888.toInt()
    val list = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(24))
    }

    init {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); fitsSystemWindows = true }
        root.addView(TextView(a).apply {
            text = "←   $title"; textSize = 20f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
            setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14)); setOnClickListener { a.finish() }
        })
        val sv = ScrollView(a); sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        a.setContentView(root)
    }

    fun section(t: String) {
        list.addView(TextView(a).apply { text = t.uppercase(); textSize = 13f; setTextColor(gray); setPadding(0, a.dp(18), 0, a.dp(6)) })
    }

    fun row(title: String, sub: String?, value: String, onClick: (TextView) -> Unit): TextView {
        val vt = TextView(a).apply { text = value; textSize = 14f; setTextColor(gray); gravity = Gravity.END }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(fg) })
        if (sub != null) left.addView(TextView(a).apply { text = sub; textSize = 13f; setTextColor(gray) })
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(12), 0, a.dp(12))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f)); addView(vt, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { onClick(vt) }
        }
        list.addView(r)
        return vt
    }

    fun toggle(title: String, sub: String?, key: String, def: Boolean, after: (() -> Unit)? = null) {
        val sw = SwitchCompat(a).apply { isChecked = a.getB(key, def) }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(fg) })
        if (sub != null) left.addView(TextView(a).apply { text = sub; textSize = 13f; setTextColor(gray) })
        sw.setOnCheckedChangeListener { _, v -> a.putB(key, v); after?.invoke() }
        list.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(12), 0, a.dp(12))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f)); addView(sw, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { sw.toggle() }
        })
    }

    fun choice(title: String, key: String, def: String, opts: List<Pair<String, String>>, after: (() -> Unit)? = null) {
        val cur = a.getS(key, def)
        row(title, null, opts.firstOrNull { it.first == cur }?.second ?: opts[0].second) { tv ->
            val idx = opts.indexOfFirst { it.first == a.getS(key, def) }.coerceAtLeast(0)
            AlertDialog.Builder(a).setTitle(title)
                .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), idx) { d, which ->
                    a.putS(key, opts[which].first); tv.text = opts[which].second; d.dismiss(); after?.invoke()
                }.show()
        }
    }

    fun text(title: String, key: String, hint: String, email: Boolean = false) {
        row(title, null, a.getS(key, "").ifBlank { "—" }) { tv ->
            val et = EditText(a).apply {
                setText(a.getS(key, "")); this.hint = hint
                inputType = if (email) InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT
            }
            AlertDialog.Builder(a).setTitle(title).setView(et)
                .setPositiveButton("OK") { _, _ -> val v = et.text.toString().trim(); a.putS(key, v); tv.text = v.ifBlank { "—" } }
                .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
        }
    }

    fun info(label: String, value: String) {
        list.addView(TextView(a).apply { text = label; textSize = 13f; setTextColor(gray); setPadding(0, a.dp(10), 0, 0) })
        list.addView(TextView(a).apply { text = value; textSize = 16f; setTextColor(fg); setTextIsSelectable(true) })
    }

    fun button(label: String, onClick: () -> Unit) {
        list.addView(Button(a).apply { text = label; setOnClickListener { onClick() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
    }
}

fun headingName(deg: Float): String {
    val en = arrayOf("North", "North East", "East", "South East", "South", "South West", "West", "North West")
    val hi = arrayOf("उत्तर", "उत्तर-पूर्व", "पूर्व", "दक्षिण-पूर्व", "दक्षिण", "दक्षिण-पश्चिम", "पश्चिम", "उत्तर-पश्चिम")
    val i = (((deg + 22.5f) / 45f).toInt()) % 8
    return if (Lang.hi) hi[i] else en[i]
}

fun headingLine(deg: Float): String = tr("Heading: ", "दिशा: ") + headingName(deg)
