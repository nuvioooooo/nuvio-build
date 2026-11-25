package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.nuvio.tv.domain.model.ExternalAudio
import com.nuvio.tv.ui.screens.player.StreamSidecarAudio

private const val DUB_TAG = "ExtExtensionRunner"

/**
 * ExtractorLink.audioTracks değerini yansıma (reflection) ile okur; böylece derleme CloudStream
 * kütüphanesinin bu alanı sunmasına bağlı olmaz. "#.m3u8" URL ipucu korunur; oynatıcı onu temizleyip
 * HLS yükleyicisini seçmekte kullanır. Bulunan dublajlar oynatma URL'si anahtarıyla kaydedilir.
 */
internal fun registerDubAudios(link: ExtractorLink) {
    val audios = link.readDubAudios()
    if (audios.isEmpty()) return
    Log.i(DUB_TAG, "EXTERNAL_AUDIO: ${audios.size} track(s) on link source=${link.source}")
    StreamSidecarAudio.register(link.url, audios)
}

private fun ExtractorLink.readDubAudios(): List<ExternalAudio> {
    return runCatching {
        val getter = javaClass.methods.find { it.name == "getAudioTracks" } ?: return@runCatching emptyList()
        val files = getter.invoke(this) as? List<*> ?: return@runCatching emptyList()
        files.mapNotNull { file ->
            if (file == null) return@mapNotNull null
            val audioUrl = (file.javaClass.methods.find { it.name == "getUrl" }?.invoke(file) as? String)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val audioHeaders = (file.javaClass.methods.find { it.name == "getHeaders" }?.invoke(file)
                as? Map<String, String>)?.ifEmpty { null }
            ExternalAudio(
                url = audioUrl,
                lang = "tr",
                label = "Türkçe Dublaj",
                headers = audioHeaders
            )
        }
    }.onFailure { Log.w(DUB_TAG, "audioTracks read failed: ${it.message}") }
        .getOrDefault(emptyList())
}
