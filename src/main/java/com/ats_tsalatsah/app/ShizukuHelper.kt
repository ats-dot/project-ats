package com.ats_tsalatsah.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
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

    // Baca logcat satu kali dan cari "ZoneID = N".
    // - pid ada: hanya log milik proses itu (aplikasi yang dicentang).
    // - pid null: cadangan, log sejak waktu sinceMs (belum teruji).
    // Hanya baris yang mengandung "ZoneID" yang dibawa ke aplikasi.
    // Hasil: (angka atau null, keterangan untuk log)
    fun readZoneId(pid: Int?, sinceMs: Long): Pair<Int?, String> {
        if (!hasPermission()) return Pair(null, "Shizuku belum diizinkan")
        val cmd: String
        val mode: String
        if (pid != null) {
            cmd = "logcat -d --pid=$pid | grep ZoneID"
            mode = "pid=$pid"
        } else {
            val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
            cmd = "logcat -d -T '$t' | grep ZoneID"
            mode = "sejak $t"
        }
        return try {
            val r = run(arrayOf("sh", "-c", cmd))
            val hit = pickZone(r.second)
            if (hit.first != null) {
                Pair(hit.first, mode)
            } else {
                val err = if (r.third.isEmpty()) "" else " err=[${short(r.third)}]"
                val note = if (hit.second.isEmpty()) "" else ", ${hit.second}"
                Pair(null, "$mode: belum ada ZoneID valid (kode=${r.first}$note)$err")
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
