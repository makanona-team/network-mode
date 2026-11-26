package com.makanona.netmode

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : BaseActivity() {

    private var sims: List<SimInfo> = emptyList()
    private val cur = HashMap<Int, Int?>()
    private var busy = false
    private var resumed = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { refresh(true) } }
    private val binderListener = Shizuku.OnBinderReceivedListener { main.post { refresh(true) } }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { render() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDead)
        } catch (_: Throwable) {}
        if (!Prefs.firstRunDone) firstRun() else askPerms()
        handleManual(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleManual(i) }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDead)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh(true)
        main.postDelayed(loop, 5000)
        bg.execute {
            if (Remote.refresh(this, force = false)) main.post { render(); checkForced() }
        }
        checkForced()
        MonitorService.sync(this)
        Shortcuts.refresh(this)
    }

    override fun onPause() { resumed = false; main.removeCallbacks(loop); super.onPause() }

    private val loop = object : Runnable {
        override fun run() { if (resumed) { refresh(false); main.postDelayed(this, 5000) } }
    }

    private fun firstRun() {
        AlertDialog.Builder(this)
            .setTitle("أهلاً بك في نمط الشبكة")
            .setMessage("• التطبيق يثبّت نمط الشبكة لكل شريحة.\n\n" +
                "• يحتاج إذن «الهاتف» ليعرض الشرائح والإشارة، والإشعارات للتنبيهات.\n\n" +
                "• يرسل للمطوّر فقط: نسخة التطبيق وموديل الجهاز ونسخة أندرويد، ليعرف أي الأجهزة يعمل عليها. لا أرقام ولا مواقع.")
            .setCancelable(false)
            .setPositiveButton("متابعة") { _, _ -> Prefs.firstRunDone = true; askPerms() }
            .show()
    }

    private fun askPerms() {
        val need = mutableListOf<String>()
        if (!Sims.has(this, Manifest.permission.READ_PHONE_STATE)) need += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= 33 && !Sims.has(this, Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        refresh(true); MonitorService.sync(this)
    }

    private fun checkForced() {
        if (!Remote.mustUpdate()) return
        AlertDialog.Builder(this)
            .setTitle("تحديث إجباري")
            .setMessage("هذه النسخة لم تعد مدعومة. يجب تنزيل النسخة ${Remote.latestName()} للمتابعة.\n\n${Remote.whatsNew()}")
            .setCancelable(false)
            .setPositiveButton("تنزيل التحديث") { _, _ -> openUrl(Remote.latestUrl()); finish() }
            .show()
    }

    /** وصلنا من اختصار أو إشعار بدون Shizuku: نعرض الطريقة اليدوية. */
    private fun handleManual(i: Intent?) {
        val m = Mode.of(i?.getStringExtra("manual_mode")) ?: return
        val s = i?.getIntExtra("manual_slot", 0) ?: 0
        i?.removeExtra("manual_mode")
        manual(s, m)
    }

    private fun refresh(full: Boolean) {
        bg.execute {
            val list = Sims.list(this)
            if (full || cur.isEmpty()) for (s in 0..1) cur[s] = Net.current(s)
            main.post {
                sims = list
                if (!busy) render()
            }
        }
    }

    private fun slot(): Int {
        val s = Prefs.slot
        if (sims.isNotEmpty() && sims.none { it.slot == s }) return sims.first().slot
        return s
    }

    private fun render() {
        clear()
        // الشريط العلوي
        val top = header("نمط الشبكة", back = false)
        val unread = Remote.unread()
        top.addView(TextView(this).apply {
            text = if (unread > 0) "🔔 $unread" else "🔔"; textSize = 18f
            setTextColor(if (unread > 0) p.warn else p.text); setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, NewsActivity::class.java)) }
        })
        top.addView(TextView(this).apply {
            text = "⚙"; textSize = 21f; setTextColor(p.text); setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        if (Remote.hasUpdate()) {
            val c = card(p.accentSoft)
            tv("يتوفر إصدار جديد ${Remote.latestName()}", 15f, p.accent, true, c)
            if (Remote.whatsNew().isNotBlank()) tv(Remote.whatsNew(), 13f, p.sub, parent = c)
            c.setOnClickListener { openUrl(Remote.latestUrl()) }
            tv("اضغط للتنزيل", 13f, p.accent, parent = c)
        }

        renderMethod()

        if (!Sims.has(this, Manifest.permission.READ_PHONE_STATE)) {
            val c = card(p.warnSoft)
            tv("امنح إذن «الهاتف» لعرض الشرائح.", 14f, p.warn, true, c)
            c.setOnClickListener { askPerms() }
        }

        // اختيار الشريحة
        val s = slot()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..1) {
            val info = sims.firstOrNull { it.slot == i }
            val sel = i == s
            row.addView(TextView(this).apply {
                text = "شريحة ${i + 1}" + (info?.carrier?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: if (info == null && sims.isNotEmpty()) "\nفارغة" else "")
                gravity = Gravity.CENTER; textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (sel) Color.WHITE else p.text)
                background = ripple(bgRound(if (sel) p.accent else p.card, 12))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                alpha = if (info == null && sims.isNotEmpty()) 0.5f else 1f
                setOnClickListener { Prefs.slot = i; Shortcuts.refresh(this@MainActivity); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = dp(8) })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // بطاقة الحالة
        val info = sims.firstOrNull { it.slot == s }
        val st = card()
        fun line(k: String, v: String, color: Int = p.text) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            tv(k, 14f, p.sub, parent = null).also { r.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            tv(v, 14f, color, true, null).also { it.gravity = Gravity.END; r.addView(it, LinearLayout.LayoutParams(0, -2, 1.4f)) }
            st.addView(r)
        }
        val curMode = cur[s]
        line("النمط الحالي", if (Shell.ready()) famLabel(curMode) else (Prefs.last(s)?.title ?: "غير معروف (بدون Shizuku)"))
        if (info != null) {
            line("الشبكة الآن", info.net, if (info.net == "لا شبكة") p.warn else p.text)
            val sig = info.dbm?.let { "$it dBm" } ?: "—"
            val q = when (info.level) { 4 -> "ممتازة"; 3 -> "جيدة"; 2 -> "متوسطة"; 1 -> "ضعيفة"; else -> "ضعيفة جداً" }
            line("الإشارة", "$sig · $q", when (info.level) { 3, 4 -> p.accent; 2 -> p.text; else -> p.warn })
            line("Band", info.band ?: if (Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) "—" else "امنح إذن الموقع")
            line("المكالمات", info.voice, if (info.voiceOk) p.text else p.warn)
        } else if (sims.isNotEmpty()) {
            line("الحالة", "لا توجد شريحة في هذا المنفذ", p.warn)
        }
        if (info?.band == null && !Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            st.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2) }
        }

        // مؤقت فعّال
        val end = Prefs.timerEnd(s)
        if (end > System.currentTimeMillis()) {
            val left = ((end - System.currentTimeMillis()) / 60000L).toInt() + 1
            val c = card(p.accentSoft)
            tv("⏱ يرجع للتلقائي الشامل بعد $left دقيقة — اضغط للإلغاء", 14f, p.accent, true, c)
            c.setOnClickListener { Timers.cancel(this, s); Prefs.setTimed(s, false); render() }
        }

        // أزرار الأنماط
        section("اختر النمط لشريحة ${s + 1}")
        for (m in Prefs.visible) modeButton(s, m, curMode)

        // تطبيق المفضّل على الشريحتين
        val p0 = Prefs.preferred(0); val p1 = Prefs.preferred(1)
        if (p0 != null || p1 != null) {
            button("تطبيق المفضّل على الشريحتين" +
                "\n" + listOfNotNull(p0?.let { "ش1: ${it.title}" }, p1?.let { "ش2: ${it.title}" }).joinToString(" · "), filled = false) {
                applyPreferred()
            }
        }

        // أنماط أخرى
        val others = Mode.values().filter { it !in Prefs.visible }
        if (others.isNotEmpty()) {
            var open = false
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val t = tv("▾ أنماط أخرى (${others.size})", 14f, p.accent, true).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            root.addView(box)
            others.forEach { modeButton(s, it, curMode, box) }
            t.setOnClickListener { open = !open; box.visibility = if (open) View.VISIBLE else View.GONE }
        }

        spacer(6)
        tv("فحص التوافق  ·  الإصدار ${BuildConfig.VERSION_NAME}", 13f, p.sub).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(this@MainActivity, CompatActivity::class.java)) }
        }
    }

    private fun renderMethod() {
        val k = Shell.kind()
        if (k != Shell.Kind.NONE) {
            tv("✓ التطبيق المباشر يعمل عبر ${Shell.kindLabel()}", 13f, p.accent).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
            return
        }
        val c = card(p.warnSoft)
        when {
            !Remote.on("shizuku_apply") ->
                tv("التطبيق المباشر متوقف مؤقتاً من المطوّر. الأزرار ستفتح قائمة Phone info مع الشرح.", 14f, p.warn, true, c)
            Shell.shizukuRunning() && !Shell.shizukuGranted() -> {
                tv("Shizuku يعمل — بقي منح الإذن للتطبيق", 15f, p.warn, true, c)
                button("منح الإذن", parent = c) {
                    try { Shizuku.requestPermission(7) } catch (e: Throwable) { toast("حدّث تطبيق Shizuku") }
                }
            }
            else -> {
                tv("⚠ الوضع اليدوي", 15f, p.warn, true, c)
                tv(if (Shell.shizukuInstalled(this)) "Shizuku مثبّت لكنه متوقف. شغّله ليعمل التطبيق بضغطة واحدة."
                   else "فعّل Shizuku مرة واحدة ليعمل التطبيق بضغطة واحدة. بدونه تفتح الأزرار قائمة Phone info مع الشرح.",
                    13f, p.text, parent = c)
                button("طريقة التفعيل", parent = c) { startActivity(Intent(this, GuideActivity::class.java)) }
            }
        }
    }

    private fun modeButton(s: Int, m: Mode, curMode: Int?, parent: LinearLayout = root) {
        val active = if (Shell.ready()) curMode == m.fam else Prefs.last(s) == m
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ripple(bgRound(if (active) p.accentSoft else p.card, 16, if (active) 2 else 1, if (active) p.accent else p.line))
        }
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        tv((if (active) "● " else "") + m.title, 18f, if (active) p.accent else p.text, true, c)
        if (m.sub.isNotBlank()) tv(m.sub, 13f, p.sub, parent = c)
        if (m.warn != null) tv("⚠ ${m.warn}", 12f, p.warn, parent = c)
        c.setOnClickListener { onMode(s, m) }
    }

    private fun onMode(s: Int, m: Mode) {
        if (!Shell.ready()) { manual(s, m); return }
        if (m == Mode.LTE_ONLY && Remote.on("lte_timer")) {
            val items = TIMER_CHOICES.map { timerLabel(it) }
            AlertDialog.Builder(this)
                .setTitle("LTE فقط — شريحة ${s + 1}")
                .setItems(items.toTypedArray()) { _, w -> doApply(s, m, TIMER_CHOICES[w]) }
                .setNegativeButton("إلغاء", null)
                .show()
            toast("تنبيه: المكالمات قد لا تصلك. اختر مدة يرجع بعدها للتلقائي.")
            return
        }
        doApply(s, m, -1)
    }

    private fun doApply(s: Int, m: Mode, timer: Int) {
        busy = true
        val dlg = AlertDialog.Builder(this).setMessage("جارٍ التطبيق…").setCancelable(false).show()
        bg.execute {
            val r = Net.applyAndRecord(this, s, m, timer)
            val now = Net.current(s)
            main.post {
                busy = false
                try { dlg.dismiss() } catch (_: Exception) {}
                cur[s] = now
                if (r.ok) toast("✓ ${m.title} — شريحة ${s + 1}" + if (r.msg.isNotBlank()) "\n${r.msg}" else "")
                else AlertDialog.Builder(this)
                    .setTitle("تعذّر التطبيق المباشر")
                    .setMessage("${r.msg}\n\nيمكنك تطبيقه يدوياً من قائمة Phone info.")
                    .setPositiveButton("الطريقة اليدوية") { _, _ -> manual(s, m) }
                    .setNegativeButton("إغلاق", null).show()
                render()
                main.postDelayed({ refresh(false) }, 3000)
            }
        }
    }

    private fun applyPreferred() {
        if (!Shell.ready()) { toast("يحتاج Shizuku"); return }
        busy = true
        bg.execute {
            val out = mutableListOf<String>()
            for (s in 0..1) {
                val m = Prefs.preferred(s) ?: continue
                if (sims.none { it.slot == s }) continue
                val r = Net.applyAndRecord(this, s, m)
                out += "ش${s + 1}: ${m.title} " + if (r.ok) "✓" else "✗ ${r.msg}"
                cur[s] = Net.current(s)
            }
            main.post { busy = false; toast(out.joinToString("\n").ifBlank { "لا توجد شرائح" }); render() }
        }
    }

    /** الطريقة اليدوية: شرح ثم فتح قائمة Phone info. */
    fun manual(s: Int, m: Mode) {
        Prefs.setLast(s, m.name)
        AlertDialog.Builder(this)
            .setTitle("${m.title} — شريحة ${s + 1}")
            .setMessage("في الشاشة التالية:\n\n1) من الأعلى اختر Phone index: Phone$s\n" +
                "2) من «Set Preferred Network Type» اختر:\n     ${m.radioInfo}\n3) ارجع للتطبيق.\n\n" +
                (m.warn?.let { "⚠ $it\n\n" } ?: "") +
                "لتطبيق النمط بضغطة واحدة بدون هذه الخطوات فعّل Shizuku.")
            .setPositiveButton("فتح القائمة") { _, _ ->
                if (!Net.openRadioInfo(this)) {
                    AlertDialog.Builder(this).setMessage("قائمة Phone info غير متاحة على جهازك. فعّل Shizuku.")
                        .setPositiveButton("طريقة التفعيل") { _, _ -> startActivity(Intent(this, GuideActivity::class.java)) }
                        .show()
                }
            }
            .setNeutralButton("تفعيل Shizuku") { _, _ -> startActivity(Intent(this, GuideActivity::class.java)) }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
