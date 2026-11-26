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
import android.widget.LinearLayout
import rikka.shizuku.Shizuku

class SettingsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        clear()
        header("الإعدادات")

        section("الأزرار على الشاشة الرئيسية")
        linkRow("اختر الأزرار الظاهرة", Prefs.visible.joinToString(" · ") { it.title }) { chooseVisible() }

        section("النمط المفضّل لكل شريحة")
        for (s in 0..1) linkRow("شريحة ${s + 1}", Prefs.preferred(s)?.title ?: "غير محدد (يُستخدم آخر نمط)") {
            val all = Mode.values().toList()
            pick("النمط المفضّل — شريحة ${s + 1}", listOf("غير محدد") + all.map { it.title }) { w ->
                Prefs.setPreferred(s, if (w == 0) null else all[w - 1]); render()
            }
        }

        section("LTE فقط")
        linkRow("المدة الافتراضية للمؤقت", timerLabel(Prefs.lteTimerMin)) {
            pick("يرجع للتلقائي بعد", TIMER_CHOICES.map { timerLabel(it) }) { Prefs.lteTimerMin = TIMER_CHOICES[it]; render() }
        }

        section("تلقائي")
        switchRow("إعادة التطبيق بعد تشغيل الهاتف", "يرجع كل شريحة لنمطها بعد إعادة التشغيل (بعد تشغيل Shizuku)", Prefs.reapplyOnBoot) {
            Prefs.reapplyOnBoot = it
        }
        switchRow("تنبيه انقطاع الشبكة", "إشعار إذا بقيت الشريحة بلا شبكة أو بلا مكالمات أكثر من دقيقة", Prefs.dropAlert) {
            Prefs.dropAlert = it; MonitorService.sync(this)
        }
        switchRow("تحويل مؤقت أثناء المكالمة (تجريبي)",
            "عند بدء مكالمة وأنت على LTE فقط يسمح بـ 3G/2G ثم يرجع بعد انتهائها. لا يحل مشكلة المكالمات الواردة.", Prefs.callSwitch) {
            Prefs.callSwitch = it; MonitorService.sync(this)
        }
        switchRow("إشعار دائم بالحالة", "يعرض النمط والإشارة لكل شريحة في شريط الإشعارات", Prefs.persistent) {
            Prefs.persistent = it; MonitorService.sync(this)
        }

        section("الزر السريع في شريط الإشعارات")
        linkRow("الشريحة", "شريحة ${Prefs.tileSlot + 1}") {
            pick("الشريحة", listOf("شريحة 1", "شريحة 2")) { Prefs.tileSlot = it; render() }
        }
        linkRow("النمط الأول", Prefs.tileA.title) {
            pick("النمط الأول", Mode.values().map { it.title }) { Prefs.tileA = Mode.values()[it]; render() }
        }
        linkRow("النمط الثاني", Prefs.tileB.title) {
            pick("النمط الثاني", Mode.values().map { it.title }) { Prefs.tileB = Mode.values()[it]; render() }
        }
        tv("لإضافته: اسحب شريط الإشعارات للأسفل ← زر القلم/التعديل ← اسحب «نمط الشبكة» للأعلى.", 12f, p.sub)
            .apply { setPadding(dp(4), 0, dp(4), dp(8)) }

        section("اختصار على الشاشة الرئيسية")
        linkRow("إضافة اختصار", "اختر الشريحة والنمط") {
            pick("الشريحة", listOf("شريحة 1", "شريحة 2")) { s ->
                pick("النمط", Mode.values().map { it.title }) { w ->
                    if (!Shortcuts.pin(this, s, Mode.values()[w])) toast("الواجهة لا تدعم الاختصارات المثبّتة")
                }
            }
        }

        section("الصلاحيات والبطارية")
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        linkRow("استثناء البطارية", if (ignoring) "✓ مستثنى" else "غير مستثنى — اضغط (ضروري للمؤقت والتنبيهات)") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        if (Build.MANUFACTURER.equals("samsung", true)) {
            linkRow("إعداد سامسونج للخلفية",
                "الإعدادات ← البطارية ← حدود استخدام الخلفية ← «التطبيقات التي لا تدخل في وضع السكون أبداً» ← أضف «نمط الشبكة». اضغط لفتح صفحة التطبيق.") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        val loc = Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION)
        linkRow("إذن الموقع", if (loc) "✓ ممنوح (لعرض الـ Band وحالة المكالمات)" else "اختياري — لعرض الـ Band وحالة المكالمات") {
            if (!loc) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3)
        }

        section("طريقة التطبيق")
        linkRow("Shizuku", when {
            Shell.shizukuGranted() -> "✓ يعمل ومُنح الإذن"
            Shell.shizukuRunning() -> "يعمل — اضغط لمنح الإذن"
            Shell.shizukuInstalled(this) -> "مثبّت لكنه متوقف"
            else -> "غير مثبّت — اضغط للشرح"
        }) {
            if (Shell.shizukuRunning() && !Shell.shizukuGranted()) {
                try { Shizuku.requestPermission(7) } catch (_: Throwable) {}
            } else {
                startActivity(Intent(this, GuideActivity::class.java))
            }
        }
        switchRow("استخدام الروت", "للهواتف المروّتة فقط", Prefs.useRoot) { v ->
            if (!v) { Prefs.useRoot = false; return@switchRow }
            bg.execute {
                val ok = Shell.testRoot()
                main.post { Prefs.useRoot = ok; if (!ok) toast("لا توجد صلاحية روت"); render() }
            }
        }

        section("النسخ الاحتياطي والتحديث")
        linkRow("نسخ الإعدادات للسيرفر", null) {
            bg.execute { val ok = Remote.backup(this); main.post { toast(if (ok) "✓ تم النسخ" else "تعذّر الاتصال") } }
        }
        linkRow("استعادة الإعدادات", null) {
            bg.execute { val ok = Remote.restore(); main.post { toast(if (ok) "✓ تمت الاستعادة" else "لا توجد نسخة"); render() } }
        }
        linkRow("التحقق من التحديثات", "الإصدار الحالي ${BuildConfig.VERSION_NAME}") {
            bg.execute {
                val ok = Remote.refresh(this, force = true)
                main.post {
                    when {
                        !ok -> toast("تعذّر الاتصال بالسيرفر")
                        Remote.hasUpdate() -> AlertDialog.Builder(this).setTitle("يتوفر ${Remote.latestName()}")
                            .setMessage(Remote.whatsNew()).setPositiveButton("تنزيل") { _, _ -> openUrl(Remote.latestUrl()) }
                            .setNegativeButton("لاحقاً", null).show()
                        else -> toast("أنت على آخر إصدار")
                    }
                }
            }
        }
        linkRow("كل الإصدارات", null) { openUrl(Remote.RELEASES) }
    }

    private fun chooseVisible() {
        val all = Mode.values()
        val checked = all.map { it in Prefs.visible }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("الأزرار الظاهرة (حتى 6)")
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, w, v -> checked[w] = v }
            .setPositiveButton("حفظ") { _, _ ->
                val sel = all.filterIndexed { i, _ -> checked[i] }.take(6)
                Prefs.visible = sel.ifEmpty { Mode.DEFAULT_VISIBLE }
                Shortcuts.refresh(this); render()
            }
            .setNeutralButton("الافتراضي") { _, _ -> Prefs.visible = Mode.DEFAULT_VISIBLE; render() }
            .setNegativeButton("إلغاء", null).show()
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
        header("تفعيل Shizuku")
        tv("Shizuku تطبيق مجاني يعطي «نمط الشبكة» صلاحية تغيير النمط مباشرة، بدون روت وبدون كمبيوتر. تفعّله مرة واحدة.", 14f, p.sub)
        spacer(10)

        val inst = Shell.shizukuInstalled(this)
        step(1, "ثبّت Shizuku", "من Google Play أو من GitHub.", inst)
        if (!inst) {
            button("تنزيل من Google Play") {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Shell.SHIZUKU_PKG}"))) }
                catch (_: Exception) { openUrl("https://play.google.com/store/apps/details?id=${Shell.SHIZUKU_PKG}") }
            }
            button("تنزيل من GitHub", filled = false) { openUrl("https://github.com/RikkaApps/Shizuku/releases/latest") }
        }

        step(2, "فعّل خيارات المطوّر",
            "الإعدادات ← حول الهاتف ← معلومات البرنامج ← اضغط على «رقم الإصدار» 7 مرات حتى تظهر رسالة «تم تشغيل وضع المطور».")
        button("فتح «حول الهاتف»", filled = false) {
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
        }

        step(3, "اتصل بشبكة Wi-Fi",
            "التصحيح اللاسلكي يحتاج Wi-Fi (أي شبكة، حتى بدون إنترنت).")

        val running = Shell.shizukuRunning()
        step(4, "شغّل Shizuku عبر التصحيح اللاسلكي",
            "افتح Shizuku ← «Start via Wireless debugging» ← «Pairing».\n" +
            "سيظهر إشعار من Shizuku. الآن افتح: خيارات المطوّر ← التصحيح اللاسلكي (فعّله) ← «إقران الجهاز برمز إقران».\n" +
            "اكتب الرمز الظاهر داخل إشعار Shizuku.\n" +
            "ارجع إلى Shizuku واضغط «Start».", running)
        if (!running) {
            button("فتح Shizuku") {
                val i = packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)
                if (i != null) startActivity(i) else toast("ثبّت Shizuku أولاً")
            }
            button("فتح خيارات المطوّر", filled = false) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {}
            }
        }

        val granted = Shell.shizukuGranted()
        step(5, "امنح الإذن لـ «نمط الشبكة»", "اضغط الزر ثم «السماح دائماً».", granted)
        if (running && !granted) button("منح الإذن") {
            try { Shizuku.requestPermission(7) } catch (_: Throwable) { toast("حدّث Shizuku") }
        }

        spacer(8)
        val n = card(p.warnSoft)
        tv("ملاحظة مهمة", 14f, p.warn, true, n)
        tv("بعد إعادة تشغيل الهاتف يتوقف Shizuku. افتحه واضغط «Start» مرة أخرى (مع Wi-Fi)، " +
            "وسيعيد «نمط الشبكة» تطبيق أنماطك تلقائياً فور تشغيله.", 13f, p.text, parent = n)
        if (granted) button("تم ✓ — رجوع") { finish() }
    }
}

class NewsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        header("الإعلانات")
        val list = Remote.news()
        if (list.isEmpty()) tv("لا توجد إعلانات.", 15f, p.sub)
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
        header("فحص التوافق")
        tv("جارٍ الفحص…", 15f, p.sub)
        bg.execute { val r = run(); main.post { show(r) } }
    }

    private fun run(): List<Pair<Boolean?, String>> {
        val r = mutableListOf<Pair<Boolean?, String>>()
        r += null to "الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        r += null to "أندرويد ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · ${Build.DISPLAY}"
        r += null to "التطبيق ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        r += (Build.VERSION.SDK_INT >= 31) to "أندرويد 12 أو أحدث (مطلوب للتطبيق المباشر)"
        val phone = Sims.has(this, Manifest.permission.READ_PHONE_STATE)
        r += phone to "إذن الهاتف"
        val sims = Sims.list(this)
        r += sims.isNotEmpty() to "الشرائح: " + sims.joinToString(" · ") { "ش${it.slot + 1} ${it.carrier} ${it.net}" }.ifBlank { "لا شيء" }
        r += Sims.has(this, Manifest.permission.ACCESS_FINE_LOCATION) to "إذن الموقع (اختياري)"
        if (Build.VERSION.SDK_INT >= 33) r += Sims.has(this, Manifest.permission.POST_NOTIFICATIONS) to "إذن الإشعارات"
        r += getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) to "استثناء البطارية"
        r += Shell.shizukuInstalled(this) to "Shizuku مثبّت"
        r += Shell.shizukuRunning() to "Shizuku يعمل" + (try { if (Shell.shizukuRunning()) " (v${Shizuku.getVersion()}, uid ${Shizuku.getUid()})" else "" } catch (_: Throwable) { "" })
        r += Shell.shizukuGranted() to "إذن Shizuku"
        r += Prefs.useRoot to "الروت (اختياري)"
        r += null to "طريقة التطبيق: ${Shell.kindLabel()}"
        if (Shell.ready()) {
            for (s in 0..1) {
                val raw = Net.rawGet(s)
                val f = Net.parseGet(raw.substringAfter(": "))
                r += (raw.startsWith("0:") && f != null) to "قراءة النمط ش${s + 1}: ${famLabel(f)}\n   ↳ ${raw.take(160)}"
            }
        }
        r += Net.radioInfoAvailable(this) to "قائمة Phone info (الطريقة اليدوية)"
        val api = Remote.base()
        r += (api != null) to "السيرفر: ${api ?: "غير متصل بعد"}"
        return r
    }

    private fun show(r: List<Pair<Boolean?, String>>) {
        clear()
        header("فحص التوافق")
        val c = card()
        for ((ok, t) in r) {
            val mark = when (ok) { true -> "✓ "; false -> "✗ "; null -> "• " }
            tv(mark + t, 13f, when (ok) { false -> p.warn; true -> p.accent; null -> p.text }, parent = c).apply { setPadding(0, dp(3), 0, dp(3)) }
        }
        val verdict = when {
            Shell.ready() && r.any { it.second.startsWith("قراءة النمط") && it.first == true } ->
                "✓ جهازك يدعم التطبيق المباشر. اضغط «اختبار فعلي» للتأكد النهائي."
            Shell.ready() -> "⚠ Shizuku يعمل لكن قراءة النمط فشلت. أرسل التقرير للمطوّر."
            else -> "الوضع اليدوي فقط حالياً. فعّل Shizuku للتطبيق المباشر."
        }
        tv(verdict, 15f, p.text, true).apply { setPadding(dp(4), dp(8), dp(4), dp(10)) }

        report = r.joinToString("\n") { (ok, t) -> (when (ok) { true -> "[OK] "; false -> "[X] "; null -> "" }) + t } +
            "\n\n--- log ---\n" + Log.dump()

        if (Shell.ready()) button("اختبار فعلي (يعيد تطبيق النمط الحالي نفسه)") { realTest() }
        button("إرسال التقرير للمطوّر") {
            bg.execute { val ok = Remote.sendCompat(this, report); main.post { toast(if (ok) "✓ تم الإرسال، شكراً" else "تعذّر الإرسال") } }
        }
        button("نسخ التقرير", filled = false) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", report))
            toast("تم النسخ")
        }
    }

    private fun realTest() {
        bg.execute {
            val out = StringBuilder()
            for (s in Sims.list(this).map { it.slot }) {
                val f = Net.current(s)
                val m = Mode.byFam(f) ?: Mode.AUTO
                val r = Net.apply(s, m)
                out.append("ش${s + 1}: ${m.title} → ").append(if (r.ok) "✓ نجح" else "✗ ${r.msg}").append("\n")
            }
            Log.add("realtest: $out")
            main.post {
                AlertDialog.Builder(this).setTitle("نتيجة الاختبار").setMessage(out.toString().ifBlank { "لا توجد شرائح" })
                    .setPositiveButton("إرسال للمطوّر") { _, _ ->
                        bg.execute { Remote.sendCompat(this, report + "\n\n--- real test ---\n" + out) }
                        toast("تم الإرسال")
                    }
                    .setNegativeButton("إغلاق", null).show()
            }
        }
    }
}
