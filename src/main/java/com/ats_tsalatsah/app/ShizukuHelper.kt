package com.ats_tsalatsah.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuHelper {

    private const val SERVICE =
        "com.ats_tsalatsah.app/com.ats_tsalatsah.app.AtsAccessibilityService"

    private var bypassed = false

    private fun bypass() {
        if (bypassed) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Throwable) {
        }
        bypassed = true
    }

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

    // Jalankan perintah lewat Shizuku. Hasil: (kode keluar, teks keluaran)
    private fun run(cmd: Array<String>): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, cmd, null as Array<String>?, null as String?) as Process
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return Pair(code, out.trim())
    }

    // Force stop satu aplikasi, tanpa animasi. Hanya untuk aplikasi yang dicentang.
    fun forceStop(pkg: String): Boolean {
        if (!hasPermission()) return false
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return false
        return try {
            run(arrayOf("am", "force-stop", pkg)).first == 0
        } catch (e: Throwable) {
            false
        }
    }

    // Hapus kartu aplikasi dari recent apps, tanpa animasi.
    // Hasil: (berhasil, keterangan untuk log)
    fun removeFromRecents(pkg: String): Pair<Boolean, String> {
        if (!hasPermission()) return Pair(false, "Shizuku belum diizinkan")
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return Pair(false, "nama paket tidak valid")
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

    // Nyalakan layanan Aksesibilitas Project ats lewat Shizuku
    fun enableAccessibility(): Boolean {
        if (!hasPermission()) return false
        return try {
            val cur = run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).second
            if (!cur.contains(SERVICE)) {
                val v = if (cur.isBlank() || cur == "null") SERVICE else "$cur:$SERVICE"
                run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", v))
            }
            run(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
            true
        } catch (e: Throwable) {
            false
        }
    }
}
