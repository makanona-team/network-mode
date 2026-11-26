package com.makanona.netmode

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Remote.load()
        Notif.channels(this)
        Remote.installCrashHandler()
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("nm", Context.MODE_PRIVATE) }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var slot: Int get() = sp.getInt("slot", 0); set(v) = sp.edit().putInt("slot", v).apply()

    var visible: List<Mode>
        get() {
            val s = sp.getString("visible", null) ?: return Mode.DEFAULT_VISIBLE
            return s.split(",").mapNotNull { Mode.of(it) }.ifEmpty { Mode.DEFAULT_VISIBLE }
        }
        set(v) = sp.edit().putString("visible", v.joinToString(",") { it.name }).apply()

    fun preferred(slot: Int): Mode? = Mode.of(sp.getString("pref_$slot", null))
    fun setPreferred(slot: Int, m: Mode?) = sp.edit().putString("pref_$slot", m?.name).apply()
    fun last(slot: Int): Mode? = Mode.of(sp.getString("last_$slot", null))
    fun setLast(slot: Int, m: String) = sp.edit().putString("last_$slot", m).apply()

    var reapplyOnBoot get() = b("reboot", true); set(v) = pb("reboot", v)
    var dropAlert get() = b("drop", true); set(v) = pb("drop", v)
    var callSwitch get() = b("call", false); set(v) = pb("call", v)
    var persistent get() = b("persist", false); set(v) = pb("persist", v)
    var useRoot get() = b("root", false); set(v) = pb("root", v)
    var pendingReapply get() = b("pending", false); set(v) = pb("pending", v)
    var firstRunDone get() = b("first", false); set(v) = pb("first", v)

    var lteTimerMin: Int get() = sp.getInt("ltetimer", 60); set(v) = sp.edit().putInt("ltetimer", v).apply()
    fun timerEnd(slot: Int) = sp.getLong("tend_$slot", 0L)
    fun setTimerEnd(slot: Int, t: Long) = sp.edit().putLong("tend_$slot", t).apply()
    fun timed(slot: Int) = b("timed_$slot", false)
    fun setTimed(slot: Int, v: Boolean) = pb("timed_$slot", v)
    fun callRevert(slot: Int) = b("crev_$slot", false)
    fun setCallRevert(slot: Int, v: Boolean) = pb("crev_$slot", v)

    var tileA: Mode get() = Mode.of(sp.getString("tileA", null)) ?: Mode.AUTO; set(v) = sp.edit().putString("tileA", v.name).apply()
    var tileB: Mode get() = Mode.of(sp.getString("tileB", null)) ?: Mode.LTE_ONLY; set(v) = sp.edit().putString("tileB", v.name).apply()
    var tileSlot: Int get() = sp.getInt("tileSlot", 0); set(v) = sp.edit().putInt("tileSlot", v).apply()

    val deviceId: String
        get() = sp.getString("did", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("did", it).apply() }

    var apiBase: String? get() = sp.getString("api", null); set(v) = sp.edit().putString("api", v).apply()
    var configJson: String? get() = sp.getString("cfg", null); set(v) = sp.edit().putString("cfg", v).apply()
    var lastPing: Long get() = sp.getLong("ping", 0); set(v) = sp.edit().putLong("ping", v).apply()
    var lastFetch: Long get() = sp.getLong("fetch", 0); set(v) = sp.edit().putLong("fetch", v).apply()
    var pendingCrash: String? get() = sp.getString("crash", null); set(v) = sp.edit().putString("crash", v).commit().let { }
    var readNews: Set<String> get() = sp.getStringSet("read", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("read", v).apply()
    var notifiedNews: Set<String> get() = sp.getStringSet("notified", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("notified", v).apply()

    /** نسخة الإعدادات للنسخ الاحتياطي. */
    fun export(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) if (k !in setOf("did", "api", "cfg", "crash", "ping", "fetch", "read", "notified")) o.put(k, v)
        return o
    }

    fun import(o: JSONObject) {
        val e = sp.edit()
        for (k in o.keys()) when (val v = o.get(k)) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }
}

/** مؤقت LTE فقط: يرجع الشريحة إلى التلقائي الشامل. */
object Timers {
    private fun pi(c: Context, slot: Int) = PendingIntent.getBroadcast(
        c, 100 + slot,
        Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.TIMER).putExtra("slot", slot),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, slot: Int, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + minutes * 60_000L
        Prefs.setTimerEnd(slot, at)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + minutes * 60_000L, pi(c, slot))
    }

    fun cancel(c: Context, slot: Int) {
        Prefs.setTimerEnd(slot, 0)
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, slot))
    }

    /** بعد إعادة التشغيل: إعادة جدولة المؤقتات المتبقية. */
    fun restore(c: Context) {
        for (s in 0..1) {
            val end = Prefs.timerEnd(s)
            if (end <= 0) continue
            val left = ((end - System.currentTimeMillis()) / 60_000L).toInt()
            if (left <= 0) {
                c.sendBroadcast(Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.TIMER).putExtra("slot", s))
            } else {
                val am = c.getSystemService(AlarmManager::class.java)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + left * 60_000L, pi(c, s))
            }
        }
    }
}

