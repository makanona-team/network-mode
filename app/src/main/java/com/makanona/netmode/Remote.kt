package com.makanona.netmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** طبقة التحكم عن بُعد: إجبار التحديث، الإعلانات، مفاتيح الإيقاف، الإحصائيات، الأعطال. */
object Remote {
    const val BOOTSTRAP = "https://raw.githubusercontent.com/makanona-team/network-mode/main/bootstrap.json"
    const val STABLE_APK = "https://github.com/makanona-team/network-mode/releases/latest/download/network-mode.apk"
    const val RELEASES = "https://github.com/makanona-team/network-mode/releases"

    @Volatile private var cfg = JSONObject()

    fun load() { cfg = try { JSONObject(Prefs.configJson ?: "{}") } catch (_: Exception) { JSONObject() } }

    fun on(key: String): Boolean = cfg.optJSONObject("switches")?.optBoolean(key, true) ?: true
    fun minVersion() = cfg.optInt("minVersion", 0)
    fun latestVersion() = cfg.optInt("latestVersion", 0)
    fun latestName() = cfg.optString("latestName", "")
    fun latestUrl(): String = cfg.optString("latestUrl", "").ifBlank { STABLE_APK }
    fun whatsNew() = cfg.optString("whatsNew", "")
    fun mustUpdate() = BuildConfig.VERSION_CODE < minVersion()
    fun hasUpdate() = BuildConfig.VERSION_CODE < latestVersion()

    fun news(): List<JSONObject> {
        val a = cfg.optJSONArray("announcements") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedByDescending { it.optLong("ts") }
    }

    fun unread(): Int { val r = Prefs.readNews; return news().count { it.optString("id") !in r } }

    private fun http(url: String, body: JSONObject? = null): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "network-mode/${BuildConfig.VERSION_NAME}")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val t = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText()
        c.disconnect()
        if (code in 200..299) t else null
    } catch (_: Exception) { null }

    private fun api(): String? {
        val b = http(BOOTSTRAP + "?t=" + System.currentTimeMillis() / 600_000)
        val u = try { b?.let { JSONObject(it).optString("api") } } catch (_: Exception) { null }
        if (!u.isNullOrBlank()) Prefs.apiBase = u.trimEnd('/')
        return Prefs.apiBase
    }

    fun base(): String? = Prefs.apiBase

    /** يُستدعى من خيط خلفي. يعيد true إذا نجح الجلب. */
    fun refresh(c: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.lastFetch < 3 * 3600_000L) return false
        val a = api() ?: return false
        val t = http("$a/config?vc=${BuildConfig.VERSION_CODE}") ?: return false
        try { JSONObject(t) } catch (_: Exception) { return false }
        Prefs.configJson = t; load(); Prefs.lastFetch = now
        notifyNews(c)
        if (now - Prefs.lastPing > 20 * 3600_000L && on("telemetry")) ping(c)
        Prefs.pendingCrash?.let { cr ->
            if (http("$a/crash", JSONObject().put("id", Prefs.deviceId).put("vc", BuildConfig.VERSION_CODE)
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL).put("trace", cr)) != null) Prefs.pendingCrash = null
        }
        return true
    }

    private fun device(c: Context) = JSONObject()
        .put("id", Prefs.deviceId)
        .put("vc", BuildConfig.VERSION_CODE).put("vn", BuildConfig.VERSION_NAME)
        .put("model", Build.MANUFACTURER + " " + Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT).put("android", Build.VERSION.RELEASE)
        .put("method", Shell.kind().name)
        .put("sims", Sims.list(c).size)

    fun ping(c: Context) {
        val a = base() ?: return
        if (http("$a/ping", device(c)) != null) Prefs.lastPing = System.currentTimeMillis()
    }

    fun sendCompat(c: Context, report: String): Boolean {
        val a = base() ?: api() ?: return false
        return http("$a/compat", device(c).put("report", report)) != null
    }

    fun backup(c: Context): Boolean {
        val a = base() ?: api() ?: return false
        return http("$a/backup", JSONObject().put("id", Prefs.deviceId).put("prefs", Prefs.export())) != null
    }

    fun restore(): Boolean {
        val a = base() ?: api() ?: return false
        val t = http("$a/backup?id=${Prefs.deviceId}") ?: return false
        return try { Prefs.import(JSONObject(t).getJSONObject("prefs")); true } catch (_: Exception) { false }
    }

    private fun notifyNews(c: Context) {
        val done = Prefs.notifiedNews.toMutableSet()
        val read = Prefs.readNews
        for (n in news()) {
            val id = n.optString("id")
            if (id.isBlank() || id in done || id in read) continue
            Notif.post(c, Notif.NEWS, 5000 + (id.hashCode() and 0xfff), n.optString("title"), n.optString("body"),
                Intent(c, NewsActivity::class.java))
            done += id
        }
        Prefs.notifiedNews = done
    }

    fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                Prefs.pendingCrash = "v${BuildConfig.VERSION_NAME} ${Build.MODEL} sdk${Build.VERSION.SDK_INT}\n" + sw.toString().take(6000)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}

object Notif {
    const val STATUS = "status"
    const val ALERT = "alert"
    const val NEWS = "news"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS, "حالة الشبكة", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT, "تنبيهات الشبكة", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(NEWS, "إعلانات التطبيق", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun open(c: Context, i: Intent, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(c, req, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun applyAction(c: Context, slot: Int, m: Mode, req: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(c, req,
            Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.APPLY)
                .putExtra("slot", slot).putExtra("mode", m.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, m.title, pi).build()
    }

    fun build(c: Context, ch: String, title: String, text: String, tap: Intent?): Notification.Builder =
        Notification.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(open(c, tap)) }

    fun post(c: Context, ch: String, id: Int, title: String, text: String, tap: Intent?, vararg actions: Notification.Action) {
        try {
            val b = build(c, ch, title, text, tap)
            actions.forEach { b.addAction(it) }
            c.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (_: Exception) {}
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)
}
