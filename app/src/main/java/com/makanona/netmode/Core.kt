package com.makanona.netmode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

const val G2 = 1
const val G3 = 2
const val G4 = 4
const val G5 = 8

/** أنماط الشبكة. fam = العائلات المسموحة (2G/3G/4G/5G). */
enum class Mode(
    val title: String,
    val sub: String,
    val fam: Int,
    val radioInfo: String,
    val warn: String? = null
) {
    AUTO("تلقائي شامل", "5G / 4G / 3G / 2G — الوضع الطبيعي", G2 or G3 or G4 or G5, "NR/LTE/GSM/WCDMA"),
    LTE_CALLS("4G + مكالمات", "إنترنت 4G، والمكالمات تنزل لـ 3G/2G وترجع", G2 or G3 or G4, "LTE/GSM/WCDMA"),
    LTE_3G("4G / 3G", "بدون 2G", G3 or G4, "LTE/WCDMA"),
    LTE_ONLY("LTE فقط", "4G فقط بدون نزول", G4, "LTE only",
        "المكالمات والرسائل قد لا تصلك إذا كان VoLTE غير مفعّل"),
    NR_LTE("5G / 4G", "يحتاج هاتفاً ومشغّلاً يدعمان 5G", G4 or G5, "NR/LTE"),
    NR_ONLY("5G فقط", "فقط حيث تتوفر 5G", G5, "NR only", "إذا لم تتوفر 5G ستبقى بلا شبكة"),
    WCDMA_GSM("3G / 2G", "بدون 4G", G2 or G3, "GSM/WCDMA auto"),
    WCDMA_ONLY("3G فقط", "", G3, "WCDMA only"),
    GSM_ONLY("2G فقط (GSM)", "مكالمات ثابتة، إنترنت بطيء جداً", G2, "GSM only",
        "الإنترنت على 2G بطيء جداً");

    val mask: Long get() = Net.famToMask(fam)

    companion object {
        val DEFAULT_VISIBLE = listOf(AUTO, LTE_CALLS, LTE_3G, LTE_ONLY)
        fun of(n: String?): Mode? = values().firstOrNull { it.name == n }
        fun byFam(f: Int?): Mode? = if (f == null) null else values().firstOrNull { it.fam == f }
    }
}

fun famLabel(f: Int?): String {
    if (f == null) return "غير معروف"
    Mode.byFam(f)?.let { return it.title }
    val p = mutableListOf<String>()
    if (f and G5 != 0) p += "5G"; if (f and G4 != 0) p += "4G"
    if (f and G3 != 0) p += "3G"; if (f and G2 != 0) p += "2G"
    return if (p.isEmpty()) "غير معروف" else p.joinToString(" / ")
}

/** تشغيل أوامر بصلاحية shell عبر Shizuku أو root. */
object Shell {
    enum class Kind { SHIZUKU, ROOT, NONE }

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    fun shizukuInstalled(c: Context) = try {
        c.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: Exception) { false }

