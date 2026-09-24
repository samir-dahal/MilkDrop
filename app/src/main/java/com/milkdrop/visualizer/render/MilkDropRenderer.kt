package com.milkdrop.visualizer.render

import android.opengl.GLSurfaceView
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * All methods here (besides [handle] reads) must run on the GLSurfaceView's GL thread —
 * callers outside the renderer should wrap calls in `GLSurfaceView.queueEvent { ... }`.
 */
class MilkDropRenderer(
    private val texturesDir: File,
) : GLSurfaceView.Renderer {

    @Volatile
    var handle: Long = 0L
        private set

    /** 0 means uncapped. Read/written from different threads, hence volatile — no locking needed for a single primitive. */
    @Volatile
    var targetFps: Int = DEFAULT_TARGET_FPS
        set(value) {
            field = value
            targetFrameIntervalNanos = if (value <= 0) 0L else 1_000_000_000L / value
        }

    @Volatile
    private var targetFrameIntervalNanos: Long = 1_000_000_000L / DEFAULT_TARGET_FPS

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        handle = ProjectMBridge.nativeCreate()
        ProjectMBridge.nativeSetTextureSearchPaths(handle, arrayOf(texturesDir.absolutePath))
        ProjectMBridge.nativeSetPresetDuration(handle, DEFAULT_PRESET_DURATION_SECONDS)
        ProjectMBridge.nativeSetSoftCutDuration(handle, SOFT_CUT_DURATION_SECONDS)
        ProjectMBridge.nativeSetShuffle(handle, true)
        // No preset loaded yet — projectM renders a blank frame until the background
        // filesystem scan (thousands of files) hands off results; see loadScannedPresets().
        // ("idle://" looked like a natural placeholder, but it requires a non-empty connected
        // playlist and throws otherwise, so it can't be used at this point.)
    }

    /** Called once the caller has finished scanning the presets directory on a background thread. */
    fun loadScannedPresets(paths: List<String>) {
        if (paths.isNotEmpty()) {
            ProjectMBridge.nativeAddPresets(handle, paths.toTypedArray(), false)
            ProjectMBridge.nativePlayNext(handle, false)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        ProjectMBridge.nativeSetWindowSize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNanos = System.nanoTime()
        ProjectMBridge.nativeRenderFrame(handle)

        val intervalNanos = targetFrameIntervalNanos
        if (intervalNanos > 0) {
            val remainingNanos = intervalNanos - (System.nanoTime() - frameStartNanos)
            if (remainingNanos > 0) {
                try {
                    Thread.sleep(remainingNanos / 1_000_000, (remainingNanos % 1_000_000).toInt())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    fun playNext() {
        ProjectMBridge.nativePlayNext(handle, false)
    }

    fun playPrevious() {
        ProjectMBridge.nativePlayPrevious(handle, false)
    }

    fun setShuffle(enabled: Boolean) {
        ProjectMBridge.nativeSetShuffle(handle, enabled)
    }

    fun playlistPosition(): Int = ProjectMBridge.nativeGetPlaylistPosition(handle)

    fun jumpToPreset(position: Int) {
        ProjectMBridge.nativeSetPlaylistPosition(handle, position, false)
    }

    fun playlistItems(): Array<String> = ProjectMBridge.nativeGetPlaylistItems(handle)

    fun feedPcm(samples: ShortArray, frameCount: Int, channels: Int) {
        val currentHandle = handle
        if (currentHandle != 0L) {
            ProjectMBridge.nativeFeedPcmInt16(currentHandle, samples, frameCount, channels)
        }
    }

    fun release() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            ProjectMBridge.nativeDestroy(currentHandle)
            handle = 0L
        }
    }

    private companion object {
        const val DEFAULT_PRESET_DURATION_SECONDS = 15.0
        const val SOFT_CUT_DURATION_SECONDS = 1.5
        const val DEFAULT_TARGET_FPS = 30
    }
}
