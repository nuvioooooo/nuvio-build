package com.nuvio.tv.core.dub

/**
 * "p,a,c,k,e,d" (Dean Edwards) paket açıcı ve Rapidrame karıştırma çözücüsü.
 * dubtool.py içindeki js_unpack / rapidrame_decode ile birebir aynı mantık. Android'e bağımlı değil.
 *
 * Rapidrame oynatıcı sayfası üç farklı şema kullandı; hepsi burada desteklenir:
 *  - v2 (dizi sayfaları, Kasım 2026): `return FN(["..",..])`, anahtar ve işlem dizisi fonksiyonun içinde.
 *  - v3 (film sayfaları, Kasım 2026): `return FN("a~b~c".split("~"))`, anahtar ve işlem dizisi diziden alınır.
 *  - eski: `"PAYLOAD".split("...")` açılmış paketin içinde.
 * v2/v3'te sabitler (çarpanlar, modüller…) fonksiyon gövdesinden okunur; site yalnızca sayıları
 * değiştirirse kod değişmeden çalışır.
 */
internal object DubPacked {

    private val PACKED = Regex(
        """\}\('(.*)', *(\d+|\[\]), *(\d+), *'(.*)'\.split\('\|'\), *(\d+), *(.*)\)\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WORD = Regex("""\b\w+\b""")
    private val SPLIT_CALL = Regex("\"([^\"]+)\"\\s*\\.split\\(\\s*\"([^\"]*)\"\\s*\\)")
    private const val ALNUM = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val V2_CALL = Regex("return\\s*(\\w+)\\(\\s*\\[((?:\\s*\"[^\"]*\"\\s*,?)+)\\s*\\]\\s*\\)")
    private val V3_CALL = Regex("return\\s*(\\w+)\\(\\s*\"([^\"]*)\"\\s*\\.split\\(\\s*\"([^\"]*)\"\\s*\\)\\s*\\)")
    private val QUOTED = Regex("\"([^\"]*)\"")
    private val V2_VARS = Regex("var \\w+=\\w+\\.join\\(''\\);var (\\w+)=\"([^\"]*)\";var (\\w+)=\"([^\"]*)\";")

    private val V2_C1 = Regex("""=\(\w+\*(\d+)\+\w+\)%(\d+);\w+=\(\w+\^\(\w+\+\w+\)\)&(\d+)\}var \w+=\(\w+\+\w+\)%(\d+),\w+=\(\w+%(\d+)\)\+(\d+),\w+=\(\(\w+\*(\d+)\+\w+\)%(\d+)\)\+1;""")
    private val PERM_C = Regex("""=\(\w+\*(\d+)\+(\d+)\)%(\d+);\w+\[\w+\]=\w+%\(\w+\+1\)""")
    private val ROT_C = Regex("""\.charCodeAt\(0\)-(\d+)\)%26""")

    private val V3_SPLICE_LEN = Regex("""var (\w+)=\w+\.length-(\d+),(\w+)=\1%(\d+),(\w+)=(\d+)\+\(\1%(\d+)\);""")
    private val V3_SPLICE = Regex("""var (\w+)=\w+\.splice\((\w+),1\)\[0\],(\w+)=\w+\.splice\((\w+),1\)\[0\]""")
    private val V3_HASH = Regex("""=\(\w+\*(\d+)\+\w+\)%(\d+);\w+=\(\w+\+\(\(\w+<<(\d+)\)\^\w+\)\)&(\d+)\}""")
    private val V3_C1 = Regex("""var \w+=\(\w+\*(\d+)\+\w+\)%(\d+),\w+=\(\w+%(\d+)\)\+(\d+),\w+=\(\(\w+\*(\d+)\+\w+\)%(\d+)\)\+1;""")
    private val V3_XOR = Regex("""=\(\w+\*(\d+)\+\w+\)%256;\w+\+=String\.fromCharCode\(\w+\^\w+\);""")
    private val V3_OPS = Regex("""if\(\w+==='(.)'\)\{\w+=atob\(\w+\)\}else if\(\w+==='(.)'\)\{\w+=\w+\.split\(''\)\.reverse""")

    /**
     * Paket bulunamazsa null. Dönen metin: açılmış paket + satır sonu + özgün betik.
     * Gerçek yük (`return FN([...])`) açılmış pakette değil özgün betikte durduğu için ikisi birlikte verilir;
     * böylece tarifte değişiklik gerekmeden rapidrame_decode ikisini de görür.
     */
    fun jsUnpack(script: String): String? {
        val m = PACKED.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\\\", "\\").replace("\\'", "'")
        val radix = if (m.groupValues[2] == "[]") 62 else m.groupValues[2].toInt()
        val symtab = m.groupValues[4].split("|")
        val unpacked = WORD.replace(payload) { w ->
            val i = unbase(w.value, radix)
            if (i != null && i < symtab.size && symtab[i].isNotEmpty()) symtab[i] else w.value
        }
        return unpacked + "\n" + script
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
    fun rapidrameDecode(text: String): String {
        val errs = ArrayList<String>()
        val decoders: List<Pair<String, (String) -> String>> = listOf(
            "v2" to { t: String -> decodeV2(t) },
            "v3" to { t: String -> decodeV3(t) }
        )
        for ((name, fn) in decoders) {
            try {
                return fn(text)
            } catch (e: Exception) {
                errs.add("$name: ${e.message}")
            }
        }
        val last = SPLIT_CALL.findAll(text).lastOrNull()
            ?: throw IllegalArgumentException("hiçbir çözücü eşleşmedi -> " + errs.joinToString(" | "))
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

    /** `FN` fonksiyonunun tanımı metinde var mı (yem fonksiyonlarını ayıklamak için). */
    private fun isDefined(name: String, text: String): Boolean =
        Regex("(?:var\\s+" + Regex.escape(name) + "\\s*=\\s*function|function\\s+" + Regex.escape(name) + ")\\s*\\(")
            .containsMatchIn(text)

    private fun ints(m: MatchResult): List<Int> = m.groupValues.drop(1).map { it.toInt() }

    // ---- v2: dizi sayfaları ------------------------------------------------------------------
    private fun decodeV2(text: String): String {
        val call = V2_CALL.findAll(text).firstOrNull { isDefined(it.groupValues[1], text) }
            ?: throw IllegalArgumentException("return FN([...]) çağrısı bulunamadı")
        val name = call.groupValues[1]
        val parts = QUOTED.findAll(call.groupValues[2]).map { it.groupValues[1] }.toList()
        val fm = Regex("function\\s+" + Regex.escape(name) + "\\(\\w+\\)\\{(.*?)(?=\\}function\\s|\\z)", RegexOption.DOT_MATCHES_ALL)
            .find(text) ?: throw IllegalArgumentException("fonksiyon gövdesi bulunamadı: $name")
        val body = fm.groupValues[1]
        val vm = V2_VARS.find(body) ?: throw IllegalArgumentException("anahtar/işlem dizisi bulunamadı")
        val n1 = vm.groupValues[1]; val v1 = vm.groupValues[2]
        val n2 = vm.groupValues[3]; val v2 = vm.groupValues[4]
        val key: String
        val ops: String
        if (Regex("\\b" + Regex.escape(n2) + "\\.charAt\\(").containsMatchIn(body)) { key = v1; ops = v2 }
        else if (Regex("\\b" + Regex.escape(n1) + "\\.charAt\\(").containsMatchIn(body)) { key = v2; ops = v1 }
        else throw IllegalArgumentException("işlem dizisi hangisi belli değil")
        val c1 = V2_C1.find(body); val c2 = PERM_C.find(body); val c3 = ROT_C.find(body)
        if (c1 == null || c2 == null || c3 == null) {
            throw IllegalArgumentException("sabitler okunamadı (site yapıyı değiştirmiş olabilir)")
        }
        val k1 = ints(c1)
        val mul = k1[0]; val mod = k1[1]; val mask = k1[2]; val mod2 = k1[3]
        val modk = k1[4]; val addk = k1[5]; val mul2 = k1[6]; val mod3 = k1[7]
        val k2 = ints(c2)
        val pm = k2[0]; val pa = k2[1]; val pmod = k2[2]
        val roff = c3.groupValues[1].toInt()
        require(ops.length <= 4096 && parts.size <= 100000) { "desteklenmeyen dal (uzun ops/dizi)" }

        var a = 0
        var b = 0
        for ((i, ch) in key.withIndex()) {
            val z = ch.code
            a = (a * mul + z) % mod
            b = (b xor (z + i)) and mask
        }
        val rw = (a + b) % mod2
        val step = (a % modk) + addk
        val seed = ((a * mul2 + b) % mod3) + 1
        var x = parts.joinToString("")
        for (i in ops.length - 1 downTo 0) {
            val c = ops[i]
            x = when (c) {
                'b' -> atob(x)
                'v' -> x.reversed()
                else -> rotLetters(x, (26 - ((c.code - roff) % 26)) % 26)
            }
        }
        return finish(x, rw, step, seed, pm, pa, pmod, 1)
    }

    // ---- v3: film sayfaları ------------------------------------------------------------------
    private fun decodeV3(text: String): String {
        val call = V3_CALL.findAll(text).firstOrNull { isDefined(it.groupValues[1], text) }
            ?: throw IllegalArgumentException("return FN(\"..\".split(..)) çağrısı bulunamadı")
        val name = call.groupValues[1]
        val payload = call.groupValues[2]
        val delim = call.groupValues[3]
        require(delim.isNotEmpty()) { "ayraç boş" }
        val parts = payload.split(delim)
        val fs = Regex("(?:var\\s+" + Regex.escape(name) + "\\s*=\\s*function|function\\s+" + Regex.escape(name) + ")\\s*\\(\\w+\\)\\{")
            .find(text) ?: throw IllegalArgumentException("fonksiyon başlangıcı bulunamadı: $name")
        val body = text.substring(fs.range.last + 1, minOf(text.length, fs.range.last + 1 + 5000))
        val sp = V3_SPLICE_LEN.find(body)
        val s2 = V3_SPLICE.find(body)
        if (sp == null || s2 == null) throw IllegalArgumentException("splice kalıbı okunamadı")
        val sub = sp.groupValues[2].toInt()
        val m1 = sp.groupValues[4].toInt()
        val base = sp.groupValues[6].toInt()
        val m2 = sp.groupValues[7].toInt()
        val q = parts.size - sub
        fun idxOf(varName: String): Int = when (varName) {
            sp.groupValues[3] -> q % m1
            sp.groupValues[5] -> base + (q % m2)
            else -> throw IllegalArgumentException("splice dizini bilinmiyor")
        }
        val arr = parts.toMutableList()
        val first = arr.removeAt(idxOf(s2.groupValues[2]))
        val second = arr.removeAt(idxOf(s2.groupValues[4]))
        val v1n = s2.groupValues[1]
        val v2n = s2.groupValues[3]
        val ops: String
        val key: String
        if (Regex("\\b" + Regex.escape(v1n) + "\\.charAt\\(").containsMatchIn(body)) { ops = first; key = second }
        else if (Regex("\\b" + Regex.escape(v2n) + "\\.charAt\\(").containsMatchIn(body)) { ops = second; key = first }
        else throw IllegalArgumentException("işlem dizisi hangisi belli değil")
        val h = V3_HASH.find(body); val c1 = V3_C1.find(body); val c2 = PERM_C.find(body)
        val c3 = ROT_C.find(body); val c4 = V3_XOR.find(body); val c5 = V3_OPS.find(body)
        if (h == null || c1 == null || c2 == null || c3 == null || c4 == null || c5 == null) {
            throw IllegalArgumentException("sabitler okunamadı (site yapıyı değiştirmiş olabilir)")
        }
        val kh = ints(h)
        val hmul = kh[0]; val hmod = kh[1]; val hsh = kh[2]; val hmask = kh[3]
        val k1 = ints(c1)
        val rmul = k1[0]; val rmod = k1[1]; val modk = k1[2]; val addk = k1[3]; val mul2 = k1[4]; val mod3 = k1[5]
        val k2 = ints(c2)
        val pm = k2[0]; val pa = k2[1]; val pmod = k2[2]
        val roff = c3.groupValues[1].toInt()
        val xmul = c4.groupValues[1].toInt()
        val cAtob = c5.groupValues[1][0]
        val cRev = c5.groupValues[2][0]

        var a = 0
        var b = 0
        for ((i, ch) in key.withIndex()) {
            val z = ch.code
            a = (a * hmul + z) % hmod
            b = (b + ((z shl hsh) xor i)) and hmask
        }
        val rw = (a * rmul + b) % rmod
        val step = (b % modk) + addk
        val seed = ((b * mul2 + a) % mod3) + 1
        var x = arr.joinToString("")
        for (i in ops.length - 1 downTo 0) {
            val c = ops[i]
            x = when (c) {
                cAtob -> atob(x)
                cRev -> x.reversed()
                else -> rotLetters(x, (26 - ((c.code - roff) % 26)) % 26)
            }
        }
        return finish(x, rw, step, seed, pm, pa, pmod, xmul)
    }

    /**
     * Ortak son aşama: Fisher-Yates benzeri karıştırmayı geri al, sonra akış anahtarıyla XOR'la.
     * v2: rw = (rw + step) % 256        (xmul = 1)
     * v3: rw = (rw * xmul + step) % 256
     */
    private fun finish(
        x: String, rw0: Int, step: Int, seed0: Int, pm: Int, pa: Int, pmod: Int, xmul: Int
    ): String {
        var rw = rw0
        var seed = seed0
        val n = x.length
        val perm = IntArray(n)
        for (i in n - 1 downTo 1) {
            seed = (seed * pm + pa) % pmod
            perm[i] = seed % (i + 1)
        }
        val chars = x.toCharArray()
        for (i in 1 until n) {
            val j = perm[i]
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        val out = StringBuilder(n)
        for (ch in chars) {
            val z = ch.code
            rw = (rw * xmul + step) % 256
            out.append((z xor rw).toChar())
            rw = (rw + z) % 256
        }
        return out.toString()
    }

    // ---- eski şema ---------------------------------------------------------------------------
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
