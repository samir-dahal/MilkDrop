package com.milkdrop.visualizer.render

import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Owns the projectM instance, which lives entirely on the GLSurfaceView's GL thread.
 *
 * Other threads never call into projectM directly. They set the desired state instead
 * ([shuffleEnabled], [autoAdvanceEnabled], [instantTransitions], [meshSize], [requestNavigation]) and
 * [onDrawFrame] applies it at the start of the next frame. That sidesteps two problems `queueEvent` had here:
 *  - `queueEvent` doesn't guarantee running after [onSurfaceCreated], so an early event could hit a
 *    null handle (a real native crash) or be silently dropped.
 *  - Each preset load blocks the GL thread (parse + shader compile), so taps queued during one
 *    load each triggered another full load afterwards. Now only the latest request is kept.
 */
class MilkDropRenderer(private val texturesDir: File) : GLSurfaceView.Renderer {

    sealed interface Navigation {
        data object Next : Navigation
        data object Previous : Navigation
        data class JumpTo(val position: Int) : Navigation
    }

    data class MeshSize(val columns: Int, val rows: Int) {
        companion object {
            /** projectM's own default. */
            val DEFAULT = MeshSize(32, 24)
        }
    }

    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    var shuffleEnabled: Boolean = true

    /** When false, projectM's own preset-duration timer is locked so it stops switching presets. */
    @Volatile
    var autoAdvanceEnabled: Boolean = true

    /**
     * Hard cuts instead of blended transitions, for both manual navigation and projectM's own
     * auto-advance (which always requests a soft cut, so its blend duration is zeroed instead).
     */
    @Volatile
    var instantTransitions: Boolean = false

    /**
     * Per-vertex warp mesh resolution (columns x rows). Presets evaluate their per-vertex equations
     * on the CPU at every mesh point each frame, so a coarser mesh directly cuts CPU cost.
     */
    @Volatile
    var meshSize: MeshSize = MeshSize.DEFAULT

    private var appliedShuffle: Boolean? = null
    private var appliedAutoAdvance: Boolean? = null
    private var appliedInstantTransitions: Boolean? = null
    private var appliedMeshSize: MeshSize? = null

    private val pendingNavigation = AtomicReference<Navigation?>(null)

    /** Kept so the playlist can be rebuilt if the EGL context (and with it projectM) is recreated. */
    @Volatile
    private var presetPaths: List<String> = emptyList()

    private var presetsLoaded = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A second call means the EGL context was lost and recreated: every GL object the old
        // instance owned is gone, so rebuild projectM from scratch rather than rendering garbage.
        release()

        handle = ProjectMBridge.nativeCreate()
        ProjectMBridge.nativeSetTextureSearchPaths(handle, arrayOf(texturesDir.absolutePath))
        ProjectMBridge.nativeSetPresetDuration(handle, PRESET_DURATION_SECONDS)
        appliedShuffle = null
        appliedAutoAdvance = null
        appliedInstantTransitions = null
        appliedMeshSize = null
        // Until the background filesystem scan hands off results (see loadScannedPresets),
        // projectM just shows its built-in idle preset.
        presetsLoaded = false
        applyPendingState()
    }

    /** Safe to call from any thread, once the caller has finished scanning the presets directory. */
    fun loadScannedPresets(paths: List<String>) {
        presetPaths = paths
    }

    fun requestNavigation(navigation: Navigation) {
        pendingNavigation.set(navigation)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        ProjectMBridge.nativeSetWindowSize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        applyPendingState()
        ProjectMBridge.nativeRenderFrame(handle)
    }

    private fun applyPendingState() {
        val currentHandle = handle
        if (currentHandle == 0L) return

        val shuffle = shuffleEnabled
        if (shuffle != appliedShuffle) {
            ProjectMBridge.nativeSetShuffle(currentHandle, shuffle)
            appliedShuffle = shuffle
        }

        val autoAdvance = autoAdvanceEnabled
        if (autoAdvance != appliedAutoAdvance) {
            ProjectMBridge.nativeSetPresetLocked(currentHandle, !autoAdvance)
            appliedAutoAdvance = autoAdvance
        }

        val instant = instantTransitions
        if (instant != appliedInstantTransitions) {
            ProjectMBridge.nativeSetSoftCutDuration(currentHandle, if (instant) 0.0 else SOFT_CUT_DURATION_SECONDS)
            appliedInstantTransitions = instant
        }

        val mesh = meshSize
        if (mesh != appliedMeshSize) {
            ProjectMBridge.nativeSetMeshSize(currentHandle, mesh.columns, mesh.rows)
            appliedMeshSize = mesh
        }

        // After shuffle is applied, so the very first preset already honours it.
        if (!presetsLoaded && presetPaths.isNotEmpty()) {
            addPresetsAndStart(presetPaths)
        }

        if (presetsLoaded) {
            pendingNavigation.getAndSet(null)?.let { navigate(currentHandle, it) }
        }
    }

    private fun addPresetsAndStart(paths: List<String>) {
        // The scan already yields unique paths. With duplicate checking on, projectM does a linear
        // search per insert: O(n²) string compares on the GL thread, seconds for ~15k presets.
        ProjectMBridge.nativeAddPresets(handle, paths.toTypedArray(), true)
        presetsLoaded = true
        navigate(handle, Navigation.Next)
    }

    private fun navigate(currentHandle: Long, navigation: Navigation) {
        val startMs = SystemClock.elapsedRealtime()
        val hardCut = appliedInstantTransitions == true
        val position = when (navigation) {
            Navigation.Next -> ProjectMBridge.nativePlayNext(currentHandle, hardCut)
            Navigation.Previous -> ProjectMBridge.nativePlayLast(currentHandle, hardCut)
            is Navigation.JumpTo -> ProjectMBridge.nativeSetPlaylistPosition(currentHandle, navigation.position, hardCut)
        }
        Log.d(TAG, "$navigation -> #$position loaded in ${SystemClock.elapsedRealtime() - startMs} ms")
    }

    fun playlistPosition(): Int {
        val currentHandle = handle
        return if (currentHandle != 0L) ProjectMBridge.nativeGetPlaylistPosition(currentHandle) else 0
    }

    /**
     * Same order as projectM's playlist (added verbatim, no filter or de-duplication), so any thread
     * can read it without copying ~15k strings back across JNI on the GL thread.
     */
    fun playlistItems(): List<String> = presetPaths

    fun feedPcm(samples: ShortArray, frameCount: Int, channels: Int) {
        val currentHandle = handle
        if (currentHandle != 0L) {
            ProjectMBridge.nativeFeedPcmInt16(currentHandle, samples, frameCount, channels)
        }
    }

    /** Must run on the GL thread. */
    fun release() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            handle = 0L
            ProjectMBridge.nativeDestroy(currentHandle)
        }
    }

    private companion object {
        const val TAG = "MilkDropRenderer"
        const val PRESET_DURATION_SECONDS = 15.0
        const val SOFT_CUT_DURATION_SECONDS = 1.0
    }
}
