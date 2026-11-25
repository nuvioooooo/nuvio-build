package com.nuvio.tv.core.dub

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.abs
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val RUNNER_TAG = "DubPipeline"
private const val MAX_BODY_BYTES = 3L * 1024 * 1024
private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Runs a provider recipe: search page, pick a result, then walk the listed steps
 * (http_get, http_post, xor_b64, regex, json_path, rot13, base64, reverse, shift_key, set, check, replace, require,
 * hls_audio, js_unpack, rapidrame_decode, rsc_enc_decrypt, js_object, pichive_sign) until one of them yields the audio URL.
 * Only these built-in operations exist; the recipe is data.
 */
internal class DubPipelineRunner(private val http: OkHttpClient) {

    suspend fun find(provider: DubProvider, request: DubRequest): DubHit? = withContext(Dispatchers.IO) {
        val queries = if (request.isMovie) provider.movieQueries else provider.seriesQueries
        if (queries.isEmpty()) return@withContext null

        val baseVars = LinkedHashMap<String, String>(request.toVars())
        baseVars["base"] = provider.base
        val tried = HashSet<String>()

        for (rawQuery in queries) {
            ensureActive()
            val query = expand(rawQuery, baseVars, strict = true) ?: continue
            // search.url "POST <adres> <json-gövde>" ile başlarsa POST'tur ({q} gövdede JSON kaçışlıdır).
            val spec = provider.searchUrl.trim()
            val post = spec.startsWith("POST ")
            val rest = if (post) spec.removePrefix("POST ").trim() else spec
            val qValue = if (post) org.json.JSONObject.quote(query).let { it.substring(1, it.length - 1) }
            else URLEncoder.encode(query, "UTF-8")
            val searchVars = baseVars + ("q" to qValue)
            val searchUrl = expand(if (post) rest.substringBefore(' ') else rest, searchVars, strict = true) ?: continue
            val searchBody = if (post) (expand(rest.substringAfter(' ', ""), searchVars, strict = true) ?: continue) else null
            val searchHeaders = LinkedHashMap<String, String>(provider.headers)
            if (post) searchHeaders["Origin"] = provider.base
            val html = try {
                fetch(searchUrl, provider.base + "/", searchHeaders, searchBody)
            } catch (e: Exception) {
                Log.w(RUNNER_TAG, "[${provider.id}] search failed: ${e.message}")
                null
            } ?: continue

            for (candidate in pickCandidates(html, provider)) {
                ensureActive()
                if (!tried.add(candidate)) continue
                Log.i(RUNNER_TAG, "[${provider.id}] trying $candidate")
                val hit = try {
                    runSteps(provider, baseVars + ("page" to candidate))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(RUNNER_TAG, "[${provider.id}] steps failed for $candidate: ${e.message}")
                    null
                }
                if (hit != null) return@withContext hit
            }
        }
        null
    }

    private fun pickCandidates(html: String, provider: DubProvider): List<String> {
        val found = LinkedHashSet<String>()
        for (pattern in provider.searchPicks) {
            val regex = try {
                Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                Log.w(RUNNER_TAG, "[${provider.id}] bad pick pattern: ${e.message}")
                continue
            }
            for (m in regex.findAll(html)) {
                // Search results can come back as escaped JSON (`dizi\/slug`); unescape the slashes.
                val href = m.groupValues.getOrNull(1)?.trim().orEmpty().replace("\\/", "/")
                if (href.isEmpty()) continue
                found.add(absoluteUrl(href, provider.base))
                if (found.size >= provider.maxCandidates) return found.toList()
            }
        }
        return found.toList()
    }

    private fun absoluteUrl(href: String, base: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base + href
        else -> "$base/$href"
    }

