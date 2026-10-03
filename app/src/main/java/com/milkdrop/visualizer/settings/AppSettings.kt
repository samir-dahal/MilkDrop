package com.milkdrop.visualizer.settings

import android.content.Context
import com.milkdrop.visualizer.audio.AudioInput

/** Persists the overlay's toggle states (everything except momentary actions like Media) and the last preset across launches. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var autoAdvanceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    var presetDurationIndex: Int
        get() = prefs.getInt(KEY_PRESET_DURATION_INDEX, 1)
        set(value) = prefs.edit().putInt(KEY_PRESET_DURATION_INDEX, value).apply()

    var beatCutsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEAT_CUTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BEAT_CUTS, value).apply()

    var hardCutEnabled: Boolean
        get() = prefs.getBoolean(KEY_HARD_CUT, false)
        set(value) = prefs.edit().putBoolean(KEY_HARD_CUT, value).apply()

    var fpsIndex: Int
        get() = prefs.getInt(KEY_FPS_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_FPS_INDEX, value).apply()

    var qualityIndex: Int
        get() = prefs.getInt(KEY_QUALITY_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_QUALITY_INDEX, value).apply()

    /** Falls back to the older on/off setting (phone audio vs mic) saved before there were three. */
    var audioInput: AudioInput
        get() = prefs.getString(KEY_AUDIO_INPUT, null)
            ?.let { saved -> AudioInput.entries.firstOrNull { it.name == saved } }
            ?: if (prefs.getBoolean(KEY_AUDIO_SOURCE_INTERNAL, false)) AudioInput.PHONE_AUDIO else AudioInput.MIC
        set(value) = prefs.edit().putString(KEY_AUDIO_INPUT, value.name).remove(KEY_AUDIO_SOURCE_INTERNAL).apply()

    /** Full path of the preset showing when the app last ran, to resume on it at launch. */
    var lastPresetPath: String?
        get() = prefs.getString(KEY_LAST_PRESET_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_PRESET_PATH, value).apply()

    private companion object {
        const val PREFS_NAME = "milkdrop_settings"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_AUTO_ADVANCE = "auto_advance_enabled"
        const val KEY_HARD_CUT = "hard_cut_enabled"
        const val KEY_PRESET_DURATION_INDEX = "preset_duration_index"
        const val KEY_BEAT_CUTS = "beat_cuts_enabled"
        const val KEY_FPS_INDEX = "fps_index"
        const val KEY_QUALITY_INDEX = "quality_index"
        const val KEY_AUDIO_SOURCE_INTERNAL = "audio_source_internal"
        const val KEY_AUDIO_INPUT = "audio_input"
        const val KEY_LAST_PRESET_PATH = "last_preset_path"
    }
}
