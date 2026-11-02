package com.nuvio.tv.core.dub

/**
 * "p,a,c,k,e,d" (Dean Edwards) paket açıcı ve Rapidrame karıştırma çözücüsü.
 * dubtool.py içindeki js_unpack / rapidrame_decode ile birebir aynı mantık. Android'e bağımlı değil.
 */
internal object DubPacked {

    private val PACKED = Regex(
        """\}\('(.*)', *(\d+|\[\]), *(\d+), *'(.*)'\.split\('\|'\), *(\d+), *(.*)\)\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WORD = Regex("""\b\w+\b""")
    private val SPLIT_CALL = Regex(""""([^"]+)"\s*\.split\(\s*"([^"]*)"\s*\)""")
    private const val ALNUM = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Paket bulunamazsa null. */
    fun jsUnpack(script: String): String? {
        val m = PACKED.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\\\", "\\").replace("\\'", "'")
        val radix = if (m.groupValues[2] == "[]") 62 else m.groupValues[2].toInt()
        val symtab = m.groupValues[4].split("|")
        return WORD.replace(payload) { w ->
            val i = unbase(w.value, radix)
            if (i != null && i < symtab.size && symtab[i].isNotEmpty()) symtab[i] else w.value
        }
    }

    private fun unbase(word: String, radix: Int): Int? {
        var v = 0L
        for (ch in word) {
            val idx = if (radix <= 62) ALNUM.indexOf(ch) else (if (ch.code in 32..126) ch.code - 32 else -1)
            if (idx < 0) return null
            v = v * radix + idx
            if (v > 10_000_000L) return null // sembol tablosundan büyük, zaten eşleşmez
        }
        return v.toInt()
    }

    /** Hata durumunda IllegalArgumentException (çağıran yakalar). */
    fun rapidrameDecode(unpacked: String): String {
        val last = SPLIT_CALL.findAll(unpacked).lastOrNull()
            ?: throw IllegalArgumentException("\"PAYLOAD\".split(\"...\") bulunamadı")
        val payload = last.groupValues[1]
        val delim = last.groupValues[2]
        require(payload.isNotEmpty() && delim.isNotEmpty()) { "payload/ayraç boş" }
        return decodeObfuscated(payload.split(delim))
    }

    private fun atob(input: String): String {
        val sb = StringBuilder(input.length + 2)
        for (c in input) if (!c.isWhitespace()) sb.append(c)
        when (sb.length % 4) { 2 -> sb.append("=="); 3 -> sb.append("=") }
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var acc = 0
        var bits = 0
        for (c in sb) {
            val v = table.indexOf(c)
            if (v < 0) continue // '=' ve alfabe dışı karakterler atlanır (Python b64decode gibi)
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((acc shr bits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }

    private fun rotLetters(s: String, shift: Int): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                val base = if (ch <= 'Z') 65 else 97
                out.append((((ch.code - base + shift) % 26) + base).toChar())
            } else out.append(ch)
        }
        return out.toString()
    }

    private fun decodeObfuscated(parts: List<String>): String {
        val arr = parts.toMutableList()
        val myq = arr.size - 2
        val b0r = myq % 7
        val d3n = 8 + (myq % 5)
        val dwtqp = arr.removeAt(d3n)
        val l6w = arr.removeAt(b0r)
        var x = arr.joinToString("")
        if (dwtqp.length > 2048) x = x.reversed()
        var uv47f = 0
        var czb = 0
        for ((i, ch) in l6w.withIndex()) {
            val z = ch.code
            uv47f = (uv47f * 37 + z) % 241
            czb = (czb + ((z shl 1) xor i)) and 255
        }
        val y99wm = (uv47f * 3 + czb) % 256
        val hkaa = (czb % 11) + 5
        var uyiz = ((czb * 251 + uv47f) % 65519) + 1
        for (i in dwtqp.length - 1 downTo 0) {
            val seq = dwtqp[i]
            x = when (seq) {
                '7' -> atob(x)
                '3' -> x.reversed()
                else -> rotLetters(x, (26 - ((seq.code - 96) % 26)) % 26)
            }
        }
        if (l6w.length > 4096) x = atob(x)
        val n = x.length
        val perm = IntArray(n)
        for (i in n - 1 downTo 1) {
            uyiz = (uyiz * 97 + 41) % 65519
            perm[i] = uyiz % (i + 1)
        }
        val chars = x.toCharArray()
        for (i in 1 until n) {
            val j = perm[i]
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        var bmrq = y99wm
        val out = StringBuilder(n)
        for (ch in chars) {
            val z = ch.code
            bmrq = (bmrq * 5 + hkaa) % 256
            out.append((z xor bmrq).toChar())
            bmrq = (bmrq + z) % 256
        }
        return out.toString()
    }
}
