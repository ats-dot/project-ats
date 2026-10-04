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

    // Awal baris logcat (format threadtime): "MM-dd HH:mm:ss.SSS  PID  TID L ..."
    private val LOG_HEAD = Regex("^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s")

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

    // ---------------------------------------------------------------
    // Baca server dari logcat berdasarkan UID aplikasi
    // ---------------------------------------------------------------

    // Pembaca log yang mengalir (streaming) untuk SATU aplikasi (UID).
    // Satu proses logcat jalan terus; tiap baris dibaca begitu muncul.
    // Hanya baris "ZoneID = N" dengan N di rentang 57092..57115 yang dicatat.
    // Baris lain langsung dibuang, hanya dihitung jumlahnya.
    class ZoneStream(private val p: Process, val uid: Int) {
        @Volatile var zone: Int? = null
        @Volatile var foundUptime = 0L
        @Volatile var logTime = ""
        @Volatile var linePid = ""
        @Volatile var lines = 0
        @Volatile var outOfRange = ""
        @Volatile var head = ""
        @Volatile var pids = ""
        @Volatile var ended = false

        init {
            val t = Thread {
                try {
                    val r = p.inputStream.bufferedReader()
                    val seen = LinkedHashSet<String>()
                    while (true) {
                        val line = r.readLine() ?: break
                        lines++
                        if (lines == 1) {
                            head = line.replace("\n", " ").trim().take(160)
                        }
                        val h = LOG_HEAD.find(line)
                        if (h != null && seen.size < 6 && seen.add(h.groupValues[2])) {
                            pids = seen.joinToString(",")
                        }
                        if (zone != null) continue
                        val m = ZONE.find(line) ?: continue
                        val v = m.groupValues[1].toInt()
                        if (v in 57092..57115) {
                            logTime = h?.groupValues?.get(1) ?: ""
                            linePid = h?.groupValues?.get(2) ?: ""
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

    // Buka pembaca log mengalir untuk satu UID, hanya log sejak sinceMs.
    // Hasil: (pembaca atau null, keterangan kalau gagal)
    fun openUidStream(uid: Int, sinceMs: Long): Pair<ZoneStream?, String> {
        if (!hasPermission()) return Pair(null, "Shizuku belum diizinkan")
        if (uid < 10000) return Pair(null, "UID tidak valid ($uid)")
        return try {
            val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
            val p = start(
                arrayOf("sh", "-c", "exec logcat -v threadtime -T '$t' --uid=$uid 2>&1")
            )
            Pair(ZoneStream(p, uid), "")
        } catch (e: Throwable) {
            val root = e.cause ?: e
            Pair(null, "error buka logcat: ${root.javaClass.simpleName} ${root.message ?: ""}")
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
