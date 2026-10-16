package com.ats_tsalatsah.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RunnerService.initStore(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            // Kotak konfirmasi dari halaman (confirm)
            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult
            ): Boolean {
                if (isFinishing) {
                    result.cancel()
                    return true
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("Ya") { _, _ -> result.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            // Pesan peringatan dari halaman (alert)
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult
            ): Boolean {
                if (isFinishing) {
                    result.confirm()
                    return true
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "App")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun open(i: Intent) {
        runOnUiThread {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {
            }
        }
    }

    inner class Bridge {

        // Daftar aplikasi terpasang: [{name, pkg}]
        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            val arr = JSONArray()
            for (p in list) {
                val o = JSONObject()
                o.put("name", p.first)
                o.put("pkg", p.second)
                arr.put(o)
            }
            return arr.toString()
        }

        // pkgsJson = ["com.a","com.b"], d1, d2, d3 dalam milidetik
        // tapOn = saklar ketukan, tapMs = waktu ketukan (ms), tapX/tapY = posisi ketukan
        @JavascriptInterface
        fun start(
            pkgsJson: String, d1: String, d2: String, d3: String,
            target: String, back: Boolean,
            tapOn: Boolean, tapMs: String, tapX: String, tapY: String
        ) {
            val arr = JSONArray(pkgsJson)
            val pkgs = Array(arr.length()) { arr.getString(it) }
            val i = Intent(this@MainActivity, RunnerService::class.java)
            i.putExtra("pkgs", pkgs)
            i.putExtra("d1", d1.toLongOrNull() ?: 7450L)
            i.putExtra("d2", d2.toLongOrNull() ?: 6500L)
            i.putExtra("d3", d3.toLongOrNull() ?: 600L)
            i.putExtra("target", target.toIntOrNull() ?: 0)
            i.putExtra("back", back)
            i.putExtra("tapOn", tapOn)
            i.putExtra("tapMs", tapMs.toLongOrNull() ?: 3000L)
            i.putExtra("tapX", tapX.toIntOrNull() ?: 1200)
            i.putExtra("tapY", tapY.toIntOrNull() ?: 960)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }

        // Tes ketuk: hitung mundur 5 detik lalu satu ketukan ke (x, y).
        // Pakai startService (bukan startForegroundService) karena
        // aksi ini tidak memanggil startForeground di RunnerService.
        @JavascriptInterface
        fun tapTest(x: String, y: String) {
            val i = Intent(this@MainActivity, RunnerService::class.java)
            i.action = "TAP_TEST"
            i.putExtra("tapX", x.toIntOrNull() ?: 1200)
            i.putExtra("tapY", y.toIntOrNull() ?: 960)
            startService(i)
        }

        @JavascriptInterface
        fun stop() {
            val i = Intent(this@MainActivity, RunnerService::class.java)
            i.action = "STOP"
            startService(i)
        }

        // Hapus log + daftar server. Return false kalau sedang berjalan.
        @JavascriptInterface
        fun clearLogs(): Boolean = RunnerService.clearAll()

        // Hapus hanya sesi terakhir
        @JavascriptInterface
        fun clearLast(): Boolean = RunnerService.clearLastSession()

        // Hapus satu sesi arsip (nomor mulai dari 0)
        @JavascriptInterface
        fun deleteSession(n: Int): Boolean = RunnerService.deleteSession(n)

        // Hapus semua arsip
        @JavascriptInterface
        fun clearArchive(): Boolean = RunnerService.clearArchive()

        // Hapus hanya daftar server
        @JavascriptInterface
        fun clearServers(): Boolean = RunnerService.clearServers()

        // Status jalan + log + server + status izin
        @JavascriptInterface
        fun status(): String {
            val o = JSONObject(RunnerService.statusJson())
            o.put("overlay", Settings.canDrawOverlays(this@MainActivity))
            o.put("shizukuRun", ShizukuHelper.isRunning())
            o.put("shizuku", ShizukuHelper.hasPermission())
            return o.toString()
        }

        // Buka setelan izin "Tampil di atas aplikasi lain"
        @JavascriptInterface
        fun overlay() {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        // Minta izin Shizuku
        @JavascriptInterface
        fun shizuku() {
            ShizukuHelper.requestPermission()
        }

        // Buka setelan penghemat baterai
        @JavascriptInterface
        fun battery() {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
