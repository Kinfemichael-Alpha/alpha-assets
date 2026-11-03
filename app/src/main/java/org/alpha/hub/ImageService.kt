package org.alpha.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ImageService(private val ctx: Context, private val msg: (String) -> String) {
    private val file = File(ctx.cacheDir, "chat_image.jpg")
    private val shot = File(ctx.cacheDir, "camera_shot.jpg")
    private val ocrFile = File(ctx.cacheDir, "chat_image_ocr.jpg")
    private val tessRoot = File(ctx.filesDir, "ocr")
    private val maxSide = 1600
    private val ocrSide = 3000

    fun cameraUri(): Uri {
        if (shot.exists()) shot.delete()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot)
    }

    private fun fail(key: String): JSONObject = JSONObject().put("ok", false).put("message", msg(key))

    private fun encode(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun prepare(uri: Uri): JSONObject {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return fail("image_failed")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return fail("image_failed")
            var degrees = 0
            try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    val ex = android.media.ExifInterface(s)
                    degrees = when (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (_: Exception) { }
            if (degrees != 0) {
                val rm = Matrix(); rm.postRotate(degrees.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rm, true)
            }
            val big = maxOf(bmp.width, bmp.height)
            val ocrBmp = if (big > ocrSide) Bitmap.createScaledBitmap(bmp, (bmp.width * ocrSide.toFloat() / big).toInt().coerceAtLeast(1), (bmp.height * ocrSide.toFloat() / big).toInt().coerceAtLeast(1), true) else bmp
            ocrFile.writeBytes(encode(ocrBmp, 92))
            val scale = maxSide.toFloat() / big
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            file.writeBytes(encode(bmp, 85))
            val ts = 240f / maxOf(bmp.width, bmp.height)
            val thumb = if (ts < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ts).toInt().coerceAtLeast(1), (bmp.height * ts).toInt().coerceAtLeast(1), true) else bmp
            if (shot.exists()) shot.delete()
            return JSONObject().put("ok", true).put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(encode(thumb, 70), Base64.NO_WRAP))
        } catch (_: OutOfMemoryError) { return fail("image_failed") }
        catch (_: Exception) { return fail("image_failed") }
    }

    fun has(): Boolean = file.exists()

    fun state(): JSONObject {
        if (!file.exists()) return JSONObject().put("has", false)
        val bmp = BitmapFactory.decodeFile(file.path) ?: return JSONObject().put("has", false)
        val ts = 240f / maxOf(bmp.width, bmp.height)
        val thumb = if (ts < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ts).toInt().coerceAtLeast(1), (bmp.height * ts).toInt().coerceAtLeast(1), true) else bmp
        return JSONObject().put("has", true).put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(encode(thumb, 70), Base64.NO_WRAP))
    }

    fun clear() { if (file.exists()) file.delete(); if (shot.exists()) shot.delete(); if (ocrFile.exists()) ocrFile.delete() }

    fun base64(): String? = if (file.exists()) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else null

    fun restore(b64: String) { try { file.writeBytes(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { } }

    private fun ensureLang(lang: String, onPhase: (String) -> Unit) {
        val dir = File(tessRoot, "tessdata").apply { mkdirs() }
        val f = File(dir, "$lang.traineddata")
        if (f.exists() && f.length() > 100000) return
        onPhase(msg("ocr_downloading"))
        val tmp = File(dir, "$lang.traineddata.part")
        var c: HttpURLConnection? = null
        try {
            c = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$lang.traineddata").openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000
            if (c.responseCode != 200) throw ApiError(502, "ocr_download_failed")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() < 100000) throw ApiError(502, "ocr_download_failed")
            tmp.renameTo(f)
        } catch (e: ApiError) { tmp.delete(); throw e }
        catch (_: java.io.IOException) { tmp.delete(); throw ApiError(502, "ocr_download_failed") }
        finally { c?.disconnect() }
    }

    private fun flatten(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = ByteArray(w * h)
        var sum = 0L
        for (i in px.indices) {
            val p = px[i]
            val g = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            gray[i] = g.toByte(); sum += g
        }
        val invert = sum / (w * h) < 105
        val cell = maxOf(24, minOf(w, h) / 20)
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val grid = IntArray(gw * gh)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            var mx = 0
            val y1 = minOf(h, (cy + 1) * cell); val x1 = minOf(w, (cx + 1) * cell)
            var y = cy * cell
            while (y < y1) {
                var x = cx * cell
                while (x < x1) {
                    var g = gray[y * w + x].toInt() and 0xFF
                    if (invert) g = 255 - g
                    if (g > mx) mx = g
                    x += 2
                }
                y += 2
            }
            grid[cy * gw + cx] = mx
        }
        val sm = IntArray(gw * gh)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            var t = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = cy + dy; val xx = cx + dx
                if (yy in 0 until gh && xx in 0 until gw) { t += grid[yy * gw + xx]; n++ }
            }
            sm[cy * gw + cx] = t / n
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(gh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(gw - 1, x0 + 1); val tx = fx - x0
                val top = sm[y0 * gw + x0] * (1 - tx) + sm[y0 * gw + x1] * tx
                val bot = sm[y1 * gw + x0] * (1 - tx) + sm[y1 * gw + x1] * tx
                val bg = maxOf(40f, top * (1 - ty) + bot * ty)
                var g = gray[y * w + x].toInt() and 0xFF
                if (invert) g = 255 - g
                val v = minOf(255, (g * 255f / bg).toInt())
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun scaleTo(src: Bitmap, side: Int): Bitmap {
        val big = maxOf(src.width, src.height)
        if (side == big) return src
        var cur = src
        var cw = src.width.toFloat(); var ch = src.height.toFloat()
        val f = side.toFloat() / big
        val tw = (src.width * f).toInt().coerceAtLeast(1); val th = (src.height * f).toInt().coerceAtLeast(1)
        while (f < 1f && cw / 2 > tw) {
            cw /= 2; ch /= 2
            val n = Bitmap.createScaledBitmap(cur, cw.toInt().coerceAtLeast(1), ch.toInt().coerceAtLeast(1), true)
            if (cur !== src) cur.recycle()
            cur = n
        }
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    fun ocr(amharic: Boolean, onPhase: (String) -> Unit): JSONObject {
        if (!file.exists()) throw ApiError(400, "image_failed")
        val langs = if (amharic) listOf("eng", "amh") else listOf("eng")
        for (l in langs) ensureLang(l, onPhase)
        onPhase(msg("ocr_reading"))
        val src = BitmapFactory.decodeFile((if (ocrFile.exists()) ocrFile else file).path) ?: throw ApiError(400, "image_failed")
        val api = TessBaseAPI()
        try {
            if (!api.init(tessRoot.absolutePath, langs.joinToString("+"))) throw ApiError(500, "ocr_failed")
            val base = flatten(src)
            src.recycle()
            val long = maxOf(base.width, base.height)
            var best = ""
            var bestScore = -1.0
            val done = HashSet<Int>()
            fun pass(side: Int, mode: Int) {
                val target = minOf(side, long * 2)
                if (!done.add(target * 10 + mode)) return
                val bmp = scaleTo(base, target)
                api.setPageSegMode(mode)
                api.setImage(bmp)
                val raw = api.getUTF8Text() ?: ""
                val conf = api.meanConfidence()
                api.clear()
                if (bmp !== base) bmp.recycle()
                val alnum = raw.count { it.isLetterOrDigit() }
                val score = conf * Math.sqrt(alnum.toDouble())
                if (score > bestScore) { best = raw; bestScore = score }
            }
            pass(2200, TessBaseAPI.PageSegMode.PSM_AUTO)
            pass(1200, TessBaseAPI.PageSegMode.PSM_AUTO)
            if (bestScore < 70.0 * Math.sqrt(40.0)) pass(700, TessBaseAPI.PageSegMode.PSM_AUTO)
            if (best.count { it.isLetterOrDigit() } < 3) pass(1500, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            val text = best.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            if (text.length < 2) throw ApiError(422, "ocr_empty")
            return JSONObject().put("text", text.take(3500))
        } catch (e: ApiError) { throw e }
        catch (_: Throwable) { throw ApiError(500, "ocr_failed") }
        finally { try { api.recycle() } catch (_: Throwable) { } }
    }
}
