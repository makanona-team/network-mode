package com.makanona.netmode

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import rikka.shizuku.Shizuku

class SettingsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        clear()
        header(tr("الإعدادات", "Settings"))

        section(tr("اللغة", "Language"))
        val langs = listOf("auto" to tr("لغة الهاتف", "Phone language"), "ar" to "العربية", "en" to "English")
        linkRow(tr("لغة التطبيق", "App language"), langs.first { it.first == Prefs.lang }.second) {
            pick(tr("لغة التطبيق", "App language"), langs.map { it.second }) { w ->
                Prefs.lang = langs[w].first
                Notif.channels(this); Shortcuts.refresh(this); recreate()
            }
        }

        section(tr("الأزرار على الشاشة الرئيسية", "Home screen buttons"))
        linkRow(tr("اختر الأزرار الظاهرة", "Choose visible buttons"), Prefs.visible.joinToString(" · ") { it.title }) { chooseVisible() }

        section(tr("النمط المفضّل لكل شريحة", "Favourite mode per SIM"))
        for (s in 0..1) linkRow("SIM ${s + 1}", Prefs.preferred(s)?.title ?: tr("غير محدد (يُستخدم آخر نمط)", "Not set (last mode is used)")) {
            val all = Mode.values().toList()
            pick(tr("النمط المفضّل — SIM ${s + 1}", "Favourite mode — SIM ${s + 1}"), listOf(tr("غير محدد", "Not set")) + all.map { it.title }) { w ->
                Prefs.setPreferred(s, if (w == 0) null else all[w - 1]); render()
            }
        }

        section("LTE Only")
        linkRow(tr("المدة الافتراضية للمؤقت", "Default timer"), timerLabel(Prefs.lteTimerMin)) {
            pick(tr("يرجع إلى Auto بعد", "Return to Auto after"), TIMER_CHOICES.map { timerLabel(it) }) { Prefs.lteTimerMin = TIMER_CHOICES[it]; render() }
        }

        section(tr("تلقائي", "Automatic"))
        switchRow(tr("إعادة التطبيق بعد تشغيل الهاتف", "Re-apply after reboot"),
            tr("يرجع كل شريحة لنمطها بعد إعادة التشغيل (بعد تشغيل Shizuku)", "Restores each SIM's mode after reboot (once Shizuku starts)"), Prefs.reapplyOnBoot) {
            Prefs.reapplyOnBoot = it
        }
        switchRow(tr("تنبيه انقطاع الشبكة", "Signal loss alert"),
            tr("إشعار إذا بقيت الشريحة بلا شبكة أو بلا مكالمات أكثر من دقيقة", "Notify if a SIM has no service or no calls for over a minute"), Prefs.dropAlert) {
            Prefs.dropAlert = it; MonitorService.sync(this)
        }
        switchRow(tr("تحويل مؤقت أثناء المكالمة (تجريبي)", "Switch during calls (experimental)"),
            tr("عند بدء مكالمة وأنت على LTE Only يسمح بـ 3G/2G ثم يرجع بعد انتهائها. لا يحل مشكلة المكالمات الواردة.",
                "When a call starts on LTE Only, allows 3G/2G and returns after the call. Doesn't fix incoming calls."), Prefs.callSwitch) {
            Prefs.callSwitch = it; MonitorService.sync(this)
        }
        switchRow(tr("إشعار دائم بالحالة", "Persistent status notification"),
            tr("يعرض النمط والإشارة لكل شريحة في شريط الإشعارات", "Shows mode and signal per SIM in the notification bar"), Prefs.persistent) {
            Prefs.persistent = it; MonitorService.sync(this)
        }

        section(tr("الزر السريع في شريط الإشعارات", "Quick Settings tile"))
        linkRow(tr("الشريحة", "SIM"), "SIM ${Prefs.tileSlot + 1}") {
            pick(tr("الشريحة", "SIM"), listOf("SIM 1", "SIM 2")) { Prefs.tileSlot = it; render() }
        }
        linkRow(tr("النمط الأول", "First mode"), Prefs.tileA.title) {
            pick(tr("النمط الأول", "First mode"), Mode.values().map { it.title }) { Prefs.tileA = Mode.values()[it]; render() }
        }
        linkRow(tr("النمط الثاني", "Second mode"), Prefs.tileB.title) {
            pick(tr("النمط الثاني", "Second mode"), Mode.values().map { it.title }) { Prefs.tileB = Mode.values()[it]; render() }
        }
        tv(tr("لإضافته: اسحب شريط الإشعارات للأسفل ← زر القلم/التعديل ← اسحب «نمط الشبكة» للأعلى.",
            "To add it: pull down the notification bar → edit (pencil) → drag \"Network Mode\" up."), 12f, p.sub)
            .apply { setPadding(dp(4), 0, dp(4), dp(8)) }

        section(tr("اختصار على الشاشة الرئيسية", "Home screen shortcut"))
        linkRow(tr("إضافة اختصار", "Add shortcut"), tr("اختر الشريحة والنمط", "Choose SIM and mode")) {
            pick(tr("الشريحة", "SIM"), listOf("SIM 1", "SIM 2")) { s ->
                pick(tr("النمط", "Mode"), Mode.values().map { it.title }) { w ->
                    if (!Shortcuts.pin(this, s, Mode.values()[w])) toast(tr("الواجهة لا تدعم الاختصارات المثبّتة", "Launcher doesn't support pinned shortcuts"))
                }
            }
        }

        section(tr("الصلاحيات والبطارية", "Permissions & battery"))
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        linkRow(tr("استثناء البطارية", "Battery exemption"),
            if (ignoring) tr("✓ مستثنى", "✓ Exempted") else tr("غير مستثنى — اضغط (ضروري للمؤقت والتنبيهات)", "Not exempted — tap (needed for timer and alerts)")) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        if (Net.isSamsung) {
            linkRow(tr("إعداد سامسونج للخلفية", "Samsung background setting"),
                tr("الإعدادات ← البطارية ← حدود استخدام الخلفية ← «التطبيقات التي لا تدخل في وضع السكون أبداً» ← أضف «نمط الشبكة». اضغط لفتح صفحة التطبيق.",
                    "Settings → Battery → Background usage limits → \"Never sleeping apps\" → add \"Network Mode\". Tap to open the app page.")) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        val loc = Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)
        linkRow(tr("إذن الموقع", "Location permission"),
            if (loc) tr("✓ ممنوح (لعرض الـ Band وحالة المكالمات)", "✓ Granted (for Band and call status)")
            else tr("اختياري — لعرض الـ Band وحالة المكالمات", "Optional — shows Band and call status")) {
            if (!loc) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3)
        }

        section(tr("طريقة التطبيق", "Apply method"))
        linkRow("Shizuku", when {
            Shell.shizukuGranted() -> tr("✓ يعمل ومُنح الإذن", "✓ Running and granted")
            Shell.shizukuRunning() -> tr("يعمل — اضغط لمنح الإذن", "Running — tap to grant")
            Shell.shizukuInstalled(this) -> tr("مثبّت لكنه متوقف", "Installed but stopped")
            else -> tr("غير مثبّت — اضغط للشرح", "Not installed — tap for guide")
        }) {
            if (Shell.shizukuRunning() && !Shell.shizukuGranted()) {
                try { Shizuku.requestPermission(7) } catch (_: Throwable) {}
            } else {
                startActivity(Intent(this, GuideActivity::class.java))
            }
        }
        switchRow(tr("استخدام الروت", "Use root"), tr("للهواتف المروّتة فقط", "Rooted phones only"), Prefs.useRoot) { v ->
            if (!v) { Prefs.useRoot = false; return@switchRow }
            bg.execute {
                val ok = Shell.testRoot()
                main.post { Prefs.useRoot = ok; if (!ok) toast(tr("لا توجد صلاحية روت", "No root access")); render() }
            }
        }

        section(tr("النسخ الاحتياطي والتحديث", "Backup & updates"))
        linkRow(tr("نسخ الإعدادات للسيرفر", "Back up settings"), null) {
            bg.execute { val ok = Remote.backup(this); main.post { toast(if (ok) tr("✓ تم النسخ", "✓ Backed up") else tr("تعذّر الاتصال", "Connection failed")) } }
        }
        linkRow(tr("استعادة الإعدادات", "Restore settings"), null) {
            bg.execute { val ok = Remote.restore(); main.post { toast(if (ok) tr("✓ تمت الاستعادة", "✓ Restored") else tr("لا توجد نسخة", "No backup found")); recreate() } }
        }
        linkRow(tr("التحقق من التحديثات", "Check for updates"), tr("الإصدار الحالي ", "Current version ") + BuildConfig.VERSION_NAME) {
            bg.execute {
                val ok = Remote.refresh(this, force = true)
                main.post {
                    when {
                        !ok -> toast(tr("تعذّر الاتصال بالسيرفر", "Could not reach the server"))
                        Remote.hasUpdate() -> AlertDialog.Builder(this).setTitle(tr("يتوفر ", "Available: ") + Remote.latestName())
                            .setMessage(Remote.whatsNew()).setPositiveButton(tr("تنزيل", "Download")) { _, _ -> openUrl(Remote.latestUrl()) }
                            .setNegativeButton(tr("لاحقاً", "Later"), null).show()
                        else -> toast(tr("أنت على آخر إصدار", "You're on the latest version"))
                    }
                }
            }
        }
        linkRow(tr("كل الإصدارات", "All releases"), null) { openUrl(Remote.RELEASES) }
    }

    private fun chooseVisible() {
        val all = Mode.values()
        val checked = all.map { it in Prefs.visible }.toBooleanArray()
        AlertDialog.Builder(this).setTitle(tr("الأزرار الظاهرة (حتى 6)", "Visible buttons (up to 6)"))
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, w, v -> checked[w] = v }
            .setPositiveButton(tr("حفظ", "Save")) { _, _ ->
                val sel = all.filterIndexed { i, _ -> checked[i] }.take(6)
                Prefs.visible = sel.ifEmpty { Mode.DEFAULT_VISIBLE }
                Shortcuts.refresh(this); render()
            }
            .setNeutralButton(tr("الافتراضي", "Default")) { _, _ -> Prefs.visible = Mode.DEFAULT_VISIBLE; render() }
            .setNegativeButton(tr("إلغاء", "Cancel"), null).show()
    }
}

class GuideActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    override fun onResume() { super.onResume(); render() }

    private fun step(n: Int, t: String, d: String, done: Boolean = false) {
        val c = card(if (done) p.accentSoft else p.card)
        tv((if (done) "✓ " else "$n) ") + t, 16f, if (done) p.accent else p.text, true, c)
        tv(d, 14f, p.sub, parent = c)
    }

    private fun render() {
        clear()
        header(tr("تفعيل Shizuku", "Enable Shizuku"))
        tv(tr("Shizuku تطبيق مجاني يعطي «نمط الشبكة» صلاحية تغيير النمط مباشرة، بدون روت وبدون كمبيوتر. تفعّله مرة واحدة.",
            "Shizuku is a free app that lets Network Mode change the mode directly — no root, no computer. Set it up once."), 14f, p.sub)
        spacer(10)

        val inst = Shell.shizukuInstalled(this)
        step(1, tr("ثبّت Shizuku", "Install Shizuku"), tr("من Google Play أو من GitHub.", "From Google Play or GitHub."), inst)
        if (!inst) {
            button(tr("تنزيل من Google Play", "Get it on Google Play")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Shell.SHIZUKU_PKG}"))) }
                catch (_: Exception) { openUrl("https://play.google.com/store/apps/details?id=${Shell.SHIZUKU_PKG}") }
            }
            button(tr("تنزيل من GitHub", "Download from GitHub"), filled = false) { openUrl("https://github.com/RikkaApps/Shizuku/releases/latest") }
        }

        step(2, tr("فعّل خيارات المطوّر", "Enable Developer options"),
            tr("الإعدادات ← حول الهاتف ← معلومات البرنامج ← اضغط على «رقم الإصدار» 7 مرات حتى تظهر رسالة «تم تشغيل وضع المطور».",
                "Settings → About phone → Software information → tap \"Build number\" 7 times until \"Developer mode has been turned on\"."))
        button(tr("فتح «حول الهاتف»", "Open \"About phone\""), filled = false) {
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
        }

        step(3, tr("اتصل بشبكة Wi-Fi", "Connect to Wi-Fi"),
            tr("التصحيح اللاسلكي يحتاج Wi-Fi (أي شبكة، حتى بدون إنترنت).", "Wireless debugging needs Wi-Fi (any network, even without internet)."))

        val running = Shell.shizukuRunning()
        step(4, tr("شغّل Shizuku عبر التصحيح اللاسلكي", "Start Shizuku via Wireless debugging"),
            tr("افتح Shizuku ← «Start via Wireless debugging» ← «Pairing».\n" +
                "سيظهر إشعار من Shizuku. الآن افتح: خيارات المطوّر ← التصحيح اللاسلكي (فعّله) ← «إقران الجهاز برمز إقران».\n" +
                "اكتب الرمز الظاهر داخل إشعار Shizuku.\nارجع إلى Shizuku واضغط «Start».",
                "Open Shizuku → \"Start via Wireless debugging\" → \"Pairing\".\n" +
                "A Shizuku notification appears. Now open: Developer options → Wireless debugging (turn on) → \"Pair device with pairing code\".\n" +
                "Type the code into the Shizuku notification.\nGo back to Shizuku and tap \"Start\"."), running)
        if (!running) {
            button(tr("فتح Shizuku", "Open Shizuku")) {
                val i = packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)
                if (i != null) startActivity(i) else toast(tr("ثبّت Shizuku أولاً", "Install Shizuku first"))
            }
            button(tr("فتح خيارات المطوّر", "Open Developer options"), filled = false) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {}
            }
        }

        val granted = Shell.shizukuGranted()
        step(5, tr("امنح الإذن لـ «نمط الشبكة»", "Grant permission to Network Mode"),
            tr("اضغط الزر ثم «السماح دائماً».", "Tap the button, then \"Allow all the time\"."), granted)
        if (running && !granted) button(tr("منح الإذن", "Grant permission")) {
            try { Shizuku.requestPermission(7) } catch (_: Throwable) { toast(tr("حدّث Shizuku", "Update Shizuku")) }
        }

        spacer(8)
        val n = card(p.warnSoft)
        tv(tr("ملاحظة مهمة", "Important"), 14f, p.warn, true, n)
        tv(tr("بعد إعادة تشغيل الهاتف يتوقف Shizuku. افتحه واضغط «Start» مرة أخرى (مع Wi-Fi)، وسيعيد «نمط الشبكة» تطبيق أنماطك تلقائياً فور تشغيله.",
            "Shizuku stops after a phone reboot. Open it and tap \"Start\" again (on Wi-Fi), and Network Mode will re-apply your modes automatically."),
            13f, p.text, parent = n)
        if (granted) button(tr("تم ✓ — رجوع", "Done ✓ — back")) { finish() }
    }
}

class NewsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        header(tr("الإعلانات", "Announcements"))
        val list = Remote.news()
        if (list.isEmpty()) tv(tr("لا توجد إعلانات.", "No announcements."), 15f, p.sub)
        val read = Prefs.readNews.toMutableSet()
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US)
        for (n in list) {
            val id = n.optString("id")
            val c = card(if (id in read) p.card else p.accentSoft)
            tv(n.optString("title"), 16f, p.text, true, c)
            tv(n.optString("body"), 14f, p.text, parent = c)
            if (n.optLong("ts") > 0) tv(fmt.format(java.util.Date(n.optLong("ts"))), 12f, p.sub, parent = c)
            n.optString("url").takeIf { it.isNotBlank() }?.let { u -> c.setOnClickListener { openUrl(u) } }
            read += id
        }
        Prefs.readNews = read
    }
}

class CompatActivity : BaseActivity() {
    private var report = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        header(tr("فحص التوافق", "Compatibility check"))
        tv(tr("جارٍ الفحص…", "Checking…"), 15f, p.sub)
        bg.execute { val r = run(); main.post { show(r) } }
    }

    /** التقرير نفسه بالإنكليزي دائماً (يُرسل للمطوّر). */
    private fun run(): List<Pair<Boolean?, String>> {
        val r = mutableListOf<Pair<Boolean?, String>>()
        r += null to "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        r += null to "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · ${Build.DISPLAY}"
        r += null to "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        r += (Build.VERSION.SDK_INT >= 31) to "Android 12+ (needed for direct apply)"
        r += Sims.has(this, Manifest.permission.READ_PHONE_STATE) to "Phone permission"
        val sims = Sims.list(this)
        r += sims.isNotEmpty() to "SIMs: " + sims.joinToString(" · ") { "SIM${it.slot + 1} ${it.carrier} ${it.net}" }.ifBlank { "none" }
        r += Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION) to "Location permission (optional)"
        if (Build.VERSION.SDK_INT >= 33) r += Sims.has(this, Manifest.permission.POST_NOTIFICATIONS) to "Notifications permission"
        r += getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) to "Battery exemption"
        r += Shell.shizukuInstalled(this) to "Shizuku installed"
        r += Shell.shizukuRunning() to "Shizuku running" + (try { if (Shell.shizukuRunning()) " (v${Shizuku.getVersion()}, uid ${Shizuku.getUid()})" else "" } catch (_: Throwable) { "" })
        r += Shell.shizukuGranted() to "Shizuku permission"
        r += Prefs.useRoot to "Root (optional)"
        r += null to "Apply method: ${Shell.kind().name}"
        if (Shell.ready()) {
            for (s in 0..1) {
                val raw = Net.rawGet(s)
                val f = Net.parseGet(raw.substringAfter(": "))
                r += (raw.startsWith("0:") && f != null) to "Read mode SIM${s + 1}: ${Mode.byFam(f)?.title ?: f}\n   ↳ ${raw.take(160)}"
            }
        }
        r += Net.radioInfoAvailable(this) to "Phone info menu directly openable"
        r += null to "Samsung: ${Net.isSamsung}"
        val api = Remote.base()
        r += (api != null) to "Server: ${api ?: "not connected yet"}"
        return r
    }

    private fun show(r: List<Pair<Boolean?, String>>) {
        clear()
        header(tr("فحص التوافق", "Compatibility check"))
        val c = card()
        for ((ok, t) in r) {
            val mark = when (ok) { true -> "✓ "; false -> "✗ "; null -> "• " }
            tv(mark + t, 13f, when (ok) { false -> p.warn; true -> p.accent; null -> p.text }, parent = c).apply {
                setPadding(0, dp(3), 0, dp(3)); textDirection = android.view.View.TEXT_DIRECTION_LTR
            }
        }
        val readOk = r.any { it.second.startsWith("Read mode") && it.first == true }
        val verdict = when {
            Shell.ready() && readOk -> tr("✓ جهازك يدعم التطبيق المباشر. اضغط «اختبار فعلي» للتأكد النهائي.",
                "✓ Your phone supports direct apply. Tap \"Real test\" to confirm.")
            Shell.ready() -> tr("⚠ Shizuku يعمل لكن قراءة النمط فشلت. أرسل التقرير للمطوّر.",
                "⚠ Shizuku is running but reading the mode failed. Send the report.")
            else -> tr("الوضع اليدوي فقط حالياً. فعّل Shizuku للتطبيق المباشر.", "Manual mode only for now. Enable Shizuku for direct apply.")
        }
        tv(verdict, 15f, p.text, true).apply { setPadding(dp(4), dp(8), dp(4), dp(10)) }

        report = r.joinToString("\n") { (ok, t) -> (when (ok) { true -> "[OK] "; false -> "[X] "; null -> "" }) + t } +
            "\n\n--- log ---\n" + Log.dump()

        if (Shell.ready()) button(tr("اختبار فعلي (يعيد تطبيق النمط الحالي نفسه)", "Real test (re-applies the current mode)")) { realTest() }
        button(tr("إرسال التقرير للمطوّر", "Send report to developer")) {
            bg.execute {
                val ok = Remote.sendCompat(this, report)
                main.post { toast(if (ok) tr("✓ تم الإرسال، شكراً", "✓ Sent, thanks") else tr("تعذّر الإرسال", "Sending failed")) }
            }
        }
        button(tr("نسخ التقرير", "Copy report"), filled = false) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", report))
            toast(tr("تم النسخ", "Copied"))
        }
    }

    private fun realTest() {
        bg.execute {
            val out = StringBuilder()
            for (s in Sims.list(this).map { it.slot }) {
                val f = Net.current(s)
                val m = Mode.byFam(f) ?: Mode.AUTO
                val r = Net.apply(s, m)
                out.append("SIM${s + 1}: ${m.title} → ").append(if (r.ok) "✓ OK" else "✗ ${r.msg}").append("\n")
            }
            Log.add("realtest: $out")
            main.post {
                AlertDialog.Builder(this).setTitle(tr("نتيجة الاختبار", "Test result"))
                    .setMessage(out.toString().ifBlank { tr("لا توجد شرائح", "No SIMs") })
                    .setPositiveButton(tr("إرسال للمطوّر", "Send to developer")) { _, _ ->
                        bg.execute { Remote.sendCompat(this, report + "\n\n--- real test ---\n" + out) }
                        toast(tr("تم الإرسال", "Sent"))
                    }
                    .setNegativeButton(tr("إغلاق", "Close"), null).show()
            }
        }
    }
}
