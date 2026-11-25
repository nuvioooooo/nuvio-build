package com.nuvio.tv.core.dub

/** DubPipelineRunner'ın çağırdığı "js_unpack" ve "rapidrame_decode" işlemleri. */
internal fun DubOps.jsUnpack(input: String): String? = DubPacked.jsUnpack(input)

internal fun DubOps.rapidrameDecode(input: String): String = DubPacked.rapidrameDecode(input)

/**
 * "xor_b64": iki base64 metni çözer ve ilkini ikincinin baytlarıyla (anahtar döngülü) XOR'lar.
 * SetFilmIzle köprü sayfası (`cerceve("b2", A, B)`) gerçek oynatıcı adresini böyle saklar.
 * Çıktı UTF-8 metindir; "|" ile ayrılmış birden çok değer içerebilir.
 */
internal fun DubOps.xorBase64(first: String, second: String): String? {
    fun dec(s: String): ByteArray? = try {
        android.util.Base64.decode(s.replace("\\/", "/").trim(), android.util.Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        null
    }
    val a = dec(first) ?: return null
    val b = dec(second) ?: return null
    if (a.isEmpty() || b.isEmpty()) return null
    return String(ByteArray(a.size) { i -> (a[i].toInt() xor b[i % b.size].toInt()).toByte() }, Charsets.UTF_8)
}
