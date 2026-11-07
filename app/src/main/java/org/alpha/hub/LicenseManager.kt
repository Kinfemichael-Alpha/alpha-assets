package org.alpha.hub

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import org.json.JSONObject

class LicenseManager(private val ctx: Context, private val sync: ConfigSync) {
    private val file = File(ctx.filesDir, "license.json")
    private var data = JSONObject()
    private var verified: Triple<String, Long, Int>? = null
    private var pub: PublicKey? = null
    private var lastSave = 0L
    var onWipe: (() -> Unit)? = null

    init {
        try { if (file.exists()) data = JSONObject(file.readText(Charsets.UTF_8)) } catch (_: Exception) { }
    }

    val deviceId: String by lazy {
        val a = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        MessageDigest.getInstance("SHA-256").digest(("alpha-hub:$a").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    @Synchronized
    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(data.toString(), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Exception) { }
    }

    private fun clockOk(now: Long): Boolean {
        val seen = data.optLong("seen", 0L)
        if (now + 300000L < seen) return false
        if (now - lastSave > 60000L && now > seen) { data.put("seen", now); lastSave = now; save() }
        return true
    }

    private fun cfg(): JSONObject = try { JSONObject(sync.cached("tiers.json") ?: throw Exception()) } catch (_: Exception) { JSONObject() }

    private fun defaults(t: Int): JSONObject = JSONObject()
        .put("name", listOf("Free", "Trial", "First", "Basic", "Full", "Ultimate").getOrElse(t) { "Free" })
        .put("price", 0)
        .put("ads", t < 5)
        .put("books", true)
        .put("past_papers", if (t >= 2) "exam" else "view")
        .put("ai_offline", t >= 2)
        .put("ai_online", t >= 2)
        .put("generated", t >= 3)
        .put("daily_questions", if (t >= 3) 100 else 0)
        .put("custom", t >= 5)
        .put("key_mode", if (t >= 5) "ours" else if (t >= 2) "own" else "none")

    fun features(t: Int): JSONObject = cfg().optJSONObject("tiers")?.optJSONObject(t.toString()) ?: defaults(t)

    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP)

    private fun der(sig: ByteArray): ByteArray {
        fun enc(x: ByteArray): ByteArray { val b = BigInteger(1, x).toByteArray(); return byteArrayOf(0x02, b.size.toByte()) + b }
        val r = enc(sig.copyOfRange(0, 32)); val s = enc(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun pubKey(): PublicKey? {
        pub?.let { return it }
        val o = data.optJSONObject("pub") ?: return null
        return try {
            val ap = AlgorithmParameters.getInstance("EC")
            ap.init(ECGenParameterSpec("secp256r1"))
            val spec = ap.getParameterSpec(ECParameterSpec::class.java)
            val pt = ECPoint(BigInteger(1, b64(o.getString("x"))), BigInteger(1, b64(o.getString("y"))))
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pt, spec)).also { pub = it }
        } catch (_: Exception) { null }
    }

    private fun ensurePubKey() {
        if (pubKey() != null) return
        val r = call("/pubkey", null)
        if (r.optString("kty") != "EC" || r.optString("crv") != "P-256" || r.optString("x").isEmpty() || r.optString("y").isEmpty()) throw ApiError(502, "err_server")
        synchronized(this) { data.put("pub", JSONObject().put("x", r.getString("x")).put("y", r.getString("y"))); save() }
        if (pubKey() == null) throw ApiError(502, "err_server")
    }

    private fun verifyToken(tk: String): Pair<Long, Int>? {
        try {
            val parts = tk.split(".")
            if (parts.size != 2) return null
            val sig = b64(parts[1])
            if (sig.size != 64) return null
            val key = pubKey() ?: return null
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(key)
            s.update(parts[0].toByteArray(Charsets.US_ASCII))
            if (!s.verify(der(sig))) return null
            val p = JSONObject(String(b64(parts[0]), Charsets.UTF_8))
            if (p.optString("d") != deviceId || p.optString("p") != data.optString("phone")) return null
            return Pair(p.optLong("exp"), p.optInt("t"))
        } catch (_: Exception) { return null }
    }

    private fun tokenValid(tk: String, now: Long): Boolean {
        val v = verified
        if (v != null && v.first == tk) return now / 1000 < v.second
        val r = verifyToken(tk) ?: return false
        verified = Triple(tk, r.first, r.second)
        return now / 1000 < r.first
    }

