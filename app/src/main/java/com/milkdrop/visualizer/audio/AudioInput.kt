package com.milkdrop.visualizer.audio

/** Where the visuals' audio comes from, as offered in Settings. */
enum class AudioInput {
    /** The microphone: works with anything, picks up the room. */
    MIC,

    /** Playback capture: best quality, needs Android's screen-capture consent on each start. */
    PHONE_AUDIO,

    /** Visualizer on the output mix: phone audio with no prompt, at lower detail. */
    OUTPUT_MIX,
}
