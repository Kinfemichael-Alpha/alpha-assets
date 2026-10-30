package org.alpha.hub

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * አንድ ጊዜ አንድ ማውረድ ብቻ (ቀድሞ _DownloadState + downloader.py)።
 * One download at a time with progress, cancel and resume (.part + HTTP Range).
 */
class FileDownloader(private val friendly: (Exception) -> String) {
    @Volatile var state = "idle"        // idle | running | done | error | cancelled
    @Volatile var name = ""
    @Volatile var title = ""
    @Volatile var scope = ""
    @Volatile var pct = 0
    @Volatile var downloaded = 0L
    @Volatile var total = -1L
    @Volatile var error = ""
    private var cancel = AtomicBoolean(false)

    fun isRunning() = state == "running"

    @Synchronized
    fun start(url: String, dest: File, fname: String, ttl: String, kind: String): Boolean {
        if (isRunning()) return false
        state = "running"; name = fname; title = ttl; scope = kind
        pct = 0; downloaded = 0; total = -1; error = ""
        cancel = AtomicBoolean(false)
        val flag = cancel
        Thread { run(url, dest, flag) }.apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun startCopy(open: () -> java.io.InputStream, size: Long, dest: File, fname: String, ttl: String, kind: String): Boolean {
        if (isRunning()) return false
        state = "running"; name = fname; title = ttl; scope = kind
        pct = 0; downloaded = 0; total = if (size > 0) size else -1; error = ""
        cancel = AtomicBoolean(false)
        val flag = cancel
        Thread { runCopy(open, dest, flag) }.apply { isDaemon = true }.start()
        return true
    }

    private fun runCopy(open: () -> java.io.InputStream, dest: File, flag: AtomicBoolean) {
        val part = File(dest.path + ".part")
        try {
            dest.parentFile?.mkdirs()
            open().use { input ->
                FileOutputStream(part, false).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (flag.get()) { part.delete(); state = "cancelled"; return }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) pct = (downloaded * 100 / total).toInt().coerceAtMost(100)
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("rename")
            pct = 100; state = "done"
        } catch (e: Exception) {
            part.delete()
            error = friendly(e)
            state = "error"
        }
    }

    fun cancel() { if (isRunning()) cancel.set(true) }

    private fun run(url: String, dest: File, flag: AtomicBoolean) {
        val part = File(dest.path + ".part")
        try {
            dest.parentFile?.mkdirs()
            val existing = if (part.exists()) part.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AlphaAI/0.1")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            if (code == 416 && existing > 0) {          // አስቀድሞ ተሟልቷል / already complete
                part.renameTo(dest); state = "done"; pct = 100; return
            }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val resume = code == 206
            val len = conn.contentLengthLong
            total = if (len > 0) (if (resume) existing + len else len) else -1
            downloaded = if (resume) existing else 0
            conn.inputStream.use { input ->
                FileOutputStream(part, resume).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (flag.get()) { state = "cancelled"; return }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) pct = (downloaded * 100 / total).toInt()
                    }
                }
            }
            if (total > 0 && downloaded < total) throw IOException("incomplete")
            if (!part.renameTo(dest)) throw IOException("rename")
            pct = 100; state = "done"
        } catch (e: Exception) {
            error = friendly(e)   // ቴክኒካዊ ጽሁፍ ለተጠቃሚ አይታይም / never show raw technical text
            state = "error"
        }
    }

    fun snapshot() = org.json.JSONObject()
        .put("state", state).put("name", name).put("title", title).put("scope", scope)
        .put("pct", pct).put("downloaded", downloaded)
        .put("total", if (total > 0) total else org.json.JSONObject.NULL).put("error", error)
}
