package com.makanona.netmode

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ===================== التحديث من داخل التطبيق =====================
object Updater {
    /** يُنزّل ملف التحديث ويتحقق أنه أحدث ومن نفس التطبيق. يُستدعى من خيط خلفي. */
    fun download(c: Context, onProgress: (Int) -> Unit): File? {
        val f = File(c.cacheDir, "update.apk")
        return try {
            f.delete()
            var url = URL(Remote.latestUrl())
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "network-mode/${BuildConfig.VERSION_NAME}")
                val code = conn.responseCode
                if (code in 300..399 && hops < 6) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); hops++; continue
                }
                if (code !in 200..299) { Log.add("update http $code"); return null }
                break
            }
            val total = conn.contentLengthLong
            var done = 0L; var last = -1
            conn.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
            conn.disconnect()
            @Suppress("DEPRECATION")
            val info = c.packageManager.getPackageArchiveInfo(f.path, 0)
            val vc = if (info == null) -1L else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            if (info?.packageName != c.packageName || vc <= BuildConfig.VERSION_CODE) {
                Log.add("update bad apk pkg=${info?.packageName} vc=$vc"); f.delete(); return null
            }
            f
        } catch (e: Exception) { Log.add("update dl ${e.message}"); null }
    }

    /** تثبيت صامت عبر Shizuku/root. يُغلق التطبيق عند النجاح. */
    fun installSilent(c: Context, f: File): Res {
        val (code, out) = Shell.execInput(
            "pm install -r -S ${f.length()} && am start -n ${c.packageName}/.MainActivity", f, 180)
        Log.add("silent install $code $out")
        return Res(out.contains("Success"), out)
    }

    /** تثبيت عبر نافذة أندرويد العادية. */
    fun installSystem(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { os -> f.inputStream().use { it.copyTo(os) }; s.fsync(os) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags)
            s.commit(pend.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                Log.add("install fail $msg")
                Toast.makeText(c, tr("فشل التثبيت: ", "Install failed: ") + msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** واجهة التحديث (تُستدعى من أي شاشة). */
fun BaseActivity.startUpdate() {
    val silent = Shell.ready() && Remote.on("silent_install")
    if (!Remote.on("in_app_update")) { openUrl(Remote.latestUrl()); return }
    if (!silent && !packageManager.canRequestPackageInstalls()) {
        AlertDialog.Builder(this)
            .setTitle(tr("السماح بالتحديث", "Allow updates"))
            .setMessage(tr("اسمح لـ «نمط الشبكة» بتثبيت التحديثات مرة واحدة، ثم ارجع واضغط تحديث.",
                "Allow Network Mode to install updates once, then come back and tap Update."))
            .setPositiveButton(tr("فتح الإعداد", "Open setting")) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { openUrl(Remote.latestUrl()) }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null).show()
        return
    }
    val dlg = AlertDialog.Builder(this)
        .setTitle(tr("تحديث إلى ", "Updating to ") + Remote.latestName())
        .setMessage(tr("جارٍ التنزيل… 0%", "Downloading… 0%"))
        .setCancelable(false).show()
    val app = applicationContext
    bg.execute {
        val f = Updater.download(app) { p -> main.post { dlg.setMessage(tr("جارٍ التنزيل… ", "Downloading… ") + "$p%") } }
        if (f == null) {
            main.post {
                try { dlg.dismiss() } catch (_: Exception) {}
                AlertDialog.Builder(this).setMessage(tr("تعذّر تنزيل التحديث.", "Could not download the update."))
                    .setPositiveButton(tr("التنزيل من المتصفح", "Download in browser")) { _, _ -> openUrl(Remote.latestUrl()) }
                    .setNegativeButton(tr("إغلاق", "Close"), null).show()
            }
            return@execute
        }
        if (silent) {
            main.post { dlg.setMessage(tr("جارٍ التثبيت… سيُغلق التطبيق ثم يعود.", "Installing… the app will close and reopen.")) }
            if (Updater.installSilent(app, f).ok) return@execute
        }
        main.post { try { dlg.dismiss() } catch (_: Exception) {} }
        try { Updater.installSystem(app, f) } catch (e: Exception) {
            main.post { toast(tr("فشل التثبيت: ", "Install failed: ") + e.message) }
        }
    }
}

// ===================== حماية LTE Only =====================
object Guard {
    fun arm(c: Context, slot: Int) {
        if (!Prefs.lteGuard || !Remote.on("lte_guard")) return
        val app = c.applicationContext
        main.postDelayed({ bg.execute { check(app, slot, 1) } }, 30_000)
    }

    private fun check(c: Context, slot: Int, n: Int) {
        if (Prefs.last(slot) != Mode.LTE_ONLY || !Shell.ready()) return
        val s = Sims.forSlot(c, slot) ?: return
        if (s.net.startsWith("4G") || s.net.startsWith("5G")) return
        if (n == 1) { main.postDelayed({ bg.execute { check(c, slot, 2) } }, 20_000); return }
        if (Net.applyAndRecord(c, slot, Mode.AUTO).ok) {
            Drops.add(slot, "no4g", s.net)
            Notif.post(c, Notif.ALERT, 500 + slot, tr("لا تغطية 4G هنا", "No 4G coverage here"),
                tr("SIM ${slot + 1} لم تجد 4G خلال 50 ثانية، فرجعت إلى Auto حتى لا تبقى بلا شبكة.",
                    "SIM ${slot + 1} found no 4G within 50 seconds, so it went back to Auto."),
                Intent(c, MainActivity::class.java))
        }
    }
}

// ===================== سجل الانقطاعات + التنبيه =====================
object Drops {
    @Synchronized fun list(): JSONArray = try { JSONArray(Prefs.dropLog) } catch (_: Exception) { JSONArray() }

    @Synchronized fun add(slot: Int, type: String, net: String) {
        val a = list()
        a.put(JSONObject().put("ts", System.currentTimeMillis()).put("slot", slot).put("type", type).put("net", net))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - 300) until a.length()) out.put(a.get(i))
        Prefs.dropLog = out.toString()
    }

    fun clear() { Prefs.dropLog = "[]" }

    fun label(type: String) = when (type) {
        "none" -> tr("انقطعت الشبكة", "Lost service")
        "nocalls" -> tr("المكالمات غير متاحة", "Calls unavailable")
        "ok" -> tr("عادت الشبكة", "Service back")
        "no4g" -> tr("لا 4G — رجوع إلى Auto", "No 4G — back to Auto")
        else -> type
    }

    private fun inCall(c: Context): Boolean = try {
        @Suppress("DEPRECATION")
        c.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (_: Throwable) { false }

    /** يقارن الحالة الحالية بالسابقة: يسجّل التغيّرات وينبّه عند استمرار المشكلة. */
    fun observe(c: Context, sims: List<SimInfo>) {
        val now = System.currentTimeMillis()
        val call = inCall(c)
        for (s in sims) {
            val state = when { !s.voiceOk && !s.dataOk -> "none"; !s.voiceOk -> "nocalls"; else -> "ok" }
            val prev = Prefs.simState(s.slot)
            if (state != prev && !call) {
                Prefs.setSimState(s.slot, state, now)
                if (state != "ok") add(s.slot, state, s.net) else if (prev.isNotEmpty()) add(s.slot, "ok", s.net)
            }
            if (state == "ok") {
                if (Prefs.alerted(s.slot)) { Notif.cancel(c, 200 + s.slot); Prefs.setAlerted(s.slot, false) }
                continue
            }
            if (!Prefs.dropAlert || !Remote.on("drop_alert") || call || Prefs.alerted(s.slot)) continue
            if (now - Prefs.simSince(s.slot) < 60_000) continue
            Prefs.setAlerted(s.slot, true)
            val noCalls = state == "nocalls"
            Notif.post(c, Notif.ALERT, 200 + s.slot,
                if (noCalls) tr("SIM ${s.slot + 1}: المكالمات لا تصلك", "SIM ${s.slot + 1}: calls can't reach you")
                else tr("SIM ${s.slot + 1}: خارج التغطية", "SIM ${s.slot + 1}: no service"),
                if (noCalls) tr("الإنترنت يعمل لكن لا يمكن استقبال المكالمات على النمط الحالي.", "Data works but calls can't be received on this mode.")
                else tr("لا توجد شبكة منذ مدة.", "No network for a while."),
                Intent(c, MainActivity::class.java),
                Notif.applyAction(c, s.slot, Mode.LTE_CALLS, 10 + s.slot),
                Notif.applyAction(c, s.slot, Mode.AUTO, 20 + s.slot))
        }
    }
}

/** فحص دوري كل ~15 دقيقة بدون إشعار دائم. */
class CheckJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Prefs.init(this)
        bg.execute {
            try {
                Drops.observe(this, Sims.list(this))
                NetWidget.update(this)
                Remote.refresh(this)
            } catch (_: Throwable) {} finally { jobFinished(p, false) }
        }
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    companion object {
        fun schedule(c: Context) {
            try {
                val js = c.getSystemService(JobScheduler::class.java)
                if (js.getPendingJob(77) != null) return
                js.schedule(JobInfo.Builder(77, ComponentName(c, CheckJob::class.java))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build())
            } catch (_: Exception) {}
        }
    }
}

