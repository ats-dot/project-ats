package com.ats_tsalatsah.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunnerService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var found = 0
        @Volatile var phase = "Siap"
        private val logs = ArrayList<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun addLog(s: String) {
            synchronized(logs) {
                logs.add(fmt.format(Date()) + "  " + s)
                if (logs.size > 300) logs.removeAt(0)
            }
        }

        fun statusJson(): String {
            val o = JSONObject()
            o.put("running", running)
            o.put("done", done)
            o.put("total", total)
            o.put("found", found)
            o.put("phase", phase)
            val arr = JSONArray()
            synchronized(logs) { logs.forEach { arr.put(it) } }
            o.put("logs", arr)
            return o.toString()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    private var capsule: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRequested = true
            return START_NOT_STICKY
        }
        if (running || intent == null) return START_NOT_STICKY

        val pkgs = intent.getStringArrayExtra("pkgs") ?: emptyArray()
        val d1 = intent.getLongExtra("d1", 7450L)
        val d2 = intent.getLongExtra("d2", 6500L)
        val target = intent.getIntExtra("target", 0)
        val back = intent.getBooleanExtra("back", false)

        startAsForeground()
        running = true
        stopRequested = false
        Thread { runAll(pkgs.toList(), d1, d2, target, back) }.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("ats", "Project ats", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, "ats")
            .setContentTitle("Project ats")
            .setContentText("Sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested) {
            val s = if (left > 100) 100L else left
            Thread.sleep(s)
            left -= s
        }
    }

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readOnce(): Int? {
        val svc = AtsAccessibilityService.instance ?: return null
        val latch = CountDownLatch(1)
        var r: Int? = null
        main.post {
            svc.readServer {
                r = it
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return r
    }

    private fun readServer(): Int? {
        for (i in 1..3) {
            if (stopRequested) return null
            val r = readOnce()
            if (r != null) return r
            sleepMs(700)
        }
        return null
    }

    private fun dismiss() {
        val svc = AtsAccessibilityService.instance ?: return
        val latch = CountDownLatch(1)
        main.post { svc.dismissFromRecents { latch.countDown() } }
        latch.await(6, TimeUnit.SECONDS)
    }

    private fun runAll(pkgs: List<String>, d1: Long, d2: Long, target: Int, back: Boolean) {
        done = 0
        found = 0
        total = pkgs.size
        val map = LinkedHashMap<Int, String>()
        AtsAccessibilityService.updateAllowed(pkgs.toSet())

        if (AtsAccessibilityService.instance == null) {
            addLog("Izin Aksesibilitas belum aktif. Nyalakan dulu di Setelan.")
        }

        // Putaran 1
        phase = "Putaran 1"
        addLog("Mulai putaran 1 (${pkgs.size} aplikasi)")
        for (pkg in pkgs) {
            if (stopRequested) break
            if (target > 0 && map.size >= target) {
                addLog("Target $target server tercapai, sisa aplikasi dilewati")
                break
            }
            if (!launch(pkg)) {
                addLog("Gagal buka $pkg")
                done++
                continue
            }
            addLog("Buka $pkg")
            sleepMs(d1)
            val s = readServer()
            if (s == null) {
                addLog("Server tidak terbaca: $pkg")
            } else if (map.containsKey(s)) {
                addLog("Server $s sudah ada, buang $pkg dari recent apps")
                dismiss()
            } else {
                map[s] = pkg
                found = map.size
                addLog("Server $s dicatat ($pkg)")
            }
            done++
        }

        // Putaran 2
        if (!stopRequested) {
            val list = map.entries.sortedBy { it.key }
            phase = "Putaran 2"
            done = 0
            total = list.size
            addLog("Mulai putaran 2 (${list.size} server, kecil ke besar)")
            for (e in list) {
                if (stopRequested) break
                if (launch(e.value)) addLog("Buka server ${e.key} (${e.value})") else addLog("Gagal buka ${e.value}")
                sleepMs(d2)
                done++
            }
        }

        val result = map.size
        if (back && !stopRequested) launch(packageName)
        phase = if (stopRequested) "Dihentikan" else "Selesai"
        addLog(if (stopRequested) "Dihentikan" else "Selesai $result server")
        running = false

        if (stopRequested) {
            main.post { stopSelf() }
        } else {
            main.post { showCapsule("Selesai $result server") }
        }
    }

    private fun showCapsule(text: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.BLACK)
        tv.textSize = 16f
        tv.gravity = Gravity.CENTER
        tv.setPadding(60, 28, 60, 28)
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = 200f
        tv.background = bg

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = (resources.displayMetrics.heightPixels * 0.25).toInt()

        try {
            wm.addView(tv, lp)
            capsule = tv
        } catch (e: Exception) {
            stopSelf()
            return
        }
        tv.setOnClickListener { stopSelf() }
        main.postDelayed({ stopSelf() }, 2000)
    }

    private fun removeCapsule() {
        capsule?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        capsule = null
    }

    override fun onDestroy() {
        stopRequested = true
        removeCapsule()
        running = false
        super.onDestroy()
    }
}
