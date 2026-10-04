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
        @Volatile var lastReason: String = ""

        // Keterangan baca teks terakhir yang berhasil (mode, arah, lama)
        @Volatile var lastOcrInfo: String = ""

        // Arah putar yang terakhir berhasil, dicoba duluan berikutnya
        @Volatile var bestAngle: Int = 0

        // Android membatasi screenshot aksesibilitas sekitar 1 per detik
        private const val MIN_GAP = 1100L

        // Sisi terpanjang gambar mode layar penuh (cadangan)
        private const val MAX_SIDE = 1000

        // Server valid
        private const val MIN_SERVER = 57092
        private const val MAX_SERVER = 57115

        private val DEFAULT_ANGLES = intArrayOf(0, 90, 270, 180)

        private val recognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        fun isReady(): Boolean = instance != null

        // Hanya aplikasi yang dicentang yang boleh dibaca
        fun updateAllowed(pkgs: Set<String>) {
            allowed = pkgs
            instance?.applyFilter()
        }

        // Hanya menerima angka di rentang server yang valid
        fun pickServer(text: String): Int? {
            val a = Regex("Zone\\s*\\w{0,3}\\s*[=:]?\\s*(\\d{5})", RegexOption.IGNORE_CASE).find(text)
            if (a != null) {
                val n = a.groupValues[1].toInt()
                if (n in MIN_SERVER..MAX_SERVER) return n
            }
            for (m in Regex("\\d{5}").findAll(text)) {
                val n = m.value.toInt()
                if (n in MIN_SERVER..MAX_SERVER) return n
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

        // Pita sempit di sekitar baris "ZoneID" (kira-kira 40% lebar x 18% tinggi
        // di tengah layar), resolusi asli tidak dikecilkan.
        private fun cropBand(src: Bitmap): Bitmap {
            val x = (src.width * 0.30f).toInt()
            val y = (src.height * 0.39f).toInt()
            val w = (src.width * 0.40f).toInt()
            val h = (src.height * 0.18f).toInt()
            if (w < 50 || h < 30 || x + w > src.width || y + h > src.height) return src
            return Bitmap.createBitmap(src, x, y, w, h)
        }

        // Mode layar penuh (cadangan): kecilkan saja
        private fun shrink(src: Bitmap): Bitmap {
            val big = maxOf(src.width, src.height)
            if (big <= MAX_SIDE) return src
            val f = MAX_SIDE.toFloat() / big
            val w = maxOf(1, (src.width * f).toInt())
            val h = maxOf(1, (src.height * f).toInt())
            return Bitmap.createScaledBitmap(src, w, h, true)
        }

        private fun angleOrder(): IntArray {
            val b = bestAngle
            val list = ArrayList<Int>()
            list.add(b)
            for (a in DEFAULT_ANGLES) {
                if (a != b) list.add(a)
            }
            return list.toIntArray()
        }

        // OCR di beberapa arah. Kalau hasil sebuah arah KOSONG, berhenti
        // (tidak ada teks di gambar, percuma memutar).
        private fun ocr(
            bmp: Bitmap, order: IntArray, idx: Int, seen: StringBuilder,
            tag: String, callback: (Int?) -> Unit
        ) {
            if (idx >= order.size) {
                lastReason = "OCR $tag jalan di ${order.size} arah, server tidak ketemu. Teks terbaca:" +
                    shortN(seen.toString(), 200)
                callback(null)
                return
            }
            val deg = order[idx]
            val t0 = SystemClock.uptimeMillis()
            lastReason = "OCR $tag sedang di arah $deg (gambar ${bmp.width}x${bmp.height})" +
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
                            lastOcrInfo = "$tag, arah $deg, $ms ms"
                            lastReason = ""
                            callback(s)
                        } else {
                            val txt = it.text.trim()
                            seen.append(" [").append(tag).append(" arah ").append(deg).append(", ")
                                .append(ms).append(" ms] ").append(shortN(it.text, 80))
                            if (txt.length < 3) {
                                lastReason = "OCR $tag: tidak ada teks di gambar." +
                                    shortN(seen.toString(), 200)
                                callback(null)
                            } else {
                                ocr(bmp, order, idx + 1, seen, tag, callback)
                            }
                        }
                    }
                    .addOnFailureListener {
                        lastReason = "OCR error: " + (it.message ?: "?")
                        callback(null)
                    }
                    .addOnCompleteListener {
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
        // full = false: hanya pita sempit, resolusi asli (cepat).
        // full = true : seluruh layar, dikecilkan (cadangan).
        fun ocrBitmap(bmp: Bitmap, full: Boolean, callback: (Int?) -> Unit) {
            val s0 = SystemClock.uptimeMillis()
            val work = try {
                if (full) shrink(bmp) else cropBand(bmp)
            } catch (e: Throwable) {
                bmp
            }
            if (work !== bmp) {
                try {
                    bmp.recycle()
                } catch (e: Throwable) {
                }
            }
            val tag = if (full) "penuh" else "pita"
            val seen = StringBuilder()
            seen.append("(siapkan ").append(SystemClock.uptimeMillis() - s0).append(" ms)")
            ocr(work, angleOrder(), 0, seen, tag) { r ->
                try {
                    if (!work.isRecycled) work.recycle()
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
                    ocrBitmap(bmp, true, callback)
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
