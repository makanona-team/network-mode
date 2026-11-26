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
            .setTitle(tr("أهلاً بك في نمط الشبكة", "Welcome to Network Mode"))
            .setMessage(tr("• التطبيق يثبّت نمط الشبكة لكل شريحة.\n\n" +
                "• يحتاج إذن «الهاتف» ليعرض الشرائح والإشارة، والإشعارات للتنبيهات.\n\n" +
                "• يرسل للمطوّر فقط: نسخة التطبيق وموديل الجهاز ونسخة أندرويد، ليعرف أي الأجهزة يعمل عليها. لا أرقام ولا مواقع.",
                "• Locks the network mode for each SIM.\n\n" +
                "• Needs the Phone permission to show SIMs and signal, and notifications for alerts.\n\n" +
                "• Sends the developer only: app version, device model and Android version. No numbers, no location."))
            .setCancelable(false)
            .setPositiveButton(tr("متابعة", "Continue")) { _, _ -> Prefs.firstRunDone = true; askPerms() }
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
            .setTitle(tr("تحديث إجباري", "Update required"))
            .setMessage(tr("هذه النسخة لم تعد مدعومة. يجب تنزيل النسخة ", "This version is no longer supported. Please download version ") + Remote.latestName() + "\n\n" + Remote.whatsNew())
            .setCancelable(false)
            .setPositiveButton(tr("تحديث الآن", "Update now")) { _, _ -> startUpdate() }
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
            if (full) Drops.observe(this, list)
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
        val top = header(tr("نمط الشبكة", "Network Mode"), back = false)
        top.addView(TextView(this).apply {
            text = if (L.en()) "ع" else "EN"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.accent); setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { Prefs.lang = if (L.en()) "ar" else "en"; Shortcuts.refresh(this@MainActivity); recreate() }
        })
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
            tv(tr("يتوفر إصدار جديد ", "New version available ") + Remote.latestName(), 15f, p.accent, true, c)
            if (Remote.whatsNew().isNotBlank()) tv(Remote.whatsNew(), 13f, p.sub, parent = c)
            c.setOnClickListener { startUpdate() }
            tv(tr("اضغط للتحديث الآن", "Tap to update now"), 13f, p.accent, parent = c)
        }

        renderMethod()

        if (!Sims.has(this, Manifest.permission.READ_PHONE_STATE)) {
            val c = card(p.warnSoft)
            tv(tr("امنح إذن «الهاتف» لعرض الشرائح.", "Grant the Phone permission to show SIMs."), 14f, p.warn, true, c)
            c.setOnClickListener { askPerms() }
        }

        // اختيار الشريحة
        val s = slot()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..1) {
            val info = sims.firstOrNull { it.slot == i }
            val sel = i == s
            row.addView(TextView(this).apply {
                text = "SIM ${i + 1}" + (info?.carrier?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: if (info == null && sims.isNotEmpty()) tr("\nفارغة", "\nEmpty") else "")
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
            r.layoutDirection = dir
            tv(k, 14f, p.sub, parent = null).also {
                it.gravity = if (L.en()) Gravity.LEFT else Gravity.RIGHT
                r.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
            }
            tv(v, 14f, color, true, null).also {
                it.gravity = if (L.en()) Gravity.RIGHT else Gravity.LEFT
                r.addView(it, LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginStart = dp(8) })
            }
            st.addView(r)
        }
        val curMode = cur[s]
        line(tr("النمط الحالي", "Current mode"), if (Shell.ready()) famLabel(curMode) else (Prefs.last(s)?.title ?: tr("غير معروف (بدون Shizuku)", "Unknown (no Shizuku)")))
        if (info != null) {
            line(tr("الشبكة الآن", "Network now"), info.net, if (info.net == NO_NET()) p.warn else p.text)
            val sig = info.dbm?.let { "$it dBm" } ?: "—"
            val q = when (info.level) { 4 -> tr("ممتازة", "Excellent"); 3 -> tr("جيدة", "Good"); 2 -> tr("متوسطة", "Fair"); 1 -> tr("ضعيفة", "Weak"); else -> tr("ضعيفة جداً", "Very weak") }
            line(tr("الإشارة", "Signal"), "$sig · $q", when (info.level) { 3, 4 -> p.accent; 2 -> p.text; else -> p.warn })
            line("Band", info.band ?: if (Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) "—" else tr("امنح إذن الموقع", "Grant location"))
            line(tr("المكالمات", "Calls"), info.voice, if (info.voiceOk) p.text else p.warn)
        } else if (sims.isNotEmpty()) {
            line(tr("الحالة", "Status"), tr("لا توجد شريحة في هذا المنفذ", "No SIM in this slot"), p.warn)
        }
        if (info?.band == null && !Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            st.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2) }
        }

        // مؤقت فعّال
        val end = Prefs.timerEnd(s)
        if (end > System.currentTimeMillis()) {
            val left = ((end - System.currentTimeMillis()) / 60000L).toInt() + 1
            val c = card(p.accentSoft)
            tv(tr("⏱ يرجع إلى Auto بعد $left دقيقة — اضغط للإلغاء", "⏱ Back to Auto in $left min — tap to cancel"), 14f, p.accent, true, c)
            c.setOnClickListener { Timers.cancel(this, s); Prefs.setTimed(s, false); render() }
        }

        // أزرار الأنماط
        section(tr("اختر النمط — SIM ${s + 1}", "Choose mode — SIM ${s + 1}"))
        for (m in Prefs.visible) modeButton(s, m, curMode)

        // تطبيق المفضّل على الشريحتين
        val p0 = Prefs.preferred(0); val p1 = Prefs.preferred(1)
        if (p0 != null || p1 != null) {
            button(tr("تطبيق المفضّل على الشريحتين", "Apply favourites to both SIMs") +
                "\n" + listOfNotNull(p0?.let { "SIM1: ${it.title}" }, p1?.let { "SIM2: ${it.title}" }).joinToString(" · "), filled = false) {
                applyPreferred()
            }
        }

        // أنماط أخرى
        val others = Mode.values().filter { it !in Prefs.visible }
        if (others.isNotEmpty()) {
            var open = false
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val t = tv(tr("▾ أنماط أخرى (${others.size})", "▾ More modes (${others.size})"), 14f, p.accent, true).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            root.addView(box)
            others.forEach { modeButton(s, it, curMode, box) }
            t.setOnClickListener { open = !open; box.visibility = if (open) View.VISIBLE else View.GONE }
        }

        spacer(6)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = dir }
        button(tr("🩺 فحص التوافق", "🩺 Compatibility"), filled = false, parent = tools) {
            startActivity(Intent(this, CompatActivity::class.java))
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
        button(tr("📉 سجل الانقطاعات", "📉 Signal log"), filled = false, parent = tools) {
            startActivity(Intent(this, DropsActivity::class.java))
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        root.addView(tools)
        tv(tr("الإصدار ", "Version ") + BuildConfig.VERSION_NAME, 12f, p.sub).apply { gravity = Gravity.CENTER }
    }

    private fun renderMethod() {
        val k = Shell.kind()
        if (k != Shell.Kind.NONE) {
            tv(tr("✓ التطبيق المباشر يعمل عبر ", "✓ Direct apply works via ") + Shell.kindLabel(), 13f, p.accent).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
            return
        }
        val c = card(p.warnSoft)
        when {
            !Remote.on("shizuku_apply") ->
                tv(tr("التطبيق المباشر متوقف مؤقتاً من المطوّر. الأزرار ستفتح الإعدادات مع الشرح.", "Direct apply is paused by the developer. Buttons will open settings with instructions."), 14f, p.warn, true, c)
            Shell.shizukuRunning() && !Shell.shizukuGranted() -> {
                tv(tr("Shizuku يعمل — بقي منح الإذن للتطبيق", "Shizuku is running — grant permission"), 15f, p.warn, true, c)
                button(tr("منح الإذن", "Grant permission"), parent = c) {
                    try { Shizuku.requestPermission(7) } catch (e: Throwable) { toast(tr("حدّث تطبيق Shizuku", "Update Shizuku")) }
                }
            }
            else -> {
                tv(tr("⚠ الوضع اليدوي", "⚠ Manual mode"), 15f, p.warn, true, c)
                tv(if (Shell.shizukuInstalled(this)) tr("Shizuku مثبّت لكنه متوقف. شغّله ليعمل التطبيق بضغطة واحدة.", "Shizuku is installed but stopped. Start it for one-tap switching.")
                   else tr("بدون Shizuku تفتح الأزرار إعدادات الهاتف مع شرح ما تختاره. فعّل Shizuku مرة واحدة للتغيير بضغطة واحدة.", "Without Shizuku, buttons open phone settings with instructions. Enable Shizuku once for one-tap switching."),
                    13f, p.text, parent = c)
                button(tr("طريقة التفعيل", "How to enable"), parent = c) { startActivity(Intent(this, GuideActivity::class.java)) }
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
                .setTitle("LTE Only — SIM ${s + 1}")
                .setItems(items.toTypedArray()) { _, w -> doApply(s, m, TIMER_CHOICES[w]) }
                .setNegativeButton(tr("إلغاء", "Cancel"), null)
                .show()
            toast(tr("تنبيه: المكالمات قد لا تصلك. اختر مدة يرجع بعدها إلى Auto.", "Note: calls may fail. Choose when to return to Auto."))
            return
        }
        doApply(s, m, -1)
    }

    private fun doApply(s: Int, m: Mode, timer: Int) {
        busy = true
        val dlg = AlertDialog.Builder(this).setMessage(tr("جارٍ التطبيق…", "Applying…")).setCancelable(false).show()
        bg.execute {
            val r = Net.applyAndRecord(this, s, m, timer)
            val now = Net.current(s)
            main.post {
                busy = false
                try { dlg.dismiss() } catch (_: Exception) {}
                cur[s] = now
                if (r.ok) toast("✓ ${m.title} — SIM ${s + 1}" + if (r.msg.isNotBlank()) "\n${r.msg}" else "")
                else AlertDialog.Builder(this)
                    .setTitle(tr("تعذّر التطبيق المباشر", "Direct apply failed"))
                    .setMessage(r.msg + tr("\n\nيمكنك تطبيقه يدوياً.", "\n\nYou can apply it manually."))
                    .setPositiveButton(tr("الطريقة اليدوية", "Manual way")) { _, _ -> manual(s, m) }
                    .setNegativeButton(tr("إغلاق", "Close"), null).show()
                render()
                main.postDelayed({ refresh(false) }, 3000)
            }
        }
    }

    private fun applyPreferred() {
        if (!Shell.ready()) { toast(tr("يحتاج Shizuku", "Needs Shizuku")); return }
        busy = true
        bg.execute {
            val out = mutableListOf<String>()
            for (s in 0..1) {
                val m = Prefs.preferred(s) ?: continue
                if (sims.none { it.slot == s }) continue
                val r = Net.applyAndRecord(this, s, m)
                out += "SIM${s + 1}: ${m.title} " + if (r.ok) "✓" else "✗ ${r.msg}"
                cur[s] = Net.current(s)
            }
            main.post { busy = false; toast(out.joinToString("\n").ifBlank { tr("لا توجد شرائح", "No SIMs") }); render() }
        }
    }

    /** الطريقة اليدوية: إعدادات سامسونج إن كان النمط فيها، وإلا القائمة المخفية. */
    fun manual(s: Int, m: Mode) {
        Prefs.setLast(s, m.name)
        val viaSamsung = Net.isSamsung && m.samsung != null
        val steps = if (viaSamsung) tr(
            "في الشاشة التالية (إعدادات سامسونج):\n\n1) اضغط «وضع الشبكة» لـ SIM ${s + 1}\n2) اختر:\n     ${m.samsung}\n3) ارجع للتطبيق.",
            "On the next screen (Samsung settings):\n\n1) Tap \"Network mode\" for SIM ${s + 1}\n2) Choose:\n     ${m.samsung}\n3) Come back to the app.")
        else tr(
            "في الشاشة التالية:\n\n1) إذا فُتح تطبيق الهاتف والكود مكتوب ولم يحدث شيء: احذف آخر # وأعد كتابتها.\n" +
                "2) اختر «Phone information» (أو معلومات الهاتف).\n3) من الأعلى اختر Phone index: Phone$s\n" +
                "4) من «Set Preferred Network Type» اختر:\n     ${m.radioInfo}\n5) ارجع للتطبيق.",
            "On the next screen:\n\n1) If the Phone app opens with the code typed and nothing happens: delete the last # and type it again.\n" +
                "2) Choose \"Phone information\".\n3) At the top choose Phone index: Phone$s\n" +
                "4) In \"Set Preferred Network Type\" choose:\n     ${m.radioInfo}\n5) Come back to the app.")
        AlertDialog.Builder(this)
            .setTitle("${m.title} — SIM ${s + 1}")
            .setMessage(steps + "\n\n" + (m.warn?.let { "⚠ $it\n\n" } ?: "") +
                tr("للتغيير بضغطة واحدة بدون هذه الخطوات فعّل Shizuku.", "Enable Shizuku to switch with one tap."))
            .setPositiveButton(if (viaSamsung) tr("فتح الإعدادات", "Open settings") else tr("فتح القائمة", "Open menu")) { _, _ ->
                val ok = if (viaSamsung) Net.openSamsungNetwork(this) else Net.openRadioInfo(this)
                if (!ok) {
                    AlertDialog.Builder(this)
                        .setMessage(tr("تعذّر فتح الصفحة. افتح تطبيق الهاتف واكتب ${Net.SECRET} أو فعّل Shizuku.",
                            "Could not open it. Open the Phone app and dial ${Net.SECRET}, or enable Shizuku."))
                        .setPositiveButton(tr("طريقة التفعيل", "How to enable")) { _, _ -> startActivity(Intent(this, GuideActivity::class.java)) }
                        .show()
                }
            }
            .setNeutralButton(if (viaSamsung) tr("القائمة المخفية", "Hidden menu") else "Shizuku") { _, _ ->
                if (viaSamsung) {
                    // نفس الشرح لكن عبر القائمة المخفية
                    AlertDialog.Builder(this).setTitle("${m.title} — SIM ${s + 1}")
                        .setMessage(tr("اختر «Phone information» ← Phone index: Phone$s ← Set Preferred Network Type: ${m.radioInfo}",
                            "Choose \"Phone information\" → Phone index: Phone$s → Set Preferred Network Type: ${m.radioInfo}"))
                        .setPositiveButton(tr("فتح القائمة", "Open menu")) { _, _ -> Net.openRadioInfo(this) }
                        .show()
                } else startActivity(Intent(this, GuideActivity::class.java))
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }
}