    @Synchronized
    fun state(): String {
        val now = System.currentTimeMillis()
        val ok = clockOk(now)
        return when (data.optString("mode")) {
            "free" -> "free"
            "trial" -> if (ok && now < data.optLong("trial_end")) "trial" else "trial_expired"
            "paid" -> {
                val tk = data.optString("token")
                if (tk.isEmpty()) "pending" else if (ok && tokenValid(tk, now)) "active" else "locked"
            }
            else -> "none"
        }
    }

    @Synchronized
    fun tier(): Int = when (state()) {
        "active" -> verified?.third ?: -1
        "free", "pending" -> 0
        "trial" -> 1
        else -> -1
    }

    fun adsOn(): Boolean = features(tier().coerceAtLeast(0)).optBoolean("ads", true)

    @Synchronized
    fun view(): JSONObject {
        val st = state()
        val t = tier()
        val c = cfg()
        val tiers = JSONObject()
        for (i in 0..5) tiers.put(i.toString(), features(i))
        val o = JSONObject().put("state", st).put("tier", t).put("features", features(t.coerceAtLeast(0)))
            .put("phone", data.optString("phone")).put("pending_tier", data.optInt("pending_tier"))
            .put("offline_days", c.optInt("offline_days", 7)).put("currency", c.optString("currency", "ETB"))
            .put("pay_to", c.optJSONObject("pay_to") ?: JSONObject()).put("policy", c.optJSONObject("policy") ?: JSONObject()).put("tiers", tiers)
        if (st == "trial") o.put("trial_left", maxOf(0L, (data.optLong("trial_end") - System.currentTimeMillis()) / 1000L))
        val code = data.optString("code")
        if (code.isNotEmpty()) o.put("code", code)
        if (data.optBoolean("wiped")) o.put("wiped", true)
        return o
    }

