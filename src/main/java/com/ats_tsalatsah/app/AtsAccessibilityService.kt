package com.ats_tsalatsah.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class AtsAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AtsAccessibilityService? = null
        @Volatile var allowed: Set<String> = emptySet()
        @Volatile var lastPackage: String = ""

        fun isReady(): Boolean = instance != null

        // Hanya aplikasi yang dicentang yang boleh dibaca
        fun setAllowed(pkgs: Set<String>) {
            allowed = pkgs
            instance?.applyFilter()
        }

        fun pickServer(text: String): Int? {
            val a = Regex("Zone\\s*\\w{0,3}\\s*[=:]?\\s*(\\d{5})", RegexOption.IGNORE_CASE).find(text)
            if (a != null) return a.groupValues[1].toInt()
            for (m in Regex("\\d{5}").findAll(text)) {
                val n = m.value.toInt()
                if (n in 57092..57115) return n
            }
            return null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyFilter()
    }

    private fun applyFilter() {
        val info = serviceInfo ?: return
        info.packageNames = if (allowed.isEmpty()) arrayOf("none.none") else allowed.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun foregroundAllowed(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: lastPackage
        return pkg in allowed
    }

    // Baca server dari layar (screenshot + OCR). Hanya jalan kalau layar depan = aplikasi yang dicentang.
    fun readServer(callback: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || !foregroundAllowed()) {
            callback(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) {
                        callback(null)
                        return
                    }
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { callback(pickServer(it.text)) }
                        .addOnFailureListener { callback(null) }
                } catch (e: Exception) {
                    callback(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                callback(null)
            }
        })
    }

    // Buang aplikasi di layar depan dari recent apps (hanya kalau aplikasi itu dicentang)
    fun dismissFromRecents(done: () -> Unit) {
        if (!foregroundAllowed()) {
            done()
            return
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        handler.postDelayed({
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            val path = Path().apply {
                moveTo(w / 2f, h * 0.55f)
                lineTo(w / 2f, h * 0.05f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
                .build()
            val finish = {
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    done()
                }, 250)
                Unit
            }
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { finish() }
                override fun onCancelled(g: GestureDescription?) { finish() }
            }, null)
            if (!ok) finish()
        }, 700)
    }
}
