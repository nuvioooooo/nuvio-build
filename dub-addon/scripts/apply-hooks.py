#!/usr/bin/env python3
"""Dublaj eklentisinin Nuvio (upstream) dosyalarına dokunan KANCALARINI uygular.

Çalışma dizini = temiz upstream klasörü.   kullanım: apply-hooks.py <dub-addon klasörü>

Mantık:
  * Dublaj kodunun neredeyse tamamı overlay/ (yeni dosyalar) ve append/ (sınıf sonuna eklenen
    üyeler) içinde durur; upstream'in kendi satırlarına bağlı değildir.
  * Burada yalnızca birkaç KÜÇÜK kanca vardır. Hiçbiri satır numarasına bağlı değildir; her biri
    bir fonksiyon / sınıf adıyla kapsamlanır ve ancak TAM 1 eşleşme varsa uygulanır.
  * Bir kanca yerleşmezse sessizce yanlış yere yazılmaz: `::error::` basılır ve çıkış kodu 2 olur.
    Hata mesajı hangi dosyada / hangi kancada sorun olduğunu söyler.
"""
import os
import re
import sys

ADDON = os.path.abspath(sys.argv[1])
PLAYER = "app/src/main/java/com/nuvio/tv/ui/screens/player/"
errors = []


class HookError(Exception):
    pass


