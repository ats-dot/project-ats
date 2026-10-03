package com.ats_tsalatsah.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

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

    // Force stop satu aplikasi lewat Shizuku, tanpa animasi.
    // Dipanggil hanya untuk aplikasi yang dicentang.
    fun forceStop(pkg: String): Boolean {
        if (!hasPermission()) return false
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return false
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val cmd = arrayOf("am", "force-stop", pkg)
            val p = m.invoke(null, cmd, null as Array<String>?, null as String?) as Process
            p.waitFor() == 0
        } catch (e: Throwable) {
            false
        }
    }
}
