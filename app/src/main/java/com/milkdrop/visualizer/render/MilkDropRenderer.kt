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
    private val presetsDir: File,
    private val texturesDir: File,
) : GLSurfaceView.Renderer {

    @Volatile
    var handle: Long = 0L
        private set

    private var shuffleMode = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        handle = ProjectMBridge.nativeCreate()
        ProjectMBridge.nativeSetTextureSearchPaths(handle, arrayOf(texturesDir.absolutePath))
        ProjectMBridge.nativeSetPresetDuration(handle, DEFAULT_PRESET_DURATION_SECONDS)
        ProjectMBridge.nativeAddPlaylistPath(handle, presetsDir.absolutePath, true, false)
        ProjectMBridge.nativeSetShuffle(handle, shuffleMode)
        ProjectMBridge.nativePlayNext(handle, false)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        ProjectMBridge.nativeSetWindowSize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        ProjectMBridge.nativeRenderFrame(handle)
    }

    fun playNext() {
        shuffleMode = false
        ProjectMBridge.nativeSetShuffle(handle, shuffleMode)
        ProjectMBridge.nativePlayNext(handle, false)
    }

    fun playPrevious() {
        shuffleMode = false
        ProjectMBridge.nativeSetShuffle(handle, shuffleMode)
        ProjectMBridge.nativePlayPrevious(handle, false)
    }

    fun playRandom() {
        shuffleMode = true
        ProjectMBridge.nativeSetShuffle(handle, shuffleMode)
        ProjectMBridge.nativePlayNext(handle, false)
    }

    fun autoAdvance() {
        ProjectMBridge.nativeSetShuffle(handle, shuffleMode)
        ProjectMBridge.nativePlayNext(handle, false)
    }

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
    }
}