// ===================== الويدجت =====================
class NetWidget : AppWidgetProvider() {
    companion object {
        const val TOGGLE = "com.makanona.netmode.W_TOGGLE"
        private val BTN = intArrayOf(R.id.b0, R.id.b1, R.id.b2, R.id.b3)

        fun update(c: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(c)
                val ids = mgr.getAppWidgetIds(ComponentName(c, NetWidget::class.java))
                if (ids.isEmpty()) return
                val slot = Prefs.slot
                val s = Sims.forSlot(c, slot)
                val rv = RemoteViews(c.packageName, R.layout.widget)
                rv.setTextViewText(R.id.w_title, "SIM ${slot + 1} · " + (s?.carrier ?: "—") + "  ⇄")
                rv.setTextViewText(R.id.w_sub, if (s == null) "" else s.net + (s.dbm?.let { " · $it dBm" } ?: ""))
                rv.setOnClickPendingIntent(R.id.w_title, PendingIntent.getBroadcast(c, 900,
                    Intent(c, NetWidget::class.java).setAction(TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                rv.setOnClickPendingIntent(R.id.w_sub, PendingIntent.getActivity(c, 901,
                    Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                val modes = Prefs.visible.take(4)
                for (i in BTN.indices) {
                    val id = BTN[i]
                    if (i >= modes.size) { rv.setViewVisibility(id, View.GONE); continue }
                    val m = modes[i]
                    val on = Prefs.last(slot) == m
                    rv.setViewVisibility(id, View.VISIBLE)
                    rv.setTextViewText(id, m.title.substringBefore(" ("))
                    rv.setInt(id, "setBackgroundResource", if (on) R.drawable.w_btn_on else R.drawable.w_btn)
                    rv.setTextColor(id, if (on) Color.WHITE else 0xFF3FC1A5.toInt())
                    rv.setOnClickPendingIntent(id, PendingIntent.getActivity(c, 1000 + slot * 10 + i,
                        Shortcuts.intent(c, slot, m), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                mgr.updateAppWidget(ids, rv)
            } catch (e: Exception) { Log.add("widget ${e.message}") }
        }
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Prefs.init(c); bg.execute { update(c) }
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == TOGGLE) {
            Prefs.init(c)
            bg.execute {
                val slots = Sims.list(c).map { it.slot }
                val next = slots.firstOrNull { it != Prefs.slot } ?: Prefs.slot
                Prefs.slot = next; update(c)
            }
        }
    }
}

// ===================== شاشة سجل الانقطاعات =====================
class DropsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }

    private fun render() {
        clear()
        header(tr("سجل الانقطاعات", "Signal log"))
        val a = Drops.list()
        val week = System.currentTimeMillis() - 7 * 86400_000L
        val c = card(p.accentSoft)
        for (s in 0..1) {
            var drops = 0; var noCalls = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optInt("slot") != s || o.optLong("ts") < week) continue
                when (o.optString("type")) { "none" -> drops++; "nocalls" -> noCalls++ }
            }
            tv("SIM ${s + 1}: " + tr("$drops انقطاع · $noCalls بلا مكالمات (آخر 7 أيام)",
                "$drops drops · $noCalls no-calls (last 7 days)"), 14f, p.accent, true, c)
        }
        tv(tr("يُسجَّل تلقائياً كل 15 دقيقة تقريباً، وكل 30 ثانية إذا كان الإشعار الدائم مفعّلاً.",
            "Recorded about every 15 minutes, or every 30 seconds with the persistent notification on."), 12f, p.sub)
        spacer(8)
        if (a.length() == 0) tv(tr("لا توجد أحداث بعد.", "No events yet."), 15f, p.sub)
        val fmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
        val box = card()
        for (i in a.length() - 1 downTo maxOf(0, a.length() - 150)) {
            val o = a.getJSONObject(i)
            val t = o.optString("type")
            tv("${fmt.format(java.util.Date(o.optLong("ts")))}  ·  SIM ${o.optInt("slot") + 1}  ·  ${Drops.label(t)}  ·  ${o.optString("net")}",
                13f, if (t == "ok") p.accent else p.warn, parent = box).apply { setPadding(0, dp(3), 0, dp(3)) }
        }
        if (a.length() > 0) button(tr("مسح السجل", "Clear log"), filled = false) { Drops.clear(); render() }
    }
}