/** اختصارات الأيقونة (ضغطة مطوّلة على أيقونة التطبيق) + اختصارات مثبّتة. */
object Shortcuts {
    fun intent(c: Context, slot: Int, m: Mode) = Intent(c, ShortcutActivity::class.java)
        .setAction("com.makanona.netmode.APPLY")
        .putExtra("slot", slot).putExtra("mode", m.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun info(c: Context, slot: Int, m: Mode): ShortcutInfo =
        ShortcutInfo.Builder(c, "m_${slot}_${m.name}")
            .setShortLabel("${m.title} · ش${slot + 1}")
            .setLongLabel("${m.title} — شريحة ${slot + 1}")
            .setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
            .setIntent(intent(c, slot, m))
            .build()

    fun refresh(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return
            val slot = Prefs.slot
            val list = Prefs.visible.take(4).map { info(c, slot, it) }
            sm.dynamicShortcuts = list
        } catch (_: Exception) {}
    }

    fun pin(c: Context, slot: Int, m: Mode): Boolean = try {
        val sm = c.getSystemService(ShortcutManager::class.java)
        sm.isRequestPinShortcutSupported && sm.requestPinShortcut(info(c, slot, m), null)
    } catch (_: Exception) { false }
}

data class SimInfo(
    val slot: Int,
    val subId: Int,
    val carrier: String,
    val net: String,
    val dbm: Int?,
    val level: Int,
    val band: String?,
    val voiceOk: Boolean,
    val voice: String,
    val dataOk: Boolean
)

object Sims {
    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun list(c: Context): List<SimInfo> {
        if (!has(c, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = c.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val subs = try { sm.activeSubscriptionInfoList } catch (e: SecurityException) { null } ?: return emptyList()
        return subs.sortedBy { it.simSlotIndex }.map { s ->
            val tm = c.getSystemService(TelephonyManager::class.java).createForSubscriptionId(s.subscriptionId)
            read(c, tm, s.simSlotIndex, s.subscriptionId, s.carrierName?.toString().orEmpty().ifBlank { tm.networkOperatorName ?: "" })
        }
    }

    fun forSlot(c: Context, slot: Int) = list(c).firstOrNull { it.slot == slot }

    private fun gen(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, 19 -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi"
        else -> ""
    }

    private fun read(c: Context, tm: TelephonyManager, slot: Int, subId: Int, carrier: String): SimInfo {
        val ss: ServiceState? = try { tm.serviceState } catch (_: Throwable) { null }
        val dataT = try { tm.dataNetworkType } catch (_: Throwable) { 0 }
        val voiceT = try { tm.voiceNetworkType } catch (_: Throwable) { 0 }
        var net = gen(dataT).ifBlank { gen(voiceT) }
        if (net == "4G" && ss?.toString()?.contains("nrState=CONNECTED") == true) net = "5G (NSA)"
        val voiceOk = ss?.let { it.state == ServiceState.STATE_IN_SERVICE } ?: true
        val dataOk = dataT != TelephonyManager.NETWORK_TYPE_UNKNOWN
        var dbm: Int? = null
        var level = 0
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val sig = tm.signalStrength
                if (sig != null) {
                    level = sig.level
                    dbm = sig.cellSignalStrengths.firstOrNull()?.dbm?.takeIf { it in -150..-20 }
                }
            } catch (_: Throwable) {}
        }
        var band: String? = null
        if (Build.VERSION.SDK_INT >= 30 && has(c, Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                for (ci in tm.allCellInfo ?: emptyList()) {
                    if (!ci.isRegistered) continue
                    band = when (ci) {
                        is CellInfoLte -> ci.cellIdentity.bands.takeIf { it.isNotEmpty() }?.joinToString(",") { "B$it" }
                        is CellInfoNr -> (ci.cellIdentity as? CellIdentityNr)?.bands?.takeIf { it.isNotEmpty() }?.joinToString(",") { "n$it" }
                        else -> null
                    }
                    if (band != null) break
                }
            } catch (_: Throwable) {}
        }
        val voice = when {
            ss == null -> "غير معروفة (امنح إذن الموقع)"
            !voiceOk && dataOk -> "غير متاحة ⚠ (لا VoLTE على هذا النمط)"
            !voiceOk -> "غير متاحة"
            voiceT == TelephonyManager.NETWORK_TYPE_LTE || voiceT == TelephonyManager.NETWORK_TYPE_NR ||
                voiceT == TelephonyManager.NETWORK_TYPE_IWLAN -> "تعمل عبر 4G (VoLTE)"
            else -> "تعمل" + (gen(voiceT).takeIf { it.isNotBlank() }?.let { " عبر $it" } ?: "")
        }
        return SimInfo(slot, subId, carrier, if (net.isBlank()) "لا شبكة" else net, dbm, level, band, voiceOk, voice, dataOk)
    }
}
