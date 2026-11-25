package com.nuvio.tv.core.dub

import android.content.Context

/**
 * The two "automatic" switches of the audio menu. Stored on the device (SharedPreferences), so a
 * choice stays until the user changes it again: across videos, app restarts and app updates.
 */
internal object DubAutoSettings {
    private const val PREFS = "nuvio_dub"
    private const val KEY_AUTO_SEARCH = "auto_search_enabled"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"

    /** Default on: the dub is looked up by itself when playback is ready (the original behaviour). */
    private const val DEFAULT_AUTO_SEARCH = true

    /** Default off: measuring reads parts of the original stream, so it is opt-in. */
    private const val DEFAULT_AUTO_SYNC = false

    fun isAutoSearchEnabled(context: Context): Boolean = read(context, KEY_AUTO_SEARCH, DEFAULT_AUTO_SEARCH)

    fun setAutoSearchEnabled(context: Context, enabled: Boolean) = write(context, KEY_AUTO_SEARCH, enabled)

    fun isAutoSyncEnabled(context: Context): Boolean = read(context, KEY_AUTO_SYNC, DEFAULT_AUTO_SYNC)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) = write(context, KEY_AUTO_SYNC, enabled)

    private fun read(context: Context, key: String, default: Boolean): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, default)
    }.getOrDefault(default)

    private fun write(context: Context, key: String, value: Boolean) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        }
    }
}