    private fun normPhone(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("0")) d = "251" + d.substring(1)
        if (d.length == 9) d = "251$d"
        if (!Regex("^251[79][0-9]{8}$").matches(d)) throw ApiError(400, "lic_err_bad_phone")
        return d
    }

    private fun call(path: String, body: JSONObject?): JSONObject {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "AlphaAI/0.1")
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val j = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            if (code >= 400) throw ApiError(code, "lic_err_" + j.optString("error", "server"))
            return j
        } catch (e: java.io.IOException) {
            throw ApiError(503, "online_no_internet")
        } finally { c.disconnect() }
    }

    fun chooseFree() {
        synchronized(this) {
            val st = state()
            if (st == "none" || st == "trial_expired") { data.put("mode", "free"); save() }
        }
    }

    fun startTrial(phone: String) {
        val st = state()
        if (st != "none" && st != "free") throw ApiError(409, "lic_err_trial_used")
        val p = normPhone(phone)
        val r = call("/trial", JSONObject().put("phone", p).put("device_id", deviceId))
        val secs = r.optInt("seconds", 300).coerceIn(30, 3600)
        synchronized(this) {
            data.put("mode", "trial").put("phone", p).put("trial_end", System.currentTimeMillis() + secs * 1000L)
            save()
        }
    }

    fun register(phone: String, tier: Int, txn: String, method: String) {
        val p = normPhone(phone)
        val st = state()
        if (st == "active" && p != data.optString("phone")) throw ApiError(409, "lic_err_other_phone")
        call("/register", JSONObject().put("phone", p).put("device_id", deviceId).put("tier", tier).put("txn_id", txn.trim()).put("method", method))
        synchronized(this) {
            if (st != "active") {
                data.put("mode", "paid").put("phone", p).put("token", "").remove("tier")
                verified = null
            }
            data.put("pending_tier", tier)
            save()
        }
    }

    fun refresh() {
        retryAck()
        val phone = synchronized(this) { if (data.optString("mode") != "paid") return; data.optString("phone") }
        val r = call("/status", JSONObject().put("phone", phone).put("device_id", deviceId))
        when (r.optString("state")) {
            "wipe" -> doWipe()
            "other_device", "none" -> synchronized(this) {
                data.remove("mode"); data.remove("token"); data.remove("tier"); data.remove("phone"); data.remove("pending_tier")
                verified = null; save()
            }
            "active" -> {
                ensurePubKey()
                val tk = r.optString("token")
                synchronized(this) {
                    val v = verifyToken(tk) ?: throw ApiError(502, "err_server")
                    verified = Triple(tk, v.first, v.second)
                    data.put("token", tk).put("tier", v.second)
                    if (data.optInt("pending_tier") <= v.second) data.remove("pending_tier")
                    val code = r.optString("recovery_code")
                    if (code.isNotEmpty()) data.put("code", code)
                    save()
                }
            }
        }
    }

    fun redeem(phone: String, code: String) {
        val p = normPhone(phone)
        call("/redeem", JSONObject().put("phone", p).put("device_id", deviceId).put("code", code.trim()))
        synchronized(this) {
            data.put("mode", "paid").put("phone", p).put("token", "").remove("tier")
            data.remove("pending_tier")
            verified = null
            save()
        }
        refresh()
    }

    fun move(phone: String, recovery: String) {
        val p = normPhone(phone)
        val r = call("/move", JSONObject().put("phone", p).put("device_id", deviceId).put("recovery_code", recovery.trim()))
        ensurePubKey()
        val tk = r.optString("token")
        synchronized(this) {
            data.put("phone", p)
            val v = verifyToken(tk)
            if (v == null) { data.remove("phone"); throw ApiError(502, "err_server") }
            verified = Triple(tk, v.first, v.second)
            data.put("mode", "paid").put("token", tk).put("tier", v.second).put("code", r.optString("recovery_code")).remove("pending_tier")
            save()
        }
    }

    fun quotaUse(subject: String, want: Int): JSONObject {
        val limit = features(tier().coerceAtLeast(0)).optInt("daily_questions", 0)
        if (limit <= 0) throw ApiError(403, "tier_required")
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val key = subject.trim().lowercase().ifEmpty { "-" }
        synchronized(this) {
            var q = data.optJSONObject("quota")
            if (q == null || q.optString("day") != day) { q = JSONObject().put("day", day); data.put("quota", q) }
            val used = q.optInt(key)
            val left = maxOf(0, limit - used)
            val give = minOf(maxOf(want, 0), left)
            if (give <= 0) throw ApiError(429, "quota_exceeded")
            q.put(key, used + give)
            save()
            return JSONObject().put("granted", give).put("left", left - give).put("limit", limit)
        }
    }

    fun contentToken(): String? = synchronized(this) {
        if (state() == "active" && features(tier()).optBoolean("generated")) data.optString("token").ifEmpty { null } else null
    }

    fun relayToken(): String? = synchronized(this) {
        if (state() == "active" && features(tier()).optString("key_mode") == "ours") data.optString("token").ifEmpty { null } else null
    }

    @Volatile private var relayIds: Set<String> = emptySet()
    @Volatile private var relayAt = 0L

    fun relayFor(id: String): Boolean = relayToken() != null && id in relayIds

    fun refreshRelay() {
        val tk = relayToken()
        if (tk == null) { relayIds = emptySet(); return }
        if (relayIds.isNotEmpty() && System.currentTimeMillis() - relayAt < 600000L) return
        val c = URL("$SERVER/relay/_providers").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("x-alpha-token", tk)
            if (c.responseCode == 200) {
                val arr = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("ids")
                val out = HashSet<String>()
                if (arr != null) for (i in 0 until arr.length()) out.add(arr.getString(i))
                relayIds = out
                relayAt = System.currentTimeMillis()
            }
        } catch (_: Exception) { } finally { c.disconnect() }
    }

    fun ackCode() { synchronized(this) { data.remove("code"); save() } }

    fun ackNotice() { synchronized(this) { data.remove("wiped"); save() } }

    private fun doWipe() {
        onWipe?.invoke()
        for (d in listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))) d.listFiles()?.forEach { it.deleteRecursively() }
        synchronized(this) { data = JSONObject().put("wiped", true).put("ack_pending", true); verified = null; save() }
        retryAck()
    }

    fun retryAck() {
        if (!synchronized(this) { data.optBoolean("ack_pending") }) return
        try {
            call("/ack-wipe", JSONObject().put("device_id", deviceId))
            synchronized(this) { data.remove("ack_pending"); save() }
        } catch (_: Exception) { }
    }

    fun due(): Boolean = state().let { it == "active" || it == "pending" || it == "locked" }

    companion object { const val SERVER = "https://alpha-server.kinfemichaelsemagnatanaw-7b8.workers.dev" }
}
