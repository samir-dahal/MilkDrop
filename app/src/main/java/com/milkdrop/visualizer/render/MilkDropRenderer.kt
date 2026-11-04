package com.milkdrop.visualizer.render

import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.milkdrop.visualizer.cast.VisualOutput
import com.milkdrop.visualizer.settings.AudioTuning
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Owns the projectM instance, which lives entirely on the GLSurfaceView's GL thread.
 *
 * Other threads never call into projectM directly. They set the desired state instead
 * ([shuffleEnabled], [autoAdvanceEnabled], [instantTransitions], [meshSize], [presetDurationSeconds],
 * [beatCutsEnabled], [requestNavigation]) and
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
        data class JumpTo(val path: String) : Navigation
    }

    data class MeshSize(val columns: Int, val rows: Int) {
        companion object {
            /** projectM's own default. */
            val DEFAULT = MeshSize(32, 24)
        }
    }

    private val pendingCountdownRestart = AtomicBoolean(false)

    @Volatile var visualOutput: VisualOutput? = null
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    var shuffleEnabled: Boolean = true

    /** Disabled holds the switching countdown at zero; changing it restarts the countdown. */
    @Volatile
    var autoAdvanceEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                pendingCountdownRestart.set(true)
            }
        }

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

    /** How long auto-advance shows each preset. */
    @Volatile
    var presetDurationSeconds: Double = 15.0
        set(value) {
            if (field != value) {
                field = value
                pendingCountdownRestart.set(true)
            }
        }

    @Volatile
    var additionalRandomSeconds: Double = 0.0
        set(value) {
            if (field != value) {
                field = value
                pendingCountdownRestart.set(true)
            }
        }

    @Volatile
    var transitionSeconds: Double = 1.7

    /** Also switch preset (hard cut) on a sudden jump in loudness, once a preset has shown a while. */
    @Volatile
    var beatCutsEnabled: Boolean = false

    /** Full-screen coordinates for geometry and equations; shader aspect stays physical. */
    @Volatile
    var screenDependentRendering: Boolean = false

    @Volatile
    var audioSensitivity: Float = AudioTuning.SENSITIVITY_DEFAULT

    @Volatile
    var fftShaderScaling: Float = AudioTuning.FFT_SCALING_DEFAULT

    @Volatile
    var fftShaderNoiseFloor: Float = AudioTuning.FFT_NOISE_FLOOR_DEFAULT

    private data class PointerState(val x: Float = -1.0f, val y: Float = -1.0f,
        val down: Boolean = false, val pressed: Boolean = false)
    private val pointer = AtomicReference(PointerState())

    /** Positions cover the whole visualization, including touches delivered to overlay controls. */
    fun updatePointer(x: Float, y: Float, down: Boolean, pressed: Boolean = false) {
        pointer.updateAndGet { previous -> PointerState(x, y, down, previous.pressed || pressed || (down && !previous.down)) }
    }

    fun clearPointer() {
        pointer.set(PointerState())
    }

    private fun applyPointer() {
        val state = pointer.getAndUpdate { if (it.pressed) it.copy(pressed = false) else it }
        ProjectMBridge.nativeSetPointer(handle, state.x, state.y, state.down, state.pressed)
    }

    private var appliedShuffle: Boolean? = null
    private var appliedAutoAdvance: Boolean? = null
    private var appliedInstantTransitions: Boolean? = null
    private var appliedMeshSize: MeshSize? = null
    private var appliedPresetDuration: Double? = null
    private var appliedRandomDuration: Double? = null
    private var appliedTransitionSeconds: Double? = null
    private var appliedBeatCuts: Boolean? = null
    private var appliedScreenDependentRendering: Boolean? = null
    private var appliedFftScaling: Float? = null
    private var appliedFftNoiseFloor: Float? = null

    private val pendingNavigation = AtomicReference<Navigation?>(null)
    private val pendingTextureRefresh = AtomicBoolean(false)

    /** Apply on the GL thread before loading presets after storage setup or permission changes. */
    fun refreshTextureSearchPaths() {
        pendingTextureRefresh.set(true)
    }

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

    /** Called when native libraries or the device's graphics context cannot initialize projectM. */
    @Volatile
    var onInitializationFailed: (() -> Unit)? = null

    /** Latest list handed over by [loadScannedPresets]; applied on the GL thread when it changes. */
    @Volatile
    private var presetPaths: List<String> = emptyList()

    /**
     * The list projectM's playlist currently holds, in the same order, or null before one is
     * loaded. Kept so the playlist can be rebuilt if the EGL context (and projectM) is recreated.
     */
    @Volatile
    private var loadedPaths: List<String>? = null

    private var lastReportedPath: String? = null

    private val frameRateMeter = FrameRateMeter()
    private var reportedFps = 0
    @Volatile private var frameRateReading: Pair<Double, Long>? = null

    /** Receives one measured-FPS sample at a time; listeners must move UI work to the main thread. */
    @Volatile
    var onFrameRateMeasured: ((fps: Int) -> Unit)? = null

    /** Measured render FPS, rather than the configured cap; stale readings show a stopped renderer. */
    fun currentFramesPerSecond(): Double? = frameRateReading?.let { (fps, sampledAt) ->
        if (System.nanoTime() - sampledAt > 1_500_000_000L) 0.0 else fps
    }

    /** Guards [handle] against being destroyed while the audio thread is mid-[feedPcm]. */
    private val pcmLock = Any()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A second call means the EGL context was lost and recreated: every GL object the old
        // instance owned is gone, so rebuild projectM from scratch rather than rendering garbage.
        release()

        handle = try {
            ProjectMBridge.nativeCreate()
        } catch (e: LinkageError) {
            Log.e(TAG, "Unable to load projectM native libraries", e)
            0L
        }
        if (handle == 0L) {
            Log.e(TAG, "Unable to initialize projectM")
            onInitializationFailed?.invoke()
            return
        }
        GpuDriverThreads.pinToFastCores()
        performanceHints.onRenderThreadStarted()
        ProjectMBridge.nativeSetTextureSearchPaths(handle, arrayOf(texturesDir.absolutePath))
        // projectM's own minimum (20 s) is longer than typical preset durations, so beat cuts
        // would never happen before the timed switch.
        ProjectMBridge.nativeSetHardCutDuration(handle, BEAT_CUT_MIN_SECONDS)
        appliedShuffle = null
        appliedAutoAdvance = null
        appliedInstantTransitions = null
        appliedMeshSize = null
        appliedPresetDuration = null
        appliedRandomDuration = null
        appliedTransitionSeconds = null
        appliedBeatCuts = null
        appliedScreenDependentRendering = null
        appliedFftScaling = null
        appliedFftNoiseFloor = null
        // Until a preset list is handed over (see loadScannedPresets), projectM just shows its
        // built-in idle preset.
        loadedPaths = null
        lastReportedPath = null
        frameRateMeter.reset()
        reportedFps = 0
        applyPointer()
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
        viewportWidth = width; viewportHeight = height
        if (handle != 0L) ProjectMBridge.nativeSetWindowSize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (handle == 0L) return
        val frameStartNanos = System.nanoTime()
        performanceHints.onFrameStart(frameStartNanos)
        applyPointer()
        applyPendingState()
        reportFrameRate(frameStartNanos)
        ProjectMBridge.nativeRenderFrame(handle)
        visualOutput?.draw(viewportWidth, viewportHeight)
        reportPresetChange()
        performanceHints.onFrameEnd(System.nanoTime())
    }

    /**
     * projectM doesn't measure the frame rate itself: without this, presets always saw its default
     * (35), and those that scale motion by `fps` ran too fast at higher frame rates.
     */
    private fun reportFrameRate(nowNanos: Long) {
        val measuredFps = frameRateMeter.onFrame(nowNanos) ?: return
        frameRateReading = measuredFps to nowNanos
        // Preserve the existing integer engine/adaptation inputs; the UI keeps full precision.
        val fps = measuredFps.roundToInt()
        onFrameRateMeasured?.invoke(fps)
        if (fps != reportedFps && handle != 0L) {
            ProjectMBridge.nativeSetFps(handle, fps)
            reportedFps = fps
        }
    }

    /** Covers auto-advance too, which projectM does internally without going through [navigate]. */
    private fun reportPresetChange() {
        if (loadedPaths == null) return
        val path = ProjectMBridge.nativeGetCurrentPresetPath(handle) ?: return
        if (path != lastReportedPath) {
            lastReportedPath = path
            startPresetPath = path
            onPresetChanged?.invoke(path)
        }
    }

    private fun applyPendingState() {
        val currentHandle = handle
        if (currentHandle == 0L) return

        // Keep a restart even if controls were changed and changed back while a heavy
        // preset blocked rendering. The final desired values still apply only once.
        if (pendingCountdownRestart.getAndSet(false)) appliedPresetDuration = null

        if (pendingTextureRefresh.getAndSet(false)) {
            ProjectMBridge.nativeSetTextureSearchPaths(currentHandle, arrayOf(texturesDir.absolutePath))
        }

        val scaling = fftShaderScaling
        val noiseFloor = fftShaderNoiseFloor
        if (scaling != appliedFftScaling || noiseFloor != appliedFftNoiseFloor) {
            ProjectMBridge.nativeSetShaderAudioSettings(currentHandle, scaling, noiseFloor)
            appliedFftScaling = scaling
            appliedFftNoiseFloor = noiseFloor
        }

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
        val transition = transitionSeconds
        if (instant != appliedInstantTransitions || transition != appliedTransitionSeconds) {
            ProjectMBridge.nativeSetSoftCutDuration(currentHandle, if (instant) 0.0 else transition)
            appliedInstantTransitions = instant
            appliedTransitionSeconds = transition
        }

        val screenDependent = screenDependentRendering
        if (screenDependent != appliedScreenDependentRendering) {
            ProjectMBridge.nativeSetScreenDependentRendering(currentHandle, screenDependent)
            appliedScreenDependentRendering = screenDependent
        }

        val mesh = meshSize
        if (mesh != appliedMeshSize) {
            ProjectMBridge.nativeSetMeshSize(currentHandle, mesh.columns, mesh.rows)
            appliedMeshSize = mesh
        }

        val duration = presetDurationSeconds
        val randomDuration = additionalRandomSeconds
        if (randomDuration != appliedRandomDuration) {
            ProjectMBridge.nativeSetAdditionalRandomTime(currentHandle, randomDuration)
            appliedRandomDuration = randomDuration
        }
        if (duration != appliedPresetDuration) {
            ProjectMBridge.nativeSetPresetDuration(currentHandle, duration)
            appliedPresetDuration = duration
        }

        val beatCuts = beatCutsEnabled
        if (beatCuts != appliedBeatCuts) {
            ProjectMBridge.nativeSetHardCutEnabled(currentHandle, beatCuts)
            appliedBeatCuts = beatCuts
        }

        // After shuffle is applied, so the very first preset already honours it.
        // Identity check: it runs every frame, and callers hand over a new list only when it changed.
        val paths = presetPaths
        if (paths.isNotEmpty() && paths !== loadedPaths) {
            loadPlaylist(currentHandle, paths)
        } else if (paths.isEmpty() && loadedPaths?.isNotEmpty() == true) {
            ProjectMBridge.nativeClearPresets(currentHandle)
            loadedPaths = paths
            lastReportedPath = null
        }

        if (loadedPaths?.isNotEmpty() == true) {
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
        lastReportedPath = null

        // Resuming reloads the preset even when it's the one already showing (after a rescan found
        // changes); the playlist API has no way to re-point its position without loading.
        val resumePath = startPresetPath?.takeIf { it in paths }
        navigate(currentHandle, if (resumePath != null) Navigation.JumpTo(resumePath) else Navigation.Next)
        onPresetsReady?.invoke()
    }

    private fun navigate(currentHandle: Long, navigation: Navigation) {
        val startMs = SystemClock.elapsedRealtime()
        val hardCut = appliedInstantTransitions == true
        val position = when (navigation) {
            Navigation.Next -> ProjectMBridge.nativePlayNext(currentHandle, hardCut)
            Navigation.Previous -> ProjectMBridge.nativePlayLast(currentHandle, hardCut)
            is Navigation.JumpTo -> {
                if (navigation.path !in loadedPaths.orEmpty()) return
                ProjectMBridge.nativePlayPresetPath(currentHandle, navigation.path, hardCut)
            }
        }
        Log.d(TAG, "$navigation -> #$position loaded in ${SystemClock.elapsedRealtime() - startMs} ms")
    }

    fun feedPcm(samples: ShortArray, frameCount: Int, channels: Int, sampleRateHz: Int, snapshot: Boolean) {
        synchronized(pcmLock) {
            val currentHandle = handle
            if (currentHandle != 0L) {
                ProjectMBridge.nativeFeedPcmInt16(currentHandle, samples, frameCount, channels,
                    sampleRateHz, snapshot, audioSensitivity)
            }
        }
    }

    /** Must run on the GL thread. */
    fun release() {
        frameRateReading = null
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
        const val BEAT_CUT_MIN_SECONDS = 5.0
    }
}