    fun shizukuRunning() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuGranted() = try {
        shizukuRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun kind(): Kind = when {
        Remote.on("shizuku_apply") && shizukuGranted() -> Kind.SHIZUKU
        Prefs.useRoot -> Kind.ROOT
        else -> Kind.NONE
    }

    fun ready() = kind() != Kind.NONE

    fun kindLabel(): String = when (kind()) {
        Kind.SHIZUKU -> "Shizuku"
        Kind.ROOT -> "Root"
        Kind.NONE -> "يدوي (قائمة Phone info)"
    }

    /** يعيد (رمز الخروج، المخرجات). يُستدعى من خيط خلفي فقط. */
    fun exec(cmd: String, timeoutSec: Long = 10): Pair<Int, String> {
        return try {
            val p: Process = when (kind()) {
                Kind.SHIZUKU -> shizukuProcess(arrayOf("sh", "-c", cmd))
                Kind.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                Kind.NONE -> return -1 to "لا توجد صلاحية (Shizuku غير مفعّل)"
            }
            val out = StringBuilder()
            val t1 = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val err = StringBuilder()
            val t2 = Thread { try { err.append(p.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            t1.start(); t2.start()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) { try { p.destroy() } catch (_: Exception) {}; return -2 to "انتهت المهلة" }
            t1.join(1000); t2.join(1000)
            p.exitValue() to (out.toString() + err.toString()).trim()
        } catch (e: Throwable) {
            -3 to (e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** فحص الروت (قد يُظهر نافذة طلب صلاحية). */
    fun testRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor(8, TimeUnit.SECONDS) && o.contains("uid=0")
    } catch (e: Throwable) { false }

    private fun shizukuProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }
}

data class Res(val ok: Boolean, val msg: String)

object Net {
    private fun b(t: Int) = 1L shl (t - 1)
    // أرقام أنواع الشبكة حسب TelephonyManager.NETWORK_TYPE_*
    private val M2 = b(16) or b(1) or b(2)                     // GSM, GPRS, EDGE
    private val M3 = b(3) or b(8) or b(9) or b(10) or b(15)    // UMTS, HSDPA, HSUPA, HSPA, HSPA+
    private val M4 = b(13) or b(19)                            // LTE, LTE_CA
    private val M5 = b(20)                                     // NR

    fun famToMask(f: Int): Long {
        var m = 0L
        if (f and G2 != 0) m = m or M2
        if (f and G3 != 0) m = m or M3
        if (f and G4 != 0) m = m or M4
        if (f and G5 != 0) m = m or M5
        return m
    }

    fun maskToFam(m: Long): Int {
        var f = 0
        if (m and M2 != 0L) f = f or G2
        if (m and M3 != 0L) f = f or G3
        if (m and M4 != 0L) f = f or G4
        if (m and M5 != 0L) f = f or G5
        return f
    }

    /** يقرأ مخرجات get-allowed-network-types-for-users (أسماء أو رقم ثنائي/عشري). */
    fun parseGet(out: String): Int? {
        val u = out.uppercase()
        var f = 0
        if (Regex("(^|[^A-Z])NR([^A-Z]|$)").containsMatchIn(u)) f = f or G5
        if (u.contains("LTE")) f = f or G4
        if (Regex("UMTS|HSPA|HSDPA|HSUPA|WCDMA").containsMatchIn(u)) f = f or G3
        if (Regex("(^|[^A-Z])GSM|GPRS|EDGE").containsMatchIn(u)) f = f or G2
        if (f != 0) return f
        val tok = Regex("[01]{8,}").find(u)?.value
        if (tok != null) return maskToFam(java.lang.Long.parseLong(tok, 2))
        val dec = Regex("\\d{3,}").find(u)?.value?.toLongOrNull()
        if (dec != null && dec > 0) return maskToFam(dec)
        return null
    }

    fun current(slot: Int): Int? {
        if (!Shell.ready()) return null
        val (c, o) = Shell.exec("cmd phone get-allowed-network-types-for-users -s $slot")
        if (c != 0) return null
        return parseGet(o)
    }

    fun rawGet(slot: Int): String = Shell.exec("cmd phone get-allowed-network-types-for-users -s $slot").let { "${it.first}: ${it.second}" }

    /** يطبّق النمط. يُستدعى من خيط خلفي. */
    fun apply(slot: Int, mode: Mode): Res {
        if (!Shell.ready()) return Res(false, "Shizuku غير مفعّل")
        val bin = java.lang.Long.toBinaryString(mode.mask)
        val (code, out) = Shell.exec("cmd phone set-allowed-network-types-for-users -s $slot $bin")
        if (!out.contains("completed")) {
            Log.add("apply $slot ${mode.name} fail: $code $out")
            return Res(false, out.ifBlank { "فشل (رمز $code)" })
        }
        val cur = current(slot)
        Log.add("apply $slot ${mode.name} ok, read=${cur}")
        return if (cur == null || cur == mode.fam) Res(true, "")
        else Res(true, "طُبّق، لكن الهاتف يقرأ: ${famLabel(cur)}")
    }

    /** تطبيق + حفظ + المؤقت. */
    fun applyAndRecord(c: Context, slot: Int, mode: Mode, timerMin: Int = -1): Res {
        val r = apply(slot, mode)
        if (r.ok) {
            Prefs.setLast(slot, mode.name)
            Timers.cancel(c, slot)
            val m = if (timerMin >= 0) timerMin else Prefs.lteTimerMin
            val timed = mode == Mode.LTE_ONLY && m > 0 && Remote.on("lte_timer")
            Prefs.setTimed(slot, timed)
            if (timed) Timers.schedule(c, slot, m)
            Shortcuts.refresh(c)
        }
        return r
    }

    /** فتح قائمة Phone info المخفية (الطريقة اليدوية). */
    fun openRadioInfo(c: Context): Boolean {
        val comps = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$TestingSettingsActivity")
        )
        for (cn in comps) {
            try {
                c.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {}
        }
        return try {
            c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#4636#*#*")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) { false }
    }

    fun radioInfoAvailable(c: Context): Boolean {
        val pm = c.packageManager
        return listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo")
        ).any { cn ->
            try { pm.getActivityInfo(cn, 0).exported } catch (_: Exception) { false }
        }
    }
}

/** سجل داخلي صغير يُرفق بتقرير التوافق. */
object Log {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(s: String) {
        lines.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s)
        while (lines.size > 60) lines.removeFirst()
    }
    @Synchronized fun dump(): String = lines.joinToString("\n")
}
