package com.makanona.netmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** ألوان وأدوات بناء الواجهة بالكود. */
class Pal(c: Context) {
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) 0xFF121614.toInt() else 0xFFF4F6F5.toInt()
    val card = if (dark) 0xFF1D2321.toInt() else Color.WHITE
    val text = if (dark) 0xFFE8EEEC.toInt() else 0xFF17201D.toInt()
    val sub = if (dark) 0xFF9BA8A4.toInt() else 0xFF5F6B67.toInt()
    val accent = if (dark) 0xFF3FC1A5.toInt() else 0xFF0E6E5C.toInt()
    val accentSoft = if (dark) 0xFF1E3A33.toInt() else 0xFFDDF1EC.toInt()
    val warn = if (dark) 0xFFFF8A7A.toInt() else 0xFFC0392B.toInt()
    val warnSoft = if (dark) 0xFF3A2220.toInt() else 0xFFFDECEA.toInt()
    val line = if (dark) 0xFF2B3330.toInt() else 0xFFE3E8E6.toInt()
}

abstract class BaseActivity : Activity() {
    lateinit var p: Pal
    lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private var langAtCreate = ""

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    val dir get() = if (L.en()) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL

    override fun attachBaseContext(base: Context) {
        Prefs.init(base)
        val loc = java.util.Locale(L.tag())
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc); cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onResume() {
        super.onResume()
        if (langAtCreate.isNotEmpty() && langAtCreate != L.tag()) recreate()
    }

    override fun onCreate(b: Bundle?) {
        langAtCreate = L.tag()
        super.onCreate(b)
        Prefs.init(this)
        p = Pal(this)
        scroll = ScrollView(this).apply { setBackgroundColor(p.bg); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = dir
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    fun clear() = root.removeAllViews()

    fun bgRound(color: Int, r: Int = 16, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    fun ripple(d: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x22000000), d, null)

    fun tv(s: CharSequence, size: Float = 15f, color: Int = p.text, bold: Boolean = false, parent: ViewGroup? = root): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            textDirection = View.TEXT_DIRECTION_LOCALE
            parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun header(title: String, back: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back) {
            row.addView(TextView(this).apply {
                text = if (L.en()) "←" else "→"; textSize = 22f; setTextColor(p.text); setPadding(dp(4), dp(4), dp(12), dp(4))
                setOnClickListener { finish() }
            })
        }
        row.addView(TextView(this).apply { text = title; textSize = 21f; setTextColor(p.text); typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return row
    }

    fun card(color: Int = p.card, parent: ViewGroup = root, pad: Int = 14): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgRound(color)
            setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

    fun section(t: String) = tv(t, 13f, p.sub, true).apply { setPadding(dp(4), dp(10), dp(4), dp(6)) }

    fun button(t: String, filled: Boolean = true, parent: ViewGroup = root, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) Color.WHITE else p.accent)
            background = ripple(bgRound(if (filled) p.accent else p.accentSoft, 12))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    /** صف بعنوان وشرح ومفتاح. */
    fun switchRow(title: String, sub: String?, value: Boolean, parent: ViewGroup = root, onChange: (Boolean) -> Unit) {
        val c = card(parent = parent)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tv(title, 15f, p.text, true, texts)
        if (!sub.isNullOrBlank()) tv(sub, 13f, p.sub, parent = texts)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, v -> onChange(v) } }
        row.addView(sw)
        c.addView(row)
        c.setOnClickListener { sw.toggle() }
    }

    /** صف قابل للضغط بعنوان وقيمة. */
    fun linkRow(title: String, value: String?, parent: ViewGroup = root, onClick: () -> Unit): LinearLayout {
        val c = card(parent = parent)
        tv(title, 15f, p.text, true, c)
        if (!value.isNullOrBlank()) tv(value, 13f, p.accent, parent = c)
        c.background = ripple(bgRound(p.card))
        c.setOnClickListener { onClick() }
        return c
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { toast(tr("تعذّر فتح الرابط", "Could not open link")) }
    }

    fun spacer(h: Int = 8) = root.addView(View(this), LinearLayout.LayoutParams(-1, dp(h)))

    fun pick(title: String, items: List<String>, onPick: (Int) -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, w -> onPick(w) }
            .setNegativeButton(tr("إلغاء", "Cancel"), null).show()
    }

    fun timerLabel(min: Int) = when (min) {
        0 -> tr("دائم (بدون مؤقت)", "Permanent (no timer)")
        30 -> tr("30 دقيقة", "30 minutes"); 60 -> tr("ساعة", "1 hour")
        180 -> tr("3 ساعات", "3 hours"); 480 -> tr("8 ساعات", "8 hours")
        else -> tr("$min دقيقة", "$min minutes")
    }
}

val TIMER_CHOICES = listOf(30, 60, 180, 480, 0)
