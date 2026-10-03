package com.ats_tsalatsah.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
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

        // Alasan kegagalan baca terakhir, dibaca RunnerService untuk log
        @Volatile var lastReason: String = ""

        // Android membatasi screenshot sekitar 1 per detik
        private const val MIN_GAP = 1100L

        fun isReady(): Boolean = instance != null

        // Hanya aplikasi yang dicentang yang boleh dibaca
        fun updateAllowed(pkgs: Set<String>) {
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

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastShot = 0L

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

    private fun foregroundPackage(): String {
        return rootInActiveWindow?.packageName?.toString() ?: lastPackage
    }

    private fun short(s: String): String {
        val t = s.replace("\n", " ").trim()
        if (t.isEmpty()) return "(kosong)"
        return if (t.length > 90) t.substring(0, 90) + "..." else t
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        val m = Matrix()
        m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // Coba OCR di 4 arah putar sampai server ketemu
    private fun ocr(bmp: Bitmap, deg: Int, seen: StringBuilder, callback: (Int?) -> Unit) {
        if (deg >= 360) {
            lastReason = "OCR jalan di 4 arah, server tidak ketemu. Teks terbaca: " + short(seen.toString())
            callback(null)
            return
        }
        val img = InputImage.fromBitmap(rotate(bmp, deg), 0)
        recognizer.process(img)
            .addOnSuccessListener {
                val s = pickServer(it.text)
                if (s != null) {
                    lastReason = ""
                    callback(s)
                } else {
                    if (seen.length < 200) seen.append(" [").append(deg).append("] ").append(it.text)
                    ocr(bmp, deg + 90, seen, callback)
                }
            }
            .addOnFailureListener {
                lastReason = "OCR error: " + (it.message ?: "?")
                callback(null)
            }
    }

    // Baca server dari layar (screenshot + OCR).
    // Hanya jalan kalau layar depan = aplikasi yang dicentang.
    fun readServer(callback: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            lastReason = "Android di bawah 11, screenshot tidak didukung"
            callback(null)
            return
        }
        val pkg = foregroundPackage()
        if (pkg !in allowed) {
            lastReason = "ditolak filter: layar depan '$pkg' bukan aplikasi yang dicentang"
            callback(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastShot < MIN_GAP) {
            lastReason = "menunggu jarak antar screenshot"
            callback(null)
            return
        }
        lastShot = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) {
                        lastReason = "gambar screenshot kosong"
                        callback(null)
                        return
                    }
                    ocr(bmp, 0, StringBuilder(), callback)
                } catch (e: Exception) {
                    lastReason = "error olah gambar: " + (e.message ?: "?")
                    callback(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                val nama = when (errorCode) {
                    1 -> "internal error"
                    2 -> "tidak ada izin screenshot"
                    3 -> "terlalu cepat (batas 1 per detik)"
                    4 -> "layar tidak valid"
                    5 -> "layar diblokir aplikasi (secure)"
                    else -> "kode $errorCode"
                }
                lastReason = "screenshot gagal: $nama"
                callback(null)
            }
        })
    }
}
