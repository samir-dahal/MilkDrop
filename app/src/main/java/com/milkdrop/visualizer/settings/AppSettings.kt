package com.milkdrop.visualizer.settings

import android.content.Context

/** Persists the overlay's toggle states (everything except momentary actions like Media) and the last preset across launches. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var autoAdvanceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    var hardCutEnabled: Boolean
        get() = prefs.getBoolean(KEY_HARD_CUT, false)
        set(value) = prefs.edit().putBoolean(KEY_HARD_CUT, value).apply()

    var fpsIndex: Int
        get() = prefs.getInt(KEY_FPS_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_FPS_INDEX, value).apply()

    var qualityIndex: Int
        get() = prefs.getInt(KEY_QUALITY_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_QUALITY_INDEX, value).apply()

    var internalAudioSource: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_SOURCE_INTERNAL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_SOURCE_INTERNAL, value).apply()

    /** Full path of the preset showing when the app last ran, to resume on it at launch. */
    var lastPresetPath: String?
        get() = prefs.getString(KEY_LAST_PRESET_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_PRESET_PATH, value).apply()

    private companion object {
        const val PREFS_NAME = "milkdrop_settings"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_AUTO_ADVANCE = "auto_advance_enabled"
        const val KEY_HARD_CUT = "hard_cut_enabled"
        const val KEY_FPS_INDEX = "fps_index"
        const val KEY_QUALITY_INDEX = "quality_index"
        const val KEY_AUDIO_SOURCE_INTERNAL = "audio_source_internal"
        const val KEY_LAST_PRESET_PATH = "last_preset_path"
    }
}
