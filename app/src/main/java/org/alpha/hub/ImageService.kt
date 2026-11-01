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
    private val tessRoot = File(ctx.filesDir, "ocr")
    private val maxSide = 1600

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
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (degrees != 0) m.postRotate(degrees.toFloat())
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
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

    fun clear() { if (file.exists()) file.delete(); if (shot.exists()) shot.delete() }

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

    fun ocr(amharic: Boolean, onPhase: (String) -> Unit): JSONObject {
        if (!file.exists()) throw ApiError(400, "image_failed")
        val langs = if (amharic) listOf("eng", "amh") else listOf("eng")
        for (l in langs) ensureLang(l, onPhase)
        onPhase(msg("ocr_reading"))
        val bmp = BitmapFactory.decodeFile(file.path) ?: throw ApiError(400, "image_failed")
        val api = TessBaseAPI()
        try {
            if (!api.init(tessRoot.absolutePath, langs.joinToString("+"))) throw ApiError(500, "ocr_failed")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            val raw = api.getUTF8Text() ?: ""
            val text = raw.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            if (text.length < 2) throw ApiError(422, "ocr_empty")
            return JSONObject().put("text", text.take(3500))
        } catch (e: ApiError) { throw e }
        catch (_: Throwable) { throw ApiError(500, "ocr_failed") }
        finally { try { api.recycle() } catch (_: Throwable) { } }
    }
}