    private fun runSteps(provider: DubProvider, startVars: Map<String, String>): DubHit? {
        val vars = HashMap<String, String>(startVars)
        var cur = ""

        for (step in provider.steps) {
            val p = step.params
            val from = p.optString("from", "")
            val input = if (from.isEmpty() || from == "cur") cur else (vars[from] ?: return fail(provider, step, "missing var '$from'"))

            val out: String = when (step.op) {
                "http_get" -> {
                    val url = expand(p.optString("url", ""), vars, strict = true)
                        ?: return fail(provider, step, "url has unresolved variables")
                    val referer = p.optString("referer", "").takeIf { it.isNotEmpty() }
                        ?.let { expand(it, vars, strict = false) }
                    // A step-level "headers" object (even an empty one) replaces the provider headers.
                    val stepHeaders: Map<String, String> = p.optJSONObject("headers")
                        ?.let { o ->
                            val m = LinkedHashMap<String, String>()
                            val keys = o.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = o.optString(k, "")
                                if (v.isNotEmpty()) m[k] = v
                            }
                            m
                        }
                        ?: provider.headers
                    fetch(url, referer, stepHeaders)
                        ?: return fail(provider, step, "request failed: $url")
                }
                "http_post" -> {
                    val url = expand(p.optString("url", ""), vars, strict = true)
                        ?: return fail(provider, step, "url has unresolved variables")
                    val referer = p.optString("referer", "").takeIf { it.isNotEmpty() }
                        ?.let { expand(it, vars, strict = false) }
                    // Form alanları {değişken} içerebilir; boş değişken boş alan olarak gönderilir.
                    val form = LinkedHashMap<String, String>()
                    p.optJSONObject("form")?.let { o ->
                        val keys = o.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            form[k] = expand(o.optString(k, ""), vars + ("cur" to cur), strict = false).orEmpty()
                        }
                    }
                    // Adımın "headers" nesnesi sağlayıcı başlıklarına EKLENİR (http_get'teki gibi değiştirmez).
                    val extra = LinkedHashMap<String, String>()
                    p.optJSONObject("headers")?.let { o ->
                        val keys = o.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            o.optString(k, "").takeIf { it.isNotEmpty() }?.let { extra[k] = it }
                        }
                    }
                    postForm(url, referer, provider.headers + extra, form)
                        ?: return fail(provider, step, "request failed: $url")
                }
                "xor_b64" -> {
                    val a = expand(p.optString("a", ""), vars + ("cur" to cur), strict = true)
                        ?: return fail(provider, step, "'a' has unresolved variables")
                    val b = expand(p.optString("b", ""), vars + ("cur" to cur), strict = true)
                        ?: return fail(provider, step, "'b' has unresolved variables")
                    DubOps.xorBase64(a, b)
                        ?: return fail(provider, step, "xor/base64 decode failed")
                }
                "regex" -> {
                    val patterns = mutableListOf<String>()
                    val patternArr = p.optJSONArray("patterns")
                    if (patternArr != null) {
                        for (k in 0 until patternArr.length()) {
                            val item = patternArr.optString(k, "")
                            if (item.isNotEmpty()) patterns.add(item)
                        }
                    }
                    if (patterns.isEmpty()) patterns.add(p.optString("pattern", ""))
                    var found: String? = null
                    for (pt in patterns) {
                        // {page}, {season}, {episode}... inside the pattern are filled with their (escaped) values.
                        val regex = buildRegex(expandPattern(pt, vars), p.optBoolean("dotAll", false), p.optBoolean("ignoreCase", false)) ?: continue
                        val v = regex.find(input)?.groupValues?.getOrNull(p.optInt("group", 1))
                        if (v != null) { found = v; break }
                    }
                    found ?: return fail(provider, step, "no match")
                }
                "json_path" -> DubOps.jsonPath(input, p.optString("path", ""))
                    ?: return fail(provider, step, "path not found")
                "rot13" -> DubOps.rot13(input)
                "reverse" -> input.reversed()
                "trim" -> input.trim()
                "base64" -> DubOps.base64Decode(input, charsetOf(p.optString("charset", "latin1")))
                    ?: return fail(provider, step, "bad base64")
                "shift_key" -> DubOps.shiftKey(
                    input = input,
                    key = p.optString("key", ""),
                    mod = p.optInt("mod", 5),
                    add = p.optInt("add", 1),
                    subtract = p.optString("dir", "sub") != "add"
                )
                "js_unpack" -> DubOps.jsUnpack(input)
                    ?: return fail(provider, step, "packed script not found")
                "rapidrame_decode" -> try {
                    DubOps.rapidrameDecode(input).trim()
                } catch (e: Exception) {
                    return fail(provider, step, "decode failed: ${e.message}")
                }
                "replace" -> {
                    val regex = buildRegex(p.optString("pattern", ""), false, p.optBoolean("ignoreCase", false))
                        ?: return fail(provider, step, "bad pattern")
                    val template = p.optString("with", "")
                    val shifts = HashMap<Int, Int>()
                    val shiftArr = p.optJSONArray("shift")
                    if (shiftArr != null) {
                        for (k in 0 until shiftArr.length()) {
                            val o = shiftArr.optJSONObject(k) ?: continue
                            shifts[o.optInt("group", -1)] = o.optInt("delta", 0)
                        }
                    }
                    regex.replace(input.trim()) { m ->
                        Regex("""\\(\d)""").replace(template) { ref ->
                            val k = ref.groupValues[1].toInt()
                            var v = m.groups[k]?.value.orEmpty()
                            val d = shifts[k]
                            if (d != null && v.length == 1 && v[0].isLetter()) {
                                val base = if (v[0].isLowerCase()) 'a' else 'A'
                                val shifted = ((v[0].code - base.code + d) % 26 + 26) % 26 + base.code
                                v = shifted.toChar().toString()
                            }
                            v
                        }
                    }
                }
                "require" -> {
                    val target = input.trim().substringBefore('#')
                    val regex = buildRegex(p.optString("pattern", ""), false, p.optBoolean("ignoreCase", true))
                        ?: return fail(provider, step, "bad pattern")
                    if (!regex.containsMatchIn(target)) {
                        return fail(provider, step, "'${p.optString("pattern")}' does not match ${target.takeLast(40)} (not the wanted language)")
                    }
                    input
                }
                "hls_audio" -> {
                    val ah = LinkedHashMap<String, String>()
                    for ((k, v) in provider.audioHeaders) {
                        expand(v, vars, strict = false)?.takeIf { it.isNotBlank() }?.let { ah[k] = it }
                    }
                    val playlistUrl = input.trim().substringBefore('#')
                    val text = fetch(playlistUrl, ah["Referer"], ah)
                        ?: return fail(provider, step, "playlist request failed: $playlistUrl")
                    DubOps.hlsAudio(text, playlistUrl, p.optString("lang", "tr"), p.optString("name", ""))
                        ?: return fail(provider, step, "no separate audio rendition for lang '${p.optString("lang", "tr")}' (link is video)")
                }
                "rsc_enc_decrypt" -> DubSelcuk.rscEncDecrypt(input)
                    ?: return fail(provider, step, "enc block not found or could not be decrypted")
                "js_object" -> DubSelcuk.jsObject(input, p.optString("after", ""))
                    ?: return fail(provider, step, "object not found after '${p.optString("after", "")}'")
                "pichive_sign" -> {
                    val htmlVar = p.optString("html", "")
                    val html = if (htmlVar.isEmpty()) "" else (vars[htmlVar] ?: return fail(provider, step, "missing var '$htmlVar'"))
                    DubSelcuk.pichiveSign(html, input.trim())
                }
                "set" -> expand(p.optString("value", ""), vars + ("cur" to cur), strict = false)
                    ?: return fail(provider, step, "bad template")
                "check" -> {
                    if (!passesCheck(provider, p, input, vars)) return fail(provider, step, "check '${p.optString("expect")}' did not match (wrong page)")
                    cur
                }
                else -> return fail(provider, step, "unknown op '${step.op}'")
            }

            cur = out
            val name = p.optString("as", "")
            if (name.isNotEmpty()) vars[name] = out
        }

        val resultValue = (if (provider.result.isNotEmpty()) vars[provider.result] else cur)?.trim().orEmpty()
        if (!(resultValue.startsWith("https://") || resultValue.startsWith("http://"))) {
            Log.w(RUNNER_TAG, "[${provider.id}] final value is not a URL")
            return null
        }
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in provider.audioHeaders) {
            expand(v, vars, strict = false)?.takeIf { it.isNotBlank() }?.let { headers[k] = it }
        }
        Log.i(RUNNER_TAG, "[${provider.id}] audio found: ${resultValue.take(80)}")
        return DubHit(resultValue, headers, provider.label, provider.lang, provider.id, provider.audioDelayMs)
    }

    /**
     * Confirms that the page is the wanted title by comparing a value read from the page with one
     * we know. It never blocks when the check cannot be made:
     * - the expected value is missing from the request, or the page does not show the value: passes
     *   (unless the step says `"required": true` for the page value);
     * - a numeric expected value is compared with `tolerance`;
     * - any other expected value (for example the IMDb id) is compared as text, ignoring case. The
     *   page counts as confirmed when ANY match of the pattern equals it, so a page that lists
     *   several ids is not rejected by an unrelated one that comes first.
     */
    private fun passesCheck(
        provider: DubProvider,
        p: org.json.JSONObject,
        input: String,
        vars: Map<String, String>
    ): Boolean {
        val expectName = p.optString("expect", "")
        val expectedText = vars[expectName]?.trim().orEmpty()
        if (expectedText.isEmpty()) {
            Log.i(RUNNER_TAG, "[${provider.id}] check '$expectName': nothing to compare with, skipped")
            return true
        }
        val regex = buildRegex(p.optString("pattern", ""), p.optBoolean("dotAll", false), p.optBoolean("ignoreCase", false))
            ?: return true
        val group = p.optInt("group", 1)
        val values = regex.findAll(input).mapNotNull { it.groupValues.getOrNull(group) }.toList()
        if (values.isEmpty()) {
            val required = p.optBoolean("required", false)
            Log.i(RUNNER_TAG, "[${provider.id}] check '$expectName': page shows no value, ${if (required) "rejected (required)" else "skipped"}")
            return !required
        }
        val expectedNumber = expectedText.toIntOrNull()
        val ok = if (expectedNumber != null) {
            val tolerance = p.optInt("tolerance", 0)
            values.any { v -> v.toIntOrNull()?.let { abs(it - expectedNumber) <= tolerance } == true }
        } else {
            values.any { it.equals(expectedText, ignoreCase = true) }
        }
        Log.i(
            RUNNER_TAG,
            "[${provider.id}] check '$expectName': expected $expectedText, page has ${values.distinct().take(5)} -> ${if (ok) "match" else "MISMATCH"}"
        )
        return ok
    }

    private fun fail(provider: DubProvider, step: DubStep, reason: String): DubHit? {
        Log.w(RUNNER_TAG, "[${provider.id}] step '${step.op}' stopped: $reason")
        return null
    }

    private fun buildRegex(pattern: String, dotAll: Boolean, ignoreCase: Boolean): Regex? {
        if (pattern.isEmpty()) return null
        val options = mutableSetOf<RegexOption>()
        if (dotAll) options.add(RegexOption.DOT_MATCHES_ALL)
        if (ignoreCase) options.add(RegexOption.IGNORE_CASE)
        return try {
            Regex(pattern, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun charsetOf(name: String) = when (name.lowercase()) {
        "utf-8", "utf8" -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun fetch(url: String, referer: String?, headers: Map<String, String>, jsonBody: String? = null): String? {
        if (!url.startsWith("https://")) return null
        val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
        for ((k, v) in headers) {
            if (jsonBody != null && k.equals("Content-Type", ignoreCase = true)) continue // gövde kendi türünü taşır
            builder.header(k, v)
        }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        if (jsonBody != null) builder.post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.peekBody(MAX_BODY_BYTES).string() else null
        }
    }

    private fun postForm(url: String, referer: String?, headers: Map<String, String>, form: Map<String, String>): String? {
        if (!url.startsWith("https://")) return null
        val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
        for ((k, v) in headers) {
            if (k.equals("Content-Type", ignoreCase = true)) continue // form gövdesi kendi türünü taşır
            builder.header(k, v)
        }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        val body = okhttp3.FormBody.Builder().apply { for ((k, v) in form) add(k, v) }.build()
        builder.post(body)
        return http.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.peekBody(MAX_BODY_BYTES).string() else null
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

        /** Replaces `{name}` with the variable value. With [strict], returns null if any is empty. */
        fun expand(template: String, vars: Map<String, String>, strict: Boolean): String? {
            var missing = false
            val result = PLACEHOLDER.replace(template) { m ->
                val value = vars[m.groupValues[1]]
                if (value.isNullOrEmpty()) {
                    missing = true
                    ""
                } else {
                    value
                }
            }
            return if (strict && missing) null else result
        }

        /**
         * Fills known `{name}` placeholders inside a regex with their value, matched literally.
         * Unknown or empty ones are left untouched. Quantifiers such as `{4}` or `{1,3}` are not
         * placeholders (a name must start with a letter), so they are safe.
         */
        fun expandPattern(pattern: String, vars: Map<String, String>): String =
            PLACEHOLDER.replace(pattern) { m ->
                val value = vars[m.groupValues[1]]
                if (value.isNullOrEmpty()) m.value else Regex.escape(value)
            }
    }
}

// ---------------------------------------------------------------------------
// SelcukFlix / DiziPal işlemleri: rsc_enc_decrypt, js_object, pichive_sign
// ---------------------------------------------------------------------------
/**
 * SelcukFlix / DiziPal (Next.js) için işlemler: "rsc_enc_decrypt", "js_object", "pichive_sign".
 * Algoritmalar bigbullapp-cs `Common.kt` ile aynıdır.
 */
internal object DubSelcuk {

    // RSC yükü kaçışlı JSON taşır: \"ciphertext\":\"...\"
    private val ENC_FIELD = Regex("""\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)""")

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Sayfadaki `enc` bloğunu (ciphertext/iv/salt/k) çözer ve oynatıcı adresini döndürür.
     * Parola `k` alanındadır; sayfa yolu da `k` olarak geçtiği için '/' içerenler elenir.
     * PBKDF2-HMAC-SHA512 (999 tur, 256 bit) + AES-256-CBC. Salt ve iv hex'tir.
     */
    fun rscEncDecrypt(html: String): String? {
        val fields = ENC_FIELD.findAll(html).groupBy({ it.groupValues[1] }) { it.groupValues[2] }
        val cipherText = fields["ciphertext"]?.firstOrNull() ?: return null
        val iv = fields["iv"]?.firstOrNull() ?: return null
        val salt = fields["salt"]?.firstOrNull() ?: return null
        val passphrase = fields["k"]?.filter { '/' !in it }?.maxByOrNull { it.length } ?: return null
        val clear = runCatching {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt.hexToBytes(), 999, 256))
                .encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.hexToBytes()))
            }
            String(cipher.doFinal(Base64.decode(cipherText, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return httpsUrl(clear.trim('\t', '\r', '\n', ' ', '\u000f')).takeIf { it.startsWith("https://") }
    }

    private fun httpsUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
        else -> raw
    }

    /**
     * [after] işaretinden (örn. `window.NP_CONFIG`) sonraki ilk `{`'den başlayıp süslü parantezleri
     * sayarak nesneyi çıkarır (string içindekiler atlanır). `after` boşsa metindeki ilk `{` alınır.
     */
    fun jsObject(text: String, after: String): String? {
        val anchor = if (after.isEmpty()) 0 else text.indexOf(after)
        if (anchor < 0) return null
        val from = text.indexOf('{', anchor)
        if (from < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------- pichive imzası

    /** pichive `nm.php` / `nsub.php` adreslerine `&p=<nonce>` ekler; diğer adresler değişmez. */
    fun pichiveSign(html: String, url: String): String {
        if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) return url
        val nonce = pichiveGateNonce(html) ?: return url
        return "$url&p=$nonce"
    }

    private fun pichiveGateNonce(html: String): String? {
        val gate = Regex(""""gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?""").find(html) ?: return null
        val n = gate.groupValues[1]
        val bits = gate.groupValues[2].toIntOrNull() ?: 12
        val md = MessageDigest.getInstance("SHA-256")
        fun wordAt(msg: String, idx: Int): Long {
            val d = md.digest(msg.toByteArray(Charsets.UTF_8))
            val o = idx.coerceIn(0, 7) * 4
            return ((d[o].toLong() and 0xff) shl 24) or ((d[o + 1].toLong() and 0xff) shl 16) or
                ((d[o + 2].toLong() and 0xff) shl 8) or (d[o + 3].toLong() and 0xff)
        }
        val fn = Regex("""window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};""").find(html)
        if (fn == null) {
            for (i in 0 until 50_000_000) if (wordAt("$n:$i", 0) ushr (32 - bits) == 0L) return i.toString()
            return null
        }
        val nVar = fn.groupValues[1]
        val body = fn.groupValues[3]
        val cm = Regex("""var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)""").find(body) ?: return null
        val saltVar = cm.groupValues[1]
        val salt = cm.groupValues[2].split(',').map { it.trim().toInt().toChar() }.joinToString("")
        val nonceMatch = Regex("""var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))""").find(body) ?: return null
        val nonceVar = nonceMatch.groupValues[1]
        val radix = nonceMatch.groupValues[2].toIntOrNull()?.coerceIn(2, 36) ?: 10
        val im = Regex("""\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0""").find(body) ?: return null
        val parts = im.groupValues[1].split('+').map { it.trim() }
        val wordIdx = im.groupValues[2].toIntOrNull() ?: 0
        val op = im.groupValues[3]
        val rhs = im.groupValues[4].toLongOrNull() ?: return null
        for (i in 0 until 50_000_000) {
            val nonce = java.lang.Long.toString(i.toLong(), radix)
            val msg = buildString {
                for (p in parts) {
                    when (p) {
                        nVar -> append(n)
                        saltVar -> append(salt)
                        nonceVar -> append(nonce)
                        else -> append(p.trim('\'', '"'))
                    }
                }
            }
            val w = wordAt(msg, wordIdx)
            val ok = if (op == ">>>") (w ushr rhs.toInt()) == 0L else (w and rhs) == 0L
            if (ok) return nonce
        }
        return null
    }
}
