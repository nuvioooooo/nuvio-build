package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.ExternalAudio

/**
 * Harici ses (ör. dublaj) kayıt defteri. Eklenti çalıştırıcısı bir bağlantı için dublajları
 * oynatma URL'siyle kaydeder; oynatıcı aynı URL ile geri okur. Böylece Stream / LocalScraperResult
 * gibi Nuvio veri sınıflarına alan eklemek gerekmez. Yalnızca bellekte, en son 64 URL tutulur.
 */
internal object StreamSidecarAudio {
    private const val MAX_ENTRIES = 64

    private val registry = object : LinkedHashMap<String, List<ExternalAudio>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExternalAudio>>?): Boolean =
            size > MAX_ENTRIES
    }

    // "#.m3u8" gibi ipuçları anahtara karışmasın.
    private fun key(url: String): String = url.substringBefore('#')

    fun register(url: String?, audios: List<ExternalAudio>) {
        val u = url?.takeIf { it.isNotBlank() } ?: return
        synchronized(registry) {
            if (audios.isEmpty()) registry.remove(key(u)) else registry[key(u)] = audios
        }
    }

    /** Eski API ile uyumluluk. */
    fun set(url: String?, audios: List<ExternalAudio>) = register(url, audios)

    fun forUrl(url: String): List<ExternalAudio> {
        if (url.isBlank()) return emptyList()
        return synchronized(registry) { registry[key(url)].orEmpty() }
    }
}
