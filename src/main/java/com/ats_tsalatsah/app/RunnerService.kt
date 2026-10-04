package com.ats_tsalatsah.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
        const val MARK = "===== Sesi baru"
        private const val MAX_SESSIONS = 20
        private const val MAX_LINES = 4000

        @Volatile var running = false
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var found = 0
        @Volatile var phase = "Siap"
        private val logs = ArrayList<String>()
        private val servers = ArrayList<String>()
        private var prefs: SharedPreferences? = null
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        // Muat log dan daftar server yang tersimpan
        fun initStore(ctx: Context) {
            synchronized(logs) {
                if (prefs != null) return
                val p = ctx.applicationContext.getSharedPreferences("ats_store", Context.MODE_PRIVATE)
                prefs = p
                val l = p.getString("logs", "") ?: ""
                if (l.isNotEmpty()) logs.addAll(l.split("\n"))
                val s = p.getString("servers", "") ?: ""
                if (s.isNotEmpty()) servers.addAll(s.split("\n"))
            }
        }

        private fun persist() {
            prefs?.edit()
                ?.putString("logs", logs.joinToString("\n"))
                ?.putString("servers", servers.joinToString("\n"))
                ?.apply()
        }

        private fun sessionCount(): Int = logs.count { it.contains(MARK) }

        // Buang satu sesi utuh yang paling lama (bukan baris acak)
        private fun trimLogs() {
            while (logs.size > MAX_LINES || sessionCount() > MAX_SESSIONS) {
                var idx = -1
                for (i in 1 until logs.size) {
                    if (logs[i].contains(MARK)) {
                        idx = i
                        break
                    }
                }
                if (idx > 0) {
                    for (k in 0 until idx) logs.removeAt(0)
                } else {
                    logs.removeAt(0)
                }
            }
        }

        fun addLog(s: String) {
            synchronized(logs) {
                logs.add(fmt.format(Date()) + "  " + s)
                trimLogs()
                persist()
            }
        }

        // Simpan daftar server, urut dari kecil ke besar
        fun setServers(map: Map<Int, String>) {
            synchronized(logs) {
                servers.clear()
                map.entries.sortedBy { it.key }.forEach { servers.add("${it.key}  ${it.value}") }
                persist()
            }
        }

        // Hapus log dan daftar server (tidak bisa saat sedang berjalan)
        fun clearAll(): Boolean {
            if (running) return false
            synchronized(logs) {
                logs.clear()
                servers.clear()
                persist()
            }
            found = 0
            done = 0
            total = 0
            phase = "Siap"
            return true
        }

        fun statusJson(): String {
            val o = JSONObject()
            o.put("running", running)
            o.put("done", done)
            o.put("total", total)
            o.put("found", found)
            o.put("phase", phase)
            val arr = JSONArray()
            val srv = JSONArray()
            synchronized(logs) {
                logs.forEach { arr.put(it) }
                servers.forEach { srv.put(it) }
            }
            o.put("logs", arr)
            o.put("servers", srv)
            return o.toString()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    private var capsule: TextView? = null

    override fun onCreate() {
        super.onCreate()
        initStore(this)
    }

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
        val d3 = intent.getLongExtra("d3", 600L)
        val target = intent.getIntExtra("target", 0)
        val back = intent.getBooleanExtra("back", false)

        startAsForeground()
        running = true
        stopRequested = false
        Thread { runAll(pkgs.toList(), d1, d2, d3, target, back) }.start()
        return START_NOT_STICKY
    }

    // Gambar ikon aplikasi (latar hitam + logo putih) untuk notifikasi
    private fun appIconBitmap(): Bitmap? {
        return try {
            val d = getDrawable(R.mipmap.ic_launcher) ?: return null
            val size = 192
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("ats", "Project ats", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(this, "ats")
            .setContentTitle("Project ats")
            .setContentText("Sedang berjalan")
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Color.BLACK)
        val big = appIconBitmap()
        if (big != null) b.setLargeIcon(big)
        val n: Notification = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // Tunggu sampai jam tertentu (uptime), dihitung dari jam sungguhan
    private fun sleepUntil(t: Long) {
        while (!stopRequested) {
            val left = t - SystemClock.uptimeMillis()
            if (left <= 0) break
            Thread.sleep(if (left > 50) 50L else left)
        }
    }

    private fun sleepMs(ms: Long) {
        sleepUntil(SystemClock.uptimeMillis() + ms)
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

    // Pindah ke layar utama (home)
    private fun goHome() {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_HOME)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    // Jalankan pekerjaan di thread terpisah dengan batas waktu,
    // supaya tidak pernah menggantung dan jatah waktu tetap seragam.
    private fun <T> limited(limit: Long, fallback: T, job: () -> T): T {
        val latch = CountDownLatch(1)
        var res: T = fallback
        val th = Thread {
            try {
                res = job()
            } catch (e: Throwable) {
            }
            latch.countDown()
        }
        th.isDaemon = true
        th.start()
        latch.await(limit, TimeUnit.MILLISECONDS)
        return res
    }

    // Tutup pembaca log di latar belakang (tidak menunda waktu aplikasi)
    private fun closeAsync(s: ShizukuHelper.ZoneStream?) {
        if (s == null) return
        val th = Thread {
            try {
                s.close()
            } catch (e: Throwable) {
            }
        }
        th.isDaemon = true
        th.start()
    }

    // Baca server dari logcat berdasarkan UID aplikasi, selama jatah waktunya.
    // Pembaca log dibuka segera, dicek tiap ~20 ms. Kalau gagal dibuka,
    // dicoba ulang maksimal 3 kali. Tidak ada cadangan lain.
    // Hasil: nomor server, atau null kalau tidak terbaca.
    private fun readUntil(pkg: String, uid: Int, t0: Long, wall: Long, deadline: Long): Int? {
        var stream: ShizukuHelper.ZoneStream? = null
        var opens = 0
        var lastLines = 0
        var lastHead = ""
        var lastPids = ""
        var lastOut = ""
        val reasons = ArrayList<String>()

        fun reason(s: String) {
            if (reasons.size < 5 && !reasons.contains(s)) reasons.add(s)
        }

        fun remember(st: ShizukuHelper.ZoneStream) {
            lastLines = st.lines
            lastHead = st.head
            lastPids = st.pids
            lastOut = st.outOfRange
        }

        try {
            while (!stopRequested) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break

                val st = stream
                if (st != null) {
                    val z = st.zone
                    if (z != null) {
                        val at = st.foundUptime - t0
                        val jam = if (st.logTime.isEmpty()) "-" else st.logTime
                        val wp = if (st.linePid.isEmpty()) "-" else st.linePid
                        addLog("Baca log OK: server $z ketemu di $at ms (uid=$uid, pid penulis=$wp, jam pesan $jam, baris dibaca ${st.lines})")
                        closeAsync(st)
                        stream = null
                        return z
                    }
                    if (st.ended) {
                        remember(st)
                        reason("pembaca log berakhir sendiri (${st.lines} baris)")
                        closeAsync(st)
                        stream = null
                    } else {
                        sleepMs(minOf(20L, left))
                    }
                    continue
                }

                // Belum ada pembaca log yang jalan
                if (opens >= 3) {
                    reason("gagal dibuka 3 kali, tidak dicoba lagi")
                    sleepUntil(deadline)
                    break
                }
                opens++
                val o = limited(
                    minOf(left - 20L, 1000L).coerceAtLeast(50L),
                    Pair<ShizukuHelper.ZoneStream?, String>(null, "terlalu lama")
                ) {
                    ShizukuHelper.openUidStream(uid, wall - 300L)
                }
                if (o.first != null) {
                    stream = o.first
                    addLog("Pembaca log dibuka di ${SystemClock.uptimeMillis() - t0} ms (uid=$uid, percobaan ke-$opens)")
                } else {
                    reason("buka pembaca log: " + o.second)
                    sleepMs(minOf(100L, maxOf(deadline - SystemClock.uptimeMillis(), 0L)))
                }
            }
        } finally {
            val s = stream
            if (s != null) {
                remember(s)
                closeAsync(s)
            }
        }

        if (!stopRequested) {
            addLog("Baca log gagal: server tidak terbaca (uid=$uid, dibuka $opens kali, baris dibaca $lastLines)")
            addLog("  baris pertama keluaran: " + (if (lastHead.isEmpty()) "(kosong)" else lastHead))
            addLog("  PID terlihat: " + (if (lastPids.isEmpty()) "(tidak ada)" else lastPids))
            if (lastOut.isNotEmpty()) addLog("  angka di luar rentang: $lastOut")
            for (r in reasons) addLog("  alasan: $r")
        }
        return null
    }

    // Force stop + buang kartu dari recent apps lewat Shizuku, tanpa animasi.
    private fun dismiss(pkg: String) {
        if (!ShizukuHelper.hasPermission()) {
            addLog("Shizuku belum aktif/diizinkan, $pkg tidak dibersihkan")
            return
        }
        goHome()
        Thread.sleep(200)
        val ok = ShizukuHelper.forceStop(pkg)
        addLog(if (ok) "Force stop $pkg berhasil" else "Force stop $pkg gagal")
        val r = ShizukuHelper.removeFromRecents(pkg)
        addLog((if (r.first) "Recent $pkg: " else "Recent $pkg GAGAL: ") + r.second)
    }

    private fun runAll(pkgs: List<String>, d1: Long, d2: Long, d3: Long, target: Int, back: Boolean) {
        done = 0
        found = 0
        total = pkgs.size
        val map = LinkedHashMap<Int, String>()
        setServers(map)

        addLog("$MARK ${dateFmt.format(Date())} =====")
        addLog("Jeda 1 = $d1 ms, jeda 2 = $d2 ms, jeda tengah = $d3 ms, target = $target")

        if (!ShizukuHelper.hasPermission()) {
            addLog("Shizuku belum aktif/diizinkan. Buka Shizuku dan beri izin, lalu coba lagi.")
            phase = "Dihentikan"
            running = false
            main.post { stopSelf() }
            return
        }

        // Putaran 1: tiap aplikasi dapat jatah waktu tetap (jeda 1)
        phase = "Putaran 1"
        addLog("Mulai putaran 1 (${pkgs.size} aplikasi)")
        for (pkg in pkgs) {
            if (stopRequested) break
            if (target > 0 && map.size >= target) {
                addLog("Target $target server tercapai, sisa aplikasi dilewati")
                break
            }

            // UID diambil sebelum aplikasi dibuka (cepat, tanpa Shizuku)
            var uid = -1
            var uidErr = ""
            try {
                uid = packageManager.getApplicationInfo(pkg, 0).uid
            } catch (e: Throwable) {
                uidErr = e.javaClass.simpleName + " " + (e.message ?: "")
            }

            val wall = System.currentTimeMillis()
            val t0 = SystemClock.uptimeMillis()
            if (!launch(pkg)) {
                addLog("Gagal buka $pkg")
                done++
                continue
            }
            addLog("Buka $pkg")
            if (uid >= 0) {
                addLog("UID $pkg = $uid")
            } else {
                addLog("UID $pkg tidak bisa diambil: $uidErr")
            }

            val deadline = t0 + d1
            val s: Int? = if (uid >= 0) readUntil(pkg, uid, t0, wall, deadline) else null
            // Walau sudah terbaca, tetap tunggu sampai jatah habis
            sleepUntil(deadline)

            if (s != null && !map.containsKey(s)) {
                // Server baru: dicatat, TIDAK dibersihkan
                map[s] = pkg
                found = map.size
                setServers(map)
                addLog("Server $s dicatat ($pkg)")
            } else {
                if (s == null) {
                    addLog("Server tidak terbaca, bersihkan $pkg")
                } else {
                    addLog("Server $s sudah ada, bersihkan $pkg")
                }
                if (!stopRequested) {
                    val c0 = SystemClock.uptimeMillis()
                    dismiss(pkg)
                    val used = SystemClock.uptimeMillis() - c0
                    if (used > d3) {
                        addLog("Pembersihan butuh $used ms, melebihi jeda tengah $d3 ms")
                    } else {
                        sleepUntil(c0 + d3)
                    }
                }
            }
            val total1 = SystemClock.uptimeMillis() - t0
            addLog("Waktu aplikasi ini: $total1 ms")
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
