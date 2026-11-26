package com.makanona.netmode

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

val bg = Executors.newSingleThreadExecutor()
val main = Handler(Looper.getMainLooper())

/** بعد إعادة التشغيل أو التحديث. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        val boot = i.action == Intent.ACTION_BOOT_COMPLETED
        if (boot) Timers.restore(c)
        if (boot && Prefs.reapplyOnBoot && Remote.on("boot_reapply")) Prefs.pendingReapply = true
        CheckJob.schedule(c)
        MonitorService.sync(c, force = Prefs.pendingReapply)
    }
}

/** أزرار الإشعارات + انتهاء مؤقت LTE فقط. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val APPLY = "com.makanona.netmode.ACT_APPLY"
        const val TIMER = "com.makanona.netmode.ACT_TIMER"
    }

    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        val slot = i.getIntExtra("slot", 0)
        val pr = goAsync()
        val mode = if (i.action == TIMER) Mode.AUTO else Mode.of(i.getStringExtra("mode")) ?: Mode.AUTO
        bg.execute {
            try {
                if (i.action == TIMER) Prefs.setTimerEnd(slot, 0)
                val r = if (Shell.ready()) Net.applyAndRecord(c, slot, mode) else Res(false, "")
                if (i.action == TIMER) {
                    if (r.ok) Notif.post(c, Notif.ALERT, 300 + slot, tr("انتهى مؤقت LTE Only", "LTE Only timer ended"),
                        tr("رجعت شريحة ${slot + 1} إلى ", "SIM ${slot + 1} is back to ") + mode.title, Intent(c, MainActivity::class.java))
                    else Notif.post(c, Notif.ALERT, 300 + slot, tr("انتهى مؤقت LTE Only", "LTE Only timer ended"),
                        tr("اضغط للرجوع إلى ${mode.title} على شريحة ${slot + 1}", "Tap to switch SIM ${slot + 1} to ${mode.title}"),
                        Intent(c, MainActivity::class.java))
                } else {
                    Notif.cancel(c, 200 + slot)
                    if (!r.ok) Notif.post(c, Notif.ALERT, 300 + slot, tr("تعذّر التطبيق", "Could not apply"), r.msg.ifBlank { tr("افتح التطبيق", "Open the app") },
                        Intent(c, MainActivity::class.java))
                }
            } finally { pr.finish() }
        }
    }
}

/** اختصار الشاشة الرئيسية: يطبّق ويغلق فوراً. */
class ShortcutActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        val slot = intent.getIntExtra("slot", 0)
        val m = Mode.of(intent.getStringExtra("mode"))
        if (m == null) { finish(); return }
        if (!Shell.ready()) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("manual_slot", slot).putExtra("manual_mode", m.name))
            finish(); return
        }
        val app = applicationContext
        bg.execute {
            val r = Net.applyAndRecord(app, slot, m)
            main.post {
                Toast.makeText(app, if (r.ok) "✓ ${m.title} — SIM ${slot + 1}" else tr("تعذّر: ", "Failed: ") + r.msg, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

/** زر سريع في شريط الإشعارات: يبدّل بين نمطين. */
class NetTileService : TileService() {
    override fun onStartListening() { Prefs.init(this); update() }

    private fun update() {
        val t = qsTile ?: return
        val cur = Prefs.last(Prefs.tileSlot)
        t.label = cur?.title?.substringBefore(" (") ?: tr("نمط الشبكة", "Network Mode")
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = "SIM ${Prefs.tileSlot + 1}"
        t.state = if (cur == Prefs.tileB) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        Prefs.init(this)
        if (!Shell.ready()) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
            return
        }
        val slot = Prefs.tileSlot
        val next = if (Prefs.last(slot) == Prefs.tileB) Prefs.tileA else Prefs.tileB
        val app = applicationContext
        qsTile?.let { it.label = "…"; it.updateTile() }
        bg.execute {
            Net.applyAndRecord(app, slot, next)
            main.post { update() }
        }
    }
}

/** خدمة المراقبة: تنبيه الانقطاع، تحويل المكالمة، إعادة التطبيق بعد التشغيل، الإعلانات. */
class MonitorService : Service() {
    companion object {
        const val FG_ID = 1
        /** الخدمة الدائمة (ومعها الإشعار) فقط عند الحاجة الفعلية. */
        fun needed() = Prefs.persistent || (Prefs.callSwitch && Remote.on("call_switch")) || Prefs.pendingReapply

        fun sync(c: Context, force: Boolean = false) {
            Prefs.init(c)
            val i = Intent(c, MonitorService::class.java)
            try {
                if (force || needed()) c.startForegroundService(i) else c.stopService(i)
            } catch (_: Exception) {}
        }
    }

    private val outSince = HashMap<Int, Long>()
    private val alerted = HashSet<Int>()
    private var lastRefresh = 0L
    private var tmCallback: Any? = null
    private var callState = TelephonyManager.CALL_STATE_IDLE

    private val binderListener = Shizuku.OnBinderReceivedListener { bg.execute { reapplyIfPending() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> bg.execute { reapplyIfPending() } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val n = Notif.build(this, Notif.STATUS, tr("نمط الشبكة يعمل", "Network Mode is running"), "…", Intent(this, MainActivity::class.java))
            .setOngoing(true).setAutoCancel(false).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(FG_ID, n)
        try { Shizuku.addBinderReceivedListenerSticky(binderListener) } catch (_: Throwable) {}
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        listenCalls()
        main.post(tick)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        bg.execute { reapplyIfPending() }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        try { Shizuku.removeBinderReceivedListener(binderListener) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        unlistenCalls()
        super.onDestroy()
    }

    private fun reapplyIfPending() {
        if (!Prefs.pendingReapply || !Shell.ready()) return
        Prefs.pendingReapply = false
        val done = mutableListOf<String>()
        for (s in Sims.list(this)) {
            val m = Prefs.preferred(s.slot) ?: Prefs.last(s.slot) ?: continue
            if (m == Mode.LTE_ONLY && Prefs.timed(s.slot) && Prefs.timerEnd(s.slot) == 0L) continue
            if (Net.apply(s.slot, m).ok) { Prefs.setLast(s.slot, m.name); done += "SIM${s.slot + 1}: ${m.title}" }
        }
        if (done.isNotEmpty()) Notif.post(this, Notif.ALERT, 400, tr("أُعيد تطبيق الأنماط", "Modes re-applied"), done.joinToString("\n"), null)
        if (!needed()) main.post { stopSelf() }
    }

    private val tick = object : Runnable {
        override fun run() {
            bg.execute { check() }
            main.postDelayed(this, 30_000)
        }
    }

    private fun check() {
        val sims = Sims.list(this)
        // الإشعار الدائم
        val txt = if (sims.isEmpty()) tr("لا توجد شريحة", "No SIM") else sims.joinToString("\n") { s ->
            "SIM${s.slot + 1} ${s.carrier}: ${Prefs.last(s.slot)?.title ?: "—"} · ${s.net}" + (s.dbm?.let { " · $it dBm" } ?: "")
        }
        val waiting = Prefs.pendingReapply && !Shell.ready()
        val title = when {
            waiting -> tr("بانتظار Shizuku", "Waiting for Shizuku")
            Shell.ready() -> tr("نمط الشبكة", "Network Mode")
            else -> tr("نمط الشبكة · Shizuku متوقف", "Network Mode · Shizuku stopped")
        }
        val body = if (waiting) tr("اضغط لتشغيل Shizuku، وستُعاد أنماطك تلقائياً.", "Tap to start Shizuku; your modes will be re-applied.") else txt
        val tap = if (waiting) packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG) ?: Intent(this, GuideActivity::class.java)
                  else Intent(this, MainActivity::class.java)
        val ongoing = Notif.build(this, Notif.STATUS, title, body, tap).setOngoing(true).setAutoCancel(false).build()
        try { getSystemService(android.app.NotificationManager::class.java).notify(FG_ID, ongoing) } catch (_: Exception) {}

        Drops.observe(this, sims)
        NetWidget.update(this)

        // الإعلانات والإعدادات البعيدة
        if (System.currentTimeMillis() - lastRefresh > 6 * 3600_000L) {
            lastRefresh = System.currentTimeMillis()
            Remote.refresh(this)
        }
        reapplyIfPending()
    }

    // ===== تحويل مؤقت عند الاتصال (تجريبي) =====
    private fun onCall(state: Int) {
        callState = state
        if (!Prefs.callSwitch || !Remote.on("call_switch")) return
        bg.execute {
            if (!Shell.ready()) return@execute
            if (state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING) {
                for (s in 0..1) if (Prefs.last(s) == Mode.LTE_ONLY) {
                    if (Net.apply(s, Mode.LTE_CALLS).ok) Prefs.setCallRevert(s, true)
                }
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                main.postDelayed({
                    if (callState == TelephonyManager.CALL_STATE_IDLE) bg.execute {
                        for (s in 0..1) if (Prefs.callRevert(s)) {
                            Net.apply(s, Mode.LTE_ONLY); Prefs.setCallRevert(s, false)
                        }
                    }
                }, 8000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun listenCalls() {
        if (!Sims.has(this, Manifest.permission.READ_PHONE_STATE)) return
        val tm = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCall(state)
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                tmCallback = cb
            } else {
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCall(state)
                }
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                tmCallback = l
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun unlistenCalls() {
        val tm = getSystemService(TelephonyManager::class.java)
        try {
            val cb = tmCallback
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is PhoneStateListener) tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}
    }
}