# --------------------------------------------------------------------------------------------
# Kotlin kaynağında parantez eşleme (yorum, string, şablon ve karakter sabitlerini atlar)
# --------------------------------------------------------------------------------------------
def _skip_string(s, i):
    """s[i] bir '"' ise, string'in bittiği yerden sonraki indeksi döndürür."""
    if s.startswith('"""', i):
        j = s.find('"""', i + 3)
        if j < 0:
            raise HookError("kapanmamış üç tırnaklı string")
        while s.startswith('"', j + 3):  # """" gibi uzun kapanışlar
            j += 1
        return j + 3
    i += 1
    while i < len(s):
        c = s[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            return i + 1
        if c == "$" and s.startswith("${", i):
            i = match_brace(s, i + 1) + 1
            continue
        i += 1
    raise HookError("kapanmamış string")


def match_brace(s, i):
    """s[i] bir '{', '(' ya da '[' ise eşinin indeksini döndürür."""
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack = [pairs[s[i]]]
    i += 1
    while i < len(s) and stack:
        c = s[i]
        if s.startswith("//", i):
            i = s.find("\n", i)
            if i < 0:
                break
            continue
        if s.startswith("/*", i):
            j = s.find("*/", i + 2)
            if j < 0:
                raise HookError("kapanmamış yorum")
            i = j + 2
            continue
        if c == '"':
            i = _skip_string(s, i)
            continue
        if c == "'":
            m = re.match(r"'(?:\\.[^']*|[^\\'])'", s[i:])
            if m:
                i += m.end()
                continue
        if c in pairs:
            stack.append(pairs[c])
        elif c == stack[-1]:
            stack.pop()
            if not stack:
                return i
        i += 1
    raise HookError("eşleşen kapanış bulunamadı")


def read(rel):
    p = os.path.join(os.getcwd(), rel)
    if not os.path.isfile(p):
        raise HookError("dosya yok: %s" % rel)
    return open(p, encoding="utf-8").read()


def write(rel, text):
    open(os.path.join(os.getcwd(), rel), "w", encoding="utf-8").write(text)


def find_one(s, pattern, what, start=0, end=None, flags=re.M):
    end = len(s) if end is None else end
    ms = list(re.finditer(pattern, s[start:end], flags))
    if len(ms) != 1:
        raise HookError("%s: %d eşleşme (1 bekleniyordu)" % (what, len(ms)))
    m = ms[0]
    return m.start() + start, m.end() + start, m


def func_body(s, header_regex, what):
    """Başlığı header_regex ile eşleşen fonksiyonun gövde aralığını (açılış '{', kapanış '}') verir."""
    hs, he, _ = find_one(s, header_regex, what)
    j = hs
    # Başlığın parametre listesini atla, sonra gövdenin '{' karakterine git.
    p = s.find("(", hs)
    p_close = match_brace(s, p)
    b = s.find("{", p_close)
    return b, match_brace(s, b)


def append_to_class(s, class_regex, text, what):
    cs, ce, _ = find_one(s, class_regex, what)
    p = s.find("(", cs)
    nl = s.find("{", cs)
    # kurucu parametreleri varsa önce onları atla
    if p != -1 and (nl == -1 or p < nl):
        nl = s.find("{", match_brace(s, p))
    end = match_brace(s, nl)
    return s[:end].rstrip() + "\n" + text.rstrip("\n") + "\n" + s[end:]


def insert_before_line(s, line_regex, new_line, what, start=0, end=None):
    ls, le, m = find_one(s, line_regex, what, start, end)
    indent = re.match(r"[ \t]*", m.group(0)).group(0)
    return s[:ls] + indent + new_line + "\n" + s[ls:], len(indent + new_line) + 1


def insert_after_line(s, line_regex, new_line, what, start=0, end=None):
    ls, le, m = find_one(s, line_regex, what, start, end)
    indent = re.match(r"[ \t]*", m.group(0)).group(0)
    nl = s.find("\n", le)
    le = len(s) if nl < 0 else nl + 1
    return s[:le] + indent + new_line + "\n" + s[le:]


def addon_text(name):
    return open(os.path.join(ADDON, "append", name), encoding="utf-8").read()


# --------------------------------------------------------------------------------------------
# Kancalar
# --------------------------------------------------------------------------------------------
def hook_runner():
    """ExtractorLink -> LocalScraperResult dönüşümünde dublajları kaydet."""
    rel = "app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt"
    s = read(rel)
    b, e = func_body(s, r"fun ExtractorLink\.toLocalScraperResult\(", "toLocalScraperResult")
    s, _ = insert_before_line(s, r"^[ \t]*return LocalScraperResult\(", "registerDubAudios(this)",
                              "return LocalScraperResult(", b, e)
    write(rel, s)


def split_params(text):
    out, depth, cur, i = [], 0, "", 0
    while i < len(text):
        c = text[i]
        if c == "-" and text.startswith("->", i):
            cur += "->"
            i += 2
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        if c == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        out.append(cur)
    return [p.strip("\n") for p in out if p.strip()]


def hook_media_source_factory():
    rel = PLAYER + "PlayerMediaSourceFactory.kt"
    s = read(rel)

    # 1) createMediaSource(...) -> createBaseMediaSource(... , useDiskCache) + üretilen sarmalayıcı.
    m = re.search(r"^( *)fun createMediaSource\(\s*\n\s*context: Context,", s, re.M)
    if not m or len(re.findall(r"^ *fun createMediaSource\(\s*\n\s*context: Context,", s, re.M)) != 1:
        raise HookError("createMediaSource(context: Context, ...) imzası bulunamadı / birden fazla")
    ind = m.group(1)
    p = s.find("(", m.start())
    pc = match_brace(s, p)
    tail = re.match(r"\s*:\s*MediaSource\s*\{", s[pc + 1:])
    if not tail:
        raise HookError("createMediaSource dönüş tipi MediaSource değil; sarmalayıcı üretilemedi")
    params_src = s[p + 1:pc]
    params = split_params(params_src)

    def pname(seg):
        clean = re.sub(r"//[^\n]*", "", seg)
        clean = re.sub(r"/\*.*?\*/", "", clean, flags=re.S)
        mm = re.match(r"\s*(\w+)\s*:", clean)
        if not mm:
            raise HookError("parametre adı okunamadı: %r" % seg[:60])
        return mm.group(1)

    names = [pname(x) for x in params]
    for need in ("context",):
        if need not in names:
            raise HookError("createMediaSource içinde '%s' parametresi yok" % need)
    for taken in ("externalAudios", "useDiskCache"):
        if taken in names:
            raise HookError("upstream artık '%s' parametresine sahip" % taken)

    def clean_seg(seg):  # sarmalayıcıda yorum satırlarını at
        keep = [ln for ln in seg.split("\n") if not ln.strip().startswith("//")]
        return "\n".join(keep).rstrip()

    wrapper_params = ",\n".join(clean_seg(x) for x in params)
    base_params = ",\n".join(x.rstrip() for x in params)
    call_args = ",\n".join("%s        %s = %s" % (ind, n, n) for n in names)
    wrapper = (
        "%sfun createMediaSource(\n%s,\n"
        "%s    externalAudios: List<com.nuvio.tv.domain.model.ExternalAudio>? = null,\n"
        "%s    // false: ölçüm probları doğrudan ağdan okur, VOD disk önbelleğine dokunmaz.\n"
        "%s    useDiskCache: Boolean = true\n"
        "%s): MediaSource {\n"
        "%s    val base = createBaseMediaSource(\n%s,\n%s        useDiskCache = useDiskCache\n%s    )\n"
        "%s    return mergeDubAudios(context, base, externalAudios ?: dubExternalAudios)\n"
        "%s}\n\n"
        % (ind, wrapper_params, ind, ind, ind, ind, ind, call_args, ind, ind, ind, ind)
    )
    base_head = "%sprivate fun createBaseMediaSource(\n%s,\n%s    useDiskCache: Boolean = true\n%s)" % (
        ind, base_params, ind, ind)
    s = s[:m.start()] + wrapper + base_head + s[pc + 1:]

    # 2) gövdede disk önbelleği koruması (yalnızca createBaseMediaSource içinde)
    bs, be = func_body(s, r"private fun createBaseMediaSource\(", "createBaseMediaSource")

    def sub_in_body(pattern, repl, what):
        nonlocal s, bs, be
        a, z, mm = find_one(s, pattern, what, bs, be)
        new = repl(mm) if callable(repl) else repl
        s = s[:a] + new + s[z:]
        be += len(new) - (z - a)

    sub_in_body(r"val useVodCache = ENABLE_VOD_CACHE",
                "val useVodCache = useDiskCache && ENABLE_VOD_CACHE", "useVodCache")

    def wrap_evict(mm):
        i = mm.group(1)
        body = "".join("%s    %s\n" % (i, ln.strip()) for ln in mm.group(0).strip("\n").split("\n"))
        return "%sif (useDiskCache) {\n%s%s}\n" % (i, body, i)

    sub_in_body(r"^([ \t]*)pendingEvictionJob\?\.cancel\(\)\n\1pendingEvictionJob = null\n"
                r"\1currentVodCacheUrl = url\n\1currentVodCacheResolvedUrl = null\n"
                r"\1vodCacheEvictor\?\.resetTrimAnchor\(\)\n", wrap_evict, "VOD eviction bloğu")
    sub_in_body(r"currentVodCacheActive = false(?=\n[ \t]*progressiveUpstreamFactory\n[ \t]*\}\n[ \t]*//[^\n]*buffer only pays)",
                "if (useDiskCache) currentVodCacheActive = false", "currentVodCacheActive (else)")
    sub_in_body(r"val progressiveFactory: DataSource\.Factory = if \(currentVodCacheActive\)",
                "val progressiveFactory: DataSource.Factory = if (useDiskCache && currentVodCacheActive)",
                "progressiveFactory")

    # 3) sınıf sonuna dublaj üyeleri
    s = append_to_class(s, r"^internal class PlayerMediaSourceFactory\b", addon_text("PlayerMediaSourceFactory.members.txt"),
                        "PlayerMediaSourceFactory sınıfı")
    write(rel, s)


def hook_controller():
    rel = PLAYER + "PlayerRuntimeController.kt"
    s = read(rel)
    s = append_to_class(s, r"^class PlayerRuntimeController\b", addon_text("PlayerRuntimeController.members.txt"),
                        "PlayerRuntimeController sınıfı")
    write(rel, s)


def hook_initialization():
    rel = PLAYER + "PlayerRuntimeControllerInitialization.kt"
    s = read(rel)
    # (a) motor seçimi: dublaj varsa ExoPlayer
    s, _ = insert_before_line(
        s, r"^[ \t]*runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine[ \t]*$",
        "effectiveInternalPlayerEngine = dubForceExoEngine(effectiveInternalPlayerEngine)",
        "runtimeInternalPlayerEngineOverride atama satırı")
    # (b) ilk STATE_READY: otomatik dublaj arama
    a, z, mm = find_one(
        s, r"if \(playbackState == Player\.STATE_READY\) \{\n([ \t]*)mediaSourceFactory\.unlockStartupPrefetch\(\)\n",
        "STATE_READY + unlockStartupPrefetch")
    s = s[:z] + mm.group(1) + "dubOnPlayerReady()\n" + s[z:]
    # (c) ses çıkışı: dublaj uzatma
    a, z, mm = find_one(s, r"val baseAudioSink = builder\.build\(\)", "baseAudioSink")
    s = s[:a] + "val baseAudioSink = DubStretchAudioSink(builder.build())" + s[z:]
    write(rel, s)


def hook_streams():
    rel = PLAYER + "PlayerRuntimeControllerStreams.kt"
    s = read(rel)
    b, e = func_body(s, r"fun PlayerRuntimeController\.applyStreamMetadata\(", "applyStreamMetadata")
    s = insert_after_line(s, r"^[ \t]*streamSubtitles = stream\.subtitles[ \t]*$",
                          "streamExternalAudios = StreamSidecarAudio.forUrl(stream.url.orEmpty())",
                          "streamSubtitles = stream.subtitles", b, e)
    write(rel, s)


def hook_tracks():
    rel = PLAYER + "PlayerRuntimeControllerTracks.kt"
    s = read(rel)
    b, e = func_body(s, r"fun PlayerRuntimeController\.updateAvailableTracks\(", "updateAvailableTracks")
    a, z, mm = find_one(s, r"^([ \t]*)val baseName = format\.label \?: langDisplay \?: ", "val baseName = ...", b, e)
    s = (s[:a] + mm.group(1) + "val externalAudio = dubExternalAudioFor(trackGroup.mediaTrackGroup.id)\n"
         + mm.group(1) + "val baseName = externalAudio?.label ?: format.label ?: langDisplay ?: " + s[z:])
    b, e = func_body(s, r"fun PlayerRuntimeController\.updateAvailableTracks\(", "updateAvailableTracks")
    start = s.index("val externalAudio = dubExternalAudioFor")
    j = s.find("language = format.language,", start)
    if j < 0 or j > e:
        raise HookError("TrackInfo(language = format.language,) satırı bulunamadı")
    s = s[:j] + "language = externalAudio?.lang ?: format.language," + s[j + len("language = format.language,"):]
    write(rel, s)


def hook_audio_overlay():
    rel = PLAYER + "AudioSelectionOverlay.kt"
    s = read(rel)
    b, e = func_body(s, r"fun AudioTracksContent\(", "AudioTracksContent")
    # (a) liste boşken: 'return' öncesi
    a, z, mm = find_one(s, r"^([ \t]*)return[ \t]*$", "AudioTracksContent içindeki return", b, e)
    s = s[:a] + mm.group(1) + "DubAudioRows(rightFocusRequester)\n" + s[a:]
    # (b) LazyColumn'un içeriğinin sonu
    b, e = func_body(s, r"fun AudioTracksContent\(", "AudioTracksContent")
    ls = s.find("LazyColumn(", b)
    if ls < 0 or ls > e:
        raise HookError("AudioTracksContent içinde LazyColumn yok")
    lp = s.find("(", ls)
    lb = s.find("{", match_brace(s, lp))
    le = match_brace(s, lb)
    line_start = s.rfind("\n", 0, le) + 1
    brace_indent = re.match(r"[ \t]*", s[line_start:]).group(0)
    s = s[:line_start] + brace_indent + '    item(key = "dub_audio_rows") { DubAudioRows(rightFocusRequester) }\n' + s[line_start:]
    write(rel, s)


HOOKS = [
    ("ExternalExtensionRunner", hook_runner),
    ("PlayerMediaSourceFactory", hook_media_source_factory),
    ("PlayerRuntimeController", hook_controller),
    ("PlayerRuntimeControllerInitialization", hook_initialization),
    ("PlayerRuntimeControllerStreams", hook_streams),
    ("PlayerRuntimeControllerTracks", hook_tracks),
    ("AudioSelectionOverlay", hook_audio_overlay),
]

for name, fn in HOOKS:
    try:
        fn()
        print("hook OK    %s" % name)
    except HookError as ex:
        errors.append(name)
        print("::error::kanca YERLEŞMEDİ  %s  -> %s" % (name, ex))

sys.exit(2 if errors else 0)
