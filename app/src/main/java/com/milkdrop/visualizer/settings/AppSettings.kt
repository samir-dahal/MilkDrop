package com.milkdrop.visualizer.settings

import android.content.Context
import com.milkdrop.visualizer.audio.AudioInput

/** Persists the overlay's toggle states (everything except momentary actions like Media) and the last preset across launches. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var swipeGesturesEnabled: Boolean
        get() = prefs.getBoolean("swipe_gestures_enabled", true)
        set(value) = prefs.edit().putBoolean("swipe_gestures_enabled", value).apply()

    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var autoAdvanceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    var presetDurationSeconds: Int
        get() {
            val legacy = PresetTiming.legacyDurations.getOrElse(prefs.getInt(KEY_PRESET_DURATION_INDEX, 1)) { 15 }
            return prefs.getInt(KEY_PRESET_DURATION, legacy).takeIf { it in PresetTiming.durations } ?: 15
        }
        set(value) = prefs.edit().putInt(KEY_PRESET_DURATION, value.takeIf { it in PresetTiming.durations } ?: 15)
            .remove(KEY_PRESET_DURATION_INDEX).apply()

    var additionalRandomSeconds: Int
        get() = prefs.getInt(KEY_RANDOM_TIME, 0).takeIf { it in PresetTiming.additionalRandom } ?: 0
        set(value) = prefs.edit().putInt(KEY_RANDOM_TIME, value.takeIf { it in PresetTiming.additionalRandom } ?: 0).apply()

    // Preserve the old Instant toggle until a duration is saved in the unified control.
    var transitionSeconds: Float
        get() = if (prefs.getBoolean(KEY_HARD_CUT, false)) 0f else prefs.getFloat(KEY_TRANSITION_TIME, 1.7f).takeIf { seconds -> PresetTiming.transitions.any { it == seconds } } ?: 1.7f
        set(value) = prefs.edit().putFloat(KEY_TRANSITION_TIME,
            value.takeIf { seconds -> PresetTiming.transitions.any { it == seconds } } ?: 1.7f).remove(KEY_HARD_CUT).apply()

    var beatCutsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEAT_CUTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BEAT_CUTS, value).apply()

    var showPresetNameDuringTransitions: Boolean
        get() = prefs.getBoolean(KEY_PRESET_NAME_TRANSITIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_PRESET_NAME_TRANSITIONS, value).apply()

    var fpsIndex: Int
        get() {
            val legacy = FrameRates.legacy.getOrElse(prefs.getInt(KEY_FPS_INDEX, 0)) { 30 }
            return FrameRates.indexFor(prefs.getInt(KEY_FPS_VALUE, legacy))
        }
        set(value) = prefs.edit().putInt(KEY_FPS_VALUE, FrameRates.options.getOrElse(value) { 30 })
            .remove(KEY_FPS_INDEX).apply()

    var screenDependentRendering: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_DEPENDENT_RENDERING, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_DEPENDENT_RENDERING, value).apply()

    var qualityIndex: Int
        get() {
            if (!prefs.contains(KEY_QUALITY_INDEX)) return QUALITY_AUTO
            val stored = prefs.getInt(KEY_QUALITY_INDEX, QUALITY_AUTO)
            if (prefs.getInt(KEY_QUALITY_SCHEMA, 1) < QUALITY_SCHEMA_VERSION) {
                // The previous three entries were High, Medium and Low respectively. Keep that
                // intent when moving to the expanded quality list instead of silently changing
                // existing installations to Auto.
                val previousTiers = intArrayOf(0, 2, 3, 5, 6, 8, 10, 11)
                val migrated = if (prefs.getInt(KEY_QUALITY_SCHEMA, 1) == 3) {
                    previousTiers[stored.coerceIn(0, 7)]
                } else if (prefs.getInt(KEY_QUALITY_SCHEMA, 1) == 2) {
                    previousTiers[if (stored == 0) 0 else stored.coerceIn(1, 6) + 1]
                } else when (stored.coerceIn(0, 2)) {
                    0 -> QUALITY_VERY_HIGH
                    1 -> QUALITY_MEDIUM
                    else -> QUALITY_LOW
                }
                prefs.edit().putInt(KEY_QUALITY_INDEX, migrated)
                    .putInt(KEY_QUALITY_SCHEMA, QUALITY_SCHEMA_VERSION).apply()
                return migrated
            }
            return stored.coerceIn(QUALITY_AUTO, QUALITY_ULTRA_HIGH)
        }
        set(value) = prefs.edit().putInt(KEY_QUALITY_INDEX,
            value.coerceIn(QUALITY_AUTO, QUALITY_ULTRA_HIGH))
            .putInt(KEY_QUALITY_SCHEMA, QUALITY_SCHEMA_VERSION).apply()

    /** Per-point mesh dimensions are deliberately separate from the output-resolution quality. */
    var meshColumns: Int
        get() = prefs.getInt(KEY_MESH_COLUMNS, DEFAULT_MESH_COLUMNS).coerceIn(MIN_MESH_SIZE, MAX_MESH_SIZE)
        set(value) = prefs.edit().putInt(KEY_MESH_COLUMNS, value.coerceIn(MIN_MESH_SIZE, MAX_MESH_SIZE)).apply()

    var meshRows: Int
        get() = prefs.getInt(KEY_MESH_ROWS, DEFAULT_MESH_ROWS).coerceIn(MIN_MESH_SIZE, MAX_MESH_SIZE)
        set(value) = prefs.edit().putInt(KEY_MESH_ROWS, value.coerceIn(MIN_MESH_SIZE, MAX_MESH_SIZE)).apply()

    var audioSensitivity: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_AUDIO_SENSITIVITY, AudioTuning.SENSITIVITY_DEFAULT),
            AudioTuning.SENSITIVITY_MAX, AudioTuning.SENSITIVITY_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_AUDIO_SENSITIVITY,
            AudioTuning.bounded(value, AudioTuning.SENSITIVITY_MAX, AudioTuning.SENSITIVITY_DEFAULT)).apply()

    var fftShaderScaling: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_FFT_SCALING, AudioTuning.FFT_SCALING_DEFAULT),
            AudioTuning.FFT_SCALING_MAX, AudioTuning.FFT_SCALING_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_FFT_SCALING,
            AudioTuning.bounded(value, AudioTuning.FFT_SCALING_MAX, AudioTuning.FFT_SCALING_DEFAULT)).apply()

    var fftShaderNoiseFloor: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_FFT_NOISE_FLOOR, AudioTuning.FFT_NOISE_FLOOR_DEFAULT),
            AudioTuning.FFT_NOISE_FLOOR_MAX, AudioTuning.FFT_NOISE_FLOOR_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_FFT_NOISE_FLOOR,
            AudioTuning.bounded(value, AudioTuning.FFT_NOISE_FLOOR_MAX, AudioTuning.FFT_NOISE_FLOOR_DEFAULT)).apply()

    /** Stereo playback capture is explicitly opt-in; legacy Internal never prompts. */
    var stereoInternalAudio: Boolean
        get() = prefs.getBoolean("stereo_internal_audio", false)
        set(value) = prefs.edit().putBoolean("stereo_internal_audio", value).apply()

    /** Reads previous audio choices and maps both playback modes to the retained Internal source. */
    var audioInput: AudioInput
        get() = prefs.getString(KEY_AUDIO_INPUT, null)
            ?.let { saved ->
                when (saved) {
                    "PHONE_AUDIO", "OUTPUT_MIX" -> AudioInput.INTERNAL
                    else -> AudioInput.entries.firstOrNull { it.name == saved }
                }
            }
            ?: if (prefs.getBoolean(KEY_AUDIO_SOURCE_INTERNAL, false)) AudioInput.INTERNAL else AudioInput.MIC
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
        const val KEY_PRESET_DURATION = "preset_duration_seconds"
        const val KEY_RANDOM_TIME = "additional_random_seconds"
        const val KEY_TRANSITION_TIME = "transition_seconds"
        const val KEY_BEAT_CUTS = "beat_cuts_enabled"
        const val KEY_PRESET_NAME_TRANSITIONS = "show_preset_name_during_transitions"
        const val KEY_FPS_INDEX = "fps_index"
        const val KEY_FPS_VALUE = "frames_per_second"
        const val KEY_QUALITY_INDEX = "quality_index"
        const val KEY_QUALITY_SCHEMA = "quality_schema"
        const val KEY_MESH_COLUMNS = "mesh_columns"
        const val KEY_MESH_ROWS = "mesh_rows"
        const val KEY_AUDIO_SENSITIVITY = "audio_sensitivity"
        const val KEY_FFT_SCALING = "fft_shader_scaling"
        const val KEY_FFT_NOISE_FLOOR = "fft_shader_noise_floor"
        const val KEY_SCREEN_DEPENDENT_RENDERING = "screen_dependent_rendering"
        const val KEY_AUDIO_SOURCE_INTERNAL = "audio_source_internal"
        const val KEY_AUDIO_INPUT = "audio_input"
        const val KEY_LAST_PRESET_PATH = "last_preset_path"

        const val QUALITY_AUTO = 0
        const val QUALITY_LOW = 5
        const val QUALITY_MEDIUM = 6
        const val QUALITY_VERY_HIGH = 10
        const val QUALITY_ULTRA_HIGH = 11
        const val QUALITY_SCHEMA_VERSION = 4
        const val MIN_MESH_SIZE = 8
        const val MAX_MESH_SIZE = 300
        const val DEFAULT_MESH_COLUMNS = 32
        const val DEFAULT_MESH_ROWS = 24
    }
}
