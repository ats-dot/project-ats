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

        // Alasan kegagalan baca terakhir, dibaca RunnerService untuk log.
        // Saat OCR masih berjalan, isinya progres (arah yang sedang dicoba).
        @Volatile var lastReason: String = ""

        // Keterangan baca teks terakhir yang berhasil (arah dan lama)
        @Volatile var lastOcrInfo: String = ""

        // Arah putar yang terakhir berhasil, dicoba duluan berikutnya
        @Volatile var bestAngle: Int = 0

        // Android membatasi screenshot aksesibilitas sekitar 1 per detik
        private const val MIN_GAP = 1100L

        // Urutan arah putar OCR. Gambar layar mendatar biasanya sudah tegak (0).
        private val DEFAULT_ANGLES = intArrayOf(0, 90, 270, 180)

        // Pembaca teks. Ada di sini (bukan di layanan) supaya tetap bisa
        // dipakai walau layanan aksesibilitas sedang mati.
        private val recognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

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

        private fun shortN(s: String, n: Int): String {
            val t = s.replace("\n", " ").trim()
            if (t.isEmpty()) return "(kosong)"
            return if (t.length > n) t.substring(0, n) + "..." else t
        }

        private fun rotate(src: Bitmap, deg: Int): Bitmap {
            if (deg == 0) return src
            val m = Matrix()
            m.postRotate(deg.toFloat())
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        // Arah yang terakhir berhasil dicoba duluan, lalu sisanya berurutan
        private fun angleOrder(): IntArray {
            val b = bestAngle
            val list = ArrayList<Int>()
            list.add(b)
            for (a in DEFAULT_ANGLES) {
                if (a != b) list.add(a)
            }
            return list.toIntArray()
        }

        // Coba OCR di beberapa arah putar sampai server ketemu.
        // Progres dicatat di lastReason supaya kelihatan walau kena batas waktu.
        private fun ocr(bmp: Bitmap, order: IntArray, idx: Int, seen: StringBuilder, callback: (Int?) -> Unit) {
            if (idx >= order.size) {
                lastReason = "OCR jalan di ${order.size} arah, server tidak ketemu. Teks terbaca:" +
                    shortN(seen.toString(), 200)
                callback(null)
                return
            }
            val deg = order[idx]
            val t0 = SystemClock.uptimeMillis()
            lastReason = "OCR sedang di arah $deg" +
                (if (seen.isNotEmpty()) ", sebelumnya:" + shortN(seen.toString(), 200) else "")

            val rot: Bitmap
            val img: InputImage
            try {
                rot = rotate(bmp, deg)
                img = InputImage.fromBitmap(rot, 0)
            } catch (e: Throwable) {
                lastReason = "error putar gambar: " + (e.message ?: "?")
                callback(null)
                return
            }
            try {
                recognizer.process(img)
                    .addOnSuccessListener {
                        val ms = SystemClock.uptimeMillis() - t0
                        val s = pickServer(it.text)
                        if (s != null) {
                            bestAngle = deg
                            lastOcrInfo = "arah $deg, $ms ms"
                            lastReason = ""
                            callback(s)
                        } else {
                            if (seen.length < 300) {
                                seen.append(" [arah ").append(deg).append(", ").append(ms)
                                    .append(" ms] ").append(shortN(it.text, 80))
                            }
                            ocr(bmp, order, idx + 1, seen, callback)
                        }
                    }
                    .addOnFailureListener {
                        lastReason = "OCR error: " + (it.message ?: "?")
                        callback(null)
                    }
                    .addOnCompleteListener {
                        // Buang salinan putaran (bukan gambar asli)
                        if (rot !== bmp) {
                            try {
                                rot.recycle()
                            } catch (e: Throwable) {
                            }
                        }
                    }
            } catch (e: Throwable) {
                lastReason = "OCR tidak bisa dijalankan: " + (e.message ?: "?")
                callback(null)
            }
        }

        // Baca server dari sebuah gambar (mis. dari screencap Shizuku).
        // Gambar diambil alih dan dibebaskan sendiri setelah selesai.
        fun ocrBitmap(bmp: Bitmap, callback: (Int?) -> Unit) {
            ocr(bmp, angleOrder(), 0, StringBuilder()) { r ->
                try {
                    if (!bmp.isRecycled) bmp.recycle()
                } catch (e: Throwable) {
                }
                callback(r)
            }
        }
    }

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

    // Jalur cadangan: baca server lewat screenshot aksesibilitas + OCR.
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
                    try {
                        hw?.recycle()
                    } catch (e: Throwable) {
                    }
                    result.hardwareBuffer.close()
                    if (bmp == null) {
                        lastReason = "gambar screenshot kosong"
                        callback(null)
                        return
                    }
                    ocrBitmap(bmp, callback)
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
