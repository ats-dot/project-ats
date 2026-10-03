package com.ats_tsalatsah.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
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

        // pkgsJson = ["com.a","com.b"], d1 & d2 dalam milidetik
        @JavascriptInterface
        fun start(pkgsJson: String, d1: String, d2: String, target: String, back: Boolean) {
            val arr = JSONArray(pkgsJson)
            val pkgs = Array(arr.length()) { arr.getString(it) }
            val i = Intent(this@MainActivity, RunnerService::class.java)
            i.putExtra("pkgs", pkgs)
            i.putExtra("d1", d1.toLongOrNull() ?: 7450L)
            i.putExtra("d2", d2.toLongOrNull() ?: 6500L)
            i.putExtra("target", target.toIntOrNull() ?: 0)
            i.putExtra("back", back)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }

        @JavascriptInterface
        fun stop() {
            val i = Intent(this@MainActivity, RunnerService::class.java)
            i.action = "STOP"
            startService(i)
        }

        // Status jalan + log + status izin
        @JavascriptInterface
        fun status(): String {
            val o = JSONObject(RunnerService.statusJson())
            o.put("overlay", Settings.canDrawOverlays(this@MainActivity))
            o.put("access", AtsAccessibilityService.isReady())
            o.put("shizukuRun", ShizukuHelper.isRunning())
            o.put("shizuku", ShizukuHelper.hasPermission())
            return o.toString()
        }

        // Buka setelan izin "Tampil di atas aplikasi lain"
        @JavascriptInterface
        fun overlay() {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        // Buka setelan Aksesibilitas
        @JavascriptInterface
        fun access() {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
