package com.makanona.netmode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import rikka.shizuku.Shizuku

const val G2 = 1
const val G3 = 2
const val G4 = 4
const val G5 = 8

/** أنماط الشبكة. الأسماء والشروح بالإنكليزي دائماً. fam = العائلات المسموحة. */
enum class Mode(
    val title: String,
    val sub: String,
    val fam: Int,
    val radioInfo: String,
    val samsung: String?,
    val warn: String? = null
) {
    AUTO("Auto (5G/4G/3G/2G)", "Normal mode — best available network", G2 or G3 or G4 or G5,
        "NR/LTE/GSM/WCDMA", "5G/LTE/3G/2G (auto connect)"),
    LTE_CALLS("4G + Calls (LTE/3G/2G)", "4G data, calls fall back to 3G/2G", G2 or G3 or G4,
        "LTE/GSM/WCDMA", "LTE/3G/2G (auto connect)"),
    LTE_3G("4G / 3G (LTE/WCDMA)", "No 2G", G3 or G4, "LTE/WCDMA", null),
    LTE_ONLY("LTE Only", "4G only, no fallback", G4, "LTE only", null,
        "Calls and SMS may fail without VoLTE"),
    NR_LTE("5G / 4G (NR/LTE)", "Needs 5G phone and operator", G4 or G5, "NR/LTE", null),
    NR_ONLY("5G Only", "Only where 5G is available", G5, "NR only", null, "No service where 5G is missing"),
    WCDMA_GSM("3G / 2G (WCDMA/GSM)", "No 4G", G2 or G3, "GSM/WCDMA auto", "3G/2G (auto connect)"),
    WCDMA_ONLY("3G Only", "WCDMA only", G3, "WCDMA only", "3G only"),
    GSM_ONLY("2G Only (GSM)", "Stable calls, very slow data", G2, "GSM only", "2G only",
        "Data on 2G is very slow");

    val mask: Long get() = Net.famToMask(fam)

    companion object {
        val DEFAULT_VISIBLE = listOf(AUTO, LTE_CALLS, LTE_3G, LTE_ONLY)
        fun of(n: String?): Mode? = values().firstOrNull { it.name == n }
        fun byFam(f: Int?): Mode? = if (f == null) null else values().firstOrNull { it.fam == f }
    }
}

fun famLabel(f: Int?): String {
    if (f == null) return tr("غير معروف", "Unknown")
    Mode.byFam(f)?.let { return it.title }
    val p = mutableListOf<String>()
    if (f and G5 != 0) p += "5G"; if (f and G4 != 0) p += "4G"
    if (f and G3 != 0) p += "3G"; if (f and G2 != 0) p += "2G"
    return if (p.isEmpty()) tr("غير معروف", "Unknown") else p.joinToString(" / ")
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
        Kind.NONE -> tr("يدوي", "Manual")
    }

    /** يعيد (رمز الخروج، المخرجات). يُستدعى من خيط خلفي فقط. */
    fun exec(cmd: String, timeoutSec: Long = 10): Pair<Int, String> = execInput(cmd, null, timeoutSec)

    /** مثل exec مع إرسال ملف إلى stdin (للتثبيت الصامت). */
    fun execInput(cmd: String, input: java.io.File?, timeoutSec: Long = 10): Pair<Int, String> {
        return try {
            val p: Process = when (kind()) {
                Kind.SHIZUKU -> shizukuProcess(arrayOf("sh", "-c", cmd))
                Kind.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                Kind.NONE -> return -1 to tr("لا توجد صلاحية (Shizuku غير مفعّل)", "No permission (Shizuku not active)")
            }
            if (input != null) p.outputStream.use { os -> input.inputStream().use { it.copyTo(os) } }
            else try { p.outputStream.close() } catch (_: Exception) {}
            val out = StringBuilder()
            val t1 = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val err = StringBuilder()
            val t2 = Thread { try { err.append(p.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            t1.start(); t2.start()
            // لا نستخدم waitFor(timeout): عملية Shizuku ترمي IllegalArgumentException من exitValue() قبل الانتهاء.
            var code = -9
            val w = Thread { try { code = p.waitFor() } catch (_: Throwable) {} }
            w.start(); w.join(timeoutSec * 1000)
            if (w.isAlive) { try { p.destroy() } catch (_: Exception) {}; return -2 to tr("انتهت المهلة", "Timed out") }
            t1.join(2000); t2.join(2000)
            code to (out.toString() + err.toString()).trim()
        } catch (e: Throwable) {
            -3 to (e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** فحص الروت (قد يُظهر نافذة طلب صلاحية). */
    fun testRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor(); o.contains("uid=0")
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
        if (!Shell.ready()) return Res(false, tr("Shizuku غير مفعّل", "Shizuku is not active"))
        val bin = java.lang.Long.toBinaryString(mode.mask)
        val (code, out) = Shell.exec("cmd phone set-allowed-network-types-for-users -s $slot $bin")
        if (!out.contains("completed")) {
            Log.add("apply $slot ${mode.name} fail: $code $out")
            return Res(false, out.ifBlank { tr("فشل (رمز $code)", "Failed (code $code)") })
        }
        val cur = current(slot)
        Log.add("apply $slot ${mode.name} ok, read=${cur}")
        return if (cur == null || cur == mode.fam) Res(true, "")
        else Res(true, tr("طُبّق، لكن الهاتف يقرأ: ", "Applied, but phone reads: ") + famLabel(cur))
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
            if (mode == Mode.LTE_ONLY) Guard.arm(c, slot)
            Shortcuts.refresh(c)
            NetWidget.update(c)
        }
        return r
    }

    val isSamsung get() = Build.MANUFACTURER.equals("samsung", true)

    /** فتح قائمة Phone info المخفية. لا نستخدم ACTION_DIAL العام حتى لا يلتقطه تطبيق آخر (مثل ForceLTE). */
    fun openRadioInfo(c: Context): Boolean {
        val pm = c.packageManager
        val comps = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo")
        )
        for (cn in comps) {
            val exported = try { pm.getActivityInfo(cn, 0).exported } catch (_: Exception) { false }
            if (!exported) continue
            try {
                c.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Exception) {}
        }
        // تطبيق الهاتف الافتراضي بالاسم مع الكود
        val dialers = listOfNotNull(
            try { c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (_: Exception) { null },
            "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"
        ).distinct()
        val uri = Uri.parse("tel:" + Uri.encode(SECRET))
        for (pkg in dialers) {
            try {
                c.startActivity(Intent(Intent.ACTION_DIAL, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    const val SECRET = "*#*#4636#*#*"

    /** صفحة «الشبكات المحمولة» في إعدادات سامسونج (فيها Network mode لكل شريحة). */
    fun openSamsungNetwork(c: Context): Boolean {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.app.telephonyui",
                "com.samsung.android.app.telephonyui.netsettings.ui.NetSettingsActivity")),
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        }
        return false
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
