package com.ats_tsalatsah.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShizukuHelper {

    private var bypassed = false

    // Pesan server di logcat: "... ZoneID = 57098 stack: ..."
    private val ZONE = Regex("ZoneID\\s*=\\s*(\\d{5})", RegexOption.IGNORE_CASE)

    // Jam di awal baris logcat: "MM-dd HH:mm:ss.SSS ..."
    private val LOG_TIME = Regex("^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d\\.\\d{3})")

    private fun bypass() {
        if (bypassed) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Throwable) {
        }
        bypassed = true
    }

    private fun validPkg(p: String): Boolean = Regex("[A-Za-z0-9_.]+").matches(p)

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isRunning() && !hasPermission()) Shizuku.requestPermission(100)
        } catch (e: Throwable) {
        }
    }

    private fun start(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null as Array<String>?, null as String?) as Process
    }

    // Jalankan perintah lewat Shizuku.
    // Hasil: (kode keluar, keluaran biasa, keluaran error)
    private fun run(cmd: Array<String>): Triple<Int, String, String> {
        val p = start(cmd)
        var err = ""
        val t = Thread {
            try {
                err = p.errorStream.bufferedReader().readText()
            } catch (e: Throwable) {
            }
        }
        t.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        t.join(1000)
        return Triple(code, out.trim(), err.trim())
    }

    private fun short(s: String): String {
        val t = s.replace("\n", " ").trim()
        if (t.isEmpty()) return "(kosong)"
        return if (t.length > 160) t.substring(0, 160) + "..." else t
    }

    // ---------------------------------------------------------------
    // Baca server dari logcat (tanpa layar)
    // ---------------------------------------------------------------

    // Cari nomor proses (PID) utama sebuah aplikasi.
    // Hasil: (pid atau null, keterangan kalau gagal)
    fun pidOf(pkg: String): Pair<Int?, String> {
        if (!hasPermission()) return Pair(null, "Shizuku belum diizinkan")
        if (!validPkg(pkg)) return Pair(null, "nama paket tidak valid")
        return try {
            val r = run(arrayOf("pidof", pkg))
            val pid = Regex("\\d+").find(r.second)?.value?.toIntOrNull()
            if (pid != null) Pair(pid, "")
            else Pair(null, "pidof kode=${r.first} keluaran=[${short(r.second)}] err=[${short(r.third)}]")
        } catch (e: Throwable) {
            val root = e.cause ?: e
            Pair(null, "error pidof: ${root.javaClass.simpleName} ${root.message ?: ""}")
        }
    }

    // Pembaca log yang mengalir (streaming) untuk SATU proses (PID).
    // Satu proses logcat jalan terus; tiap baris dibaca begitu muncul.
    // Hanya baris "ZoneID = N" dengan N di rentang 57092..57115 yang dicatat.
    // Baris lain langsung dibuang, hanya dihitung jumlahnya.
    class ZoneStream(private val p: Process, val pid: Int) {
        @Volatile var zone: Int? = null
        @Volatile var foundUptime = 0L
        @Volatile var logTime = ""
        @Volatile var lines = 0
        @Volatile var outOfRange = ""
        @Volatile var ended = false

        init {
            val t = Thread {
                try {
                    val r = p.inputStream.bufferedReader()
                    while (true) {
                        val line = r.readLine() ?: break
                        lines++
                        if (zone != null) continue
                        val m = ZONE.find(line) ?: continue
                        val v = m.groupValues[1].toInt()
                        if (v in 57092..57115) {
                            logTime = LOG_TIME.find(line.trim())?.groupValues?.get(1) ?: ""
                            foundUptime = SystemClock.uptimeMillis()
                            zone = v
                        } else {
                            outOfRange = v.toString()
                        }
                    }
                } catch (e: Throwable) {
                }
                ended = true
            }
            t.isDaemon = true
            t.start()
        }

        // Hentikan proses logcat
        fun close() {
            try {
                p.destroy()
            } catch (e: Throwable) {
            }
            try {
                p.inputStream.close()
            } catch (e: Throwable) {
            }
        }
    }

    // Buka pembaca log mengalir untuk satu PID.
    // Hasil: (pembaca atau null, keterangan kalau gagal)
    fun openZoneStream(pid: Int): Pair<ZoneStream?, String> {
        if (!hasPermission()) return Pair(null, "Shizuku belum diizinkan")
        return try {
            val p = start(arrayOf("sh", "-c", "exec logcat -v threadtime --pid=$pid 2>&1"))
            Pair(ZoneStream(p, pid), "")
        } catch (e: Throwable) {
            val root = e.cause ?: e
            Pair(null, "error buka logcat: ${root.javaClass.simpleName} ${root.message ?: ""}")
        }
    }

    // Cari angka dari pesan "ZoneID = N". Hanya angka di rentang
    // 57092..57115 yang diterima; kalau ada beberapa, diambil yang terakhir.
    // Hasil: (angka valid atau null, catatan)
    private fun pickZone(text: String): Pair<Int?, String> {
        var valid: Int? = null
        var other = ""
        var n = 0
        for (m in ZONE.findAll(text)) {
            n++
            val v = m.groupValues[1].toInt()
            if (v in 57092..57115) valid = v else other = v.toString()
        }
        val note = if (valid == null && n > 0) "ada ZoneID di luar rentang ($other)" else ""
        return Pair(valid, note)
    }

    // Cadangan (dipakai kalau PID tidak ketemu): baca logcat satu kali.
    // - pid ada: hanya log milik proses itu.
    // - pid null: log sejak waktu sinceMs (belum teruji).
    // Hanya baris yang mengandung "ZoneID" yang dibawa ke aplikasi.
    // Hasil: (angka atau null, keterangan untuk log)
    fun readZoneId(pid: Int?, sinceMs: Long): Pair<Int?, String> {
        if (!hasPermission()) return Pair(null, "Shizuku belum diizinkan")
        val cmd: String
        val mode: String
        if (pid != null) {
            cmd = "L=\$(logcat -d --pid=$pid 2>&1); " +
                "echo \"#N=\$(echo \"\$L\" | grep -c .)\"; " +
                "echo \"\$L\" | grep ZoneID"
            mode = "pid=$pid"
        } else {
            val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
            cmd = "logcat -d -T '$t' | grep ZoneID"
            mode = "sejak $t"
        }
        val t0 = System.nanoTime()
        return try {
            val r = run(arrayOf("sh", "-c", cmd))
            val ms = (System.nanoTime() - t0) / 1_000_000

            var body = r.second
            var count = ""
            if (pid != null) {
                val nl = body.indexOf('\n')
                val first = if (nl < 0) body else body.substring(0, nl)
                if (first.startsWith("#N=")) {
                    count = first.substring(3).trim()
                    body = if (nl < 0) "" else body.substring(nl + 1)
                }
            }
            val extra = "baca $ms ms" + (if (count.isEmpty()) "" else ", $count baris log")

            val hit = pickZone(body)
            if (hit.first != null) {
                val line = body.lines().lastOrNull { ZONE.containsMatchIn(it) }
                val jam = if (line == null) null else LOG_TIME.find(line.trim())?.groupValues?.get(1)
                val jamNote = if (jam == null) "" else ", pesan jam $jam"
                Pair(hit.first, "$mode, $extra$jamNote")
            } else {
                val err = if (r.third.isEmpty()) "" else " err=[${short(r.third)}]"
                val note = if (hit.second.isEmpty()) "" else ", ${hit.second}"
                Pair(null, "$mode: belum ada ZoneID valid (kode=${r.first}$note, $extra)$err")
            }
        } catch (e: Throwable) {
            val root = e.cause ?: e
            Pair(null, "error logcat: ${root.javaClass.simpleName} ${root.message ?: ""}")
        }
    }

    // Hitung jumlah baris log milik sebuah proses (untuk diagnosa kalau gagal).
    fun logLineCount(pid: Int): String {
        if (!hasPermission()) return "Shizuku belum diizinkan"
        return try {
            val r = run(arrayOf("sh", "-c", "logcat -d --pid=$pid | wc -l"))
            val n = r.second.trim()
            if (r.first == 0 && n.isNotEmpty()) "$n baris" else "gagal kode=${r.first} err=[${short(r.third)}]"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "error: ${root.javaClass.simpleName} ${root.message ?: ""}"
        }
    }

    // ---------------------------------------------------------------
    // Bersihkan aplikasi (tanpa animasi)
    // ---------------------------------------------------------------

    // Force stop satu aplikasi. Hanya untuk aplikasi yang dicentang.
    fun forceStop(pkg: String): Boolean {
        if (!hasPermission()) return false
        if (!validPkg(pkg)) return false
        return try {
            run(arrayOf("am", "force-stop", pkg)).first == 0
        } catch (e: Throwable) {
            false
        }
    }

    // Hapus kartu aplikasi dari recent apps.
    // Hasil: (berhasil, keterangan untuk log)
    fun removeFromRecents(pkg: String): Pair<Boolean, String> {
        if (!hasPermission()) return Pair(false, "Shizuku belum diizinkan")
        if (!validPkg(pkg)) return Pair(false, "nama paket tidak valid")
        var step = "mulai"
        return try {
            bypass()
            step = "ambil layanan"
            val raw: IBinder = SystemServiceHelper.getSystemService("activity_task")
                ?: return Pair(false, "layanan activity_task tidak ada")
            val wrapped = ShizukuBinderWrapper(raw)

            step = "buat antarmuka"
            val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val atm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            val cls = Class.forName("android.app.IActivityTaskManager")

            step = "baca daftar recent"
            val int = Int::class.javaPrimitiveType
            val slice = cls.getMethod("getRecentTasks", int, int, int).invoke(atm, 100, 0, 0)
                ?: return Pair(false, "daftar recent kosong")
            val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>

            step = "cari kartu"
            val ids = ArrayList<Int>()
            for (info in list) {
                if (info == null) continue
                val c = info.javaClass
                val taskId = c.getField("taskId").getInt(info)
                var p: String? = null
                try {
                    p = (c.getField("baseIntent").get(info) as? Intent)?.component?.packageName
                } catch (e: Throwable) {
                }
                if (p == null) {
                    try {
                        p = (c.getField("topActivity").get(info) as? ComponentName)?.packageName
                    } catch (e: Throwable) {
                    }
                }
                if (p == pkg) ids.add(taskId)
            }
            if (ids.isEmpty()) return Pair(true, "kartu tidak ada di recent (sudah bersih)")

            step = "hapus kartu"
            val remove = cls.getMethod("removeTask", int)
            var n = 0
            for (id in ids) {
                if (remove.invoke(atm, id) as Boolean) n++
            }
            Pair(n > 0, "$n dari ${ids.size} kartu dihapus")
        } catch (e: Throwable) {
            val root = e.cause ?: e
            Pair(false, "gagal di langkah '$step': ${root.javaClass.simpleName} ${root.message ?: ""}")
        }
    }
}
