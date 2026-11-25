@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.dub.DubAutoSettings
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.ExternalAudio
import java.lang.ref.WeakReference

/**
 * Ses menüsündeki dublaj satırlarının oynatıcıya köprüsü. En son oluşturulan
 * [PlayerRuntimeController] kendini buraya bağlar (bkz. append/PlayerRuntimeController.members.txt).
 */
internal object DubUi {
    private var ref: WeakReference<PlayerRuntimeController>? = null

    fun attach(controller: PlayerRuntimeController) {
        ref = WeakReference(controller)
    }

    val controller: PlayerRuntimeController? get() = ref?.get()
}

/** Ses menüsüne eklenen iki satır: "Türkçe dublaj ara" ve "Dublajı otomatik senkronla" (+ anahtarları). */
@Composable
internal fun DubAudioRows(rightFocusRequester: FocusRequester) {
    val context = LocalContext.current
    var autoSearch by remember { mutableStateOf(DubAutoSettings.isAutoSearchEnabled(context)) }
    var autoSync by remember { mutableStateOf(DubAutoSettings.isAutoSyncEnabled(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DubActionRow(
            label = "Türkçe dublaj ara",
            icon = Icons.Default.Search,
            onClick = {
                DubUi.controller?.let {
                    it.searchDubAudio(manual = true)
                    it.onEvent(PlayerEvent.OnDismissTransientOverlay)
                }
            },
            switchChecked = autoSearch,
            onSwitchChange = {
                autoSearch = it
                DubAutoSettings.setAutoSearchEnabled(context, it)
            },
            rightFocusRequester = rightFocusRequester
        )
        DubActionRow(
            label = "Dublajı otomatik senkronla",
            icon = Icons.Default.Refresh,
            onClick = {
                DubUi.controller?.let {
                    it.syncDubAudio()
                    it.onEvent(PlayerEvent.OnDismissTransientOverlay)
                }
            },
            switchChecked = autoSync,
            onSwitchChange = {
                autoSync = it
                DubAutoSettings.setAutoSyncEnabled(context, it)
            },
            rightFocusRequester = rightFocusRequester
        )
    }
}

/** Dublaj varsa oynatıcıyı ExoPlayer'a zorlar (harici ses yalnızca MergingMediaSource ile birleşir). */
internal fun PlayerRuntimeController.dubForceExoEngine(engine: InternalPlayerEngine): InternalPlayerEngine {
    var result = engine
    if (streamExternalAudios.isNotEmpty() && result != InternalPlayerEngine.EXOPLAYER) {
        Log.i(PlayerRuntimeController.TAG, "EXTERNAL_AUDIO: forcing EXOPLAYER (was $result)")
        result = InternalPlayerEngine.EXOPLAYER
    }
    Log.i(PlayerRuntimeController.TAG, "EXTERNAL_AUDIO: count=${streamExternalAudios.size} engine=$result")
    return result
}

/** İlk STATE_READY'de bir kez dublaj aramasını başlatır. */
internal fun PlayerRuntimeController.dubOnPlayerReady() {
    if (!dubAutoSearchStarted) {
        dubAutoSearchStarted = true
        searchDubAudio(manual = false)
    }
}

/**
 * MergingMediaPeriod grup kimliklerinin başına alt kaynak sırasını yazar ("0:", "1:" …);
 * 0 ana akış, 1.. harici ses kaynaklarıdır.
 */
internal fun PlayerRuntimeController.dubExternalAudioFor(groupId: String?): ExternalAudio? {
    if (streamExternalAudios.isEmpty()) return null
    val child = groupId?.substringBefore(':')?.toIntOrNull() ?: 0
    return if (child >= 1) streamExternalAudios.getOrNull(child - 1) else null
}
