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
class MilkDropRenderer(
    private val texturesDir: File,
    private val performanceHints: RenderPerformanceHints,
) : GLSurfaceView.Renderer {

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

    /**
     * Preset to resume on when a playlist is first built, e.g. the one showing at last exit. Kept
     * current as presets change, so a rebuild after EGL context loss resumes where it was.
     */
    @Volatile
    var startPresetPath: String? = null

    /** Called on the GL thread whenever a different preset starts showing, however it was picked. */
    @Volatile
    var onPresetChanged: ((path: String) -> Unit)? = null

    /** Called on the GL thread once a playlist is loaded and navigation works. */
    @Volatile
    var onPresetsReady: (() -> Unit)? = null

    /** Latest list handed over by [loadScannedPresets]; applied on the GL thread when it changes. */
    @Volatile
    private var presetPaths: List<String> = emptyList()

    /**
     * The list projectM's playlist currently holds, in the same order, or null before one is
     * loaded. Kept so the playlist can be rebuilt if the EGL context (and projectM) is recreated.
     */
    @Volatile
    private var loadedPaths: List<String>? = null

    private var lastReportedPosition = -1

    /** Guards [handle] against being destroyed while the audio thread is mid-[feedPcm]. */
    private val pcmLock = Any()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A second call means the EGL context was lost and recreated: every GL object the old
        // instance owned is gone, so rebuild projectM from scratch rather than rendering garbage.
        release()

        handle = ProjectMBridge.nativeCreate()
        GpuDriverThreads.pinToFastCores()
        performanceHints.onRenderThreadStarted()
        ProjectMBridge.nativeSetTextureSearchPaths(handle, arrayOf(texturesDir.absolutePath))
        ProjectMBridge.nativeSetPresetDuration(handle, PRESET_DURATION_SECONDS)
        appliedShuffle = null
        appliedAutoAdvance = null
        appliedInstantTransitions = null
        appliedMeshSize = null
        // Until a preset list is handed over (see loadScannedPresets), projectM just shows its
        // built-in idle preset.
        loadedPaths = null
        lastReportedPosition = -1
        applyPendingState()
    }

    /**
     * Safe to call from any thread, and again later with a newer list (e.g. a cached list first,
     * then a fresh scan). A different list replaces the playlist, keeping the current preset.
     */
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
        performanceHints.onFrameStart(System.nanoTime())
        applyPendingState()
        ProjectMBridge.nativeRenderFrame(handle)
        reportPresetChange()
        performanceHints.onFrameEnd(System.nanoTime())
    }

    /** Covers auto-advance too, which projectM does internally without going through [navigate]. */
    private fun reportPresetChange() {
        val paths = loadedPaths ?: return
        val position = ProjectMBridge.nativeGetPlaylistPosition(handle)
        if (position != lastReportedPosition) {
            lastReportedPosition = position
            paths.getOrNull(position)?.let { path ->
                startPresetPath = path
                onPresetChanged?.invoke(path)
            }
        }
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
        // Identity check: it runs every frame, and callers hand over a new list only when it changed.
        val paths = presetPaths
        if (paths.isNotEmpty() && paths !== loadedPaths) {
            loadPlaylist(currentHandle, paths)
        }

        if (loadedPaths != null) {
            pendingNavigation.getAndSet(null)?.let { navigate(currentHandle, it) }
        }
    }

    private fun loadPlaylist(currentHandle: Long, paths: List<String>) {
        if (loadedPaths != null) {
            ProjectMBridge.nativeClearPresets(currentHandle)
        }
        // The scan already yields unique paths. With duplicate checking on, projectM does a linear
        // search per insert: O(n²) string compares on the GL thread, seconds for ~15k presets.
        ProjectMBridge.nativeAddPresets(currentHandle, paths.toTypedArray(), true)
        loadedPaths = paths
        lastReportedPosition = -1

        // Resuming reloads the preset even when it's the one already showing (after a rescan found
        // changes); the playlist API has no way to re-point its position without loading.
        val resumeIndex = startPresetPath?.let { paths.indexOf(it) } ?: -1
        navigate(currentHandle, if (resumeIndex >= 0) Navigation.JumpTo(resumeIndex) else Navigation.Next)
        onPresetsReady?.invoke()
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
    fun playlistItems(): List<String> = loadedPaths.orEmpty()

    fun feedPcm(samples: ShortArray, frameCount: Int, channels: Int) {
        synchronized(pcmLock) {
            val currentHandle = handle
            if (currentHandle != 0L) {
                ProjectMBridge.nativeFeedPcmInt16(currentHandle, samples, frameCount, channels)
            }
        }
    }

    /** Must run on the GL thread. */
    fun release() {
        performanceHints.release()
        val currentHandle = handle
        if (currentHandle != 0L) {
            // Clear the handle under the lock so no feedPcm call can still be using it once it's freed.
            synchronized(pcmLock) { handle = 0L }
            ProjectMBridge.nativeDestroy(currentHandle)
        }
    }

    private companion object {
        const val TAG = "MilkDropRenderer"
        const val PRESET_DURATION_SECONDS = 15.0
        const val SOFT_CUT_DURATION_SECONDS = 1.0
    }
}
