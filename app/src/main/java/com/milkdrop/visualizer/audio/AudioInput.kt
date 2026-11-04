package com.milkdrop.visualizer.audio

/** Where the visuals' audio comes from, as offered in Settings. */
enum class AudioInput {
    /** The microphone: works with anything, picks up the room. */
    MIC,

    /** Stereo playback capture on Android 10+, with legacy mono output-mix fallback. */
    INTERNAL,

    /** Prefer captured playback, using the microphone while Internal is silent or unavailable. */
    AUTOMATIC,
}
