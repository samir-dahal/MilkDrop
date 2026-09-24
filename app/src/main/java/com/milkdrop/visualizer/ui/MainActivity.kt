package com.milkdrop.visualizer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.audio.AudioCaptureManager
import com.milkdrop.visualizer.databinding.ActivityMainBinding
import com.milkdrop.visualizer.presets.PresetPaths
import com.milkdrop.visualizer.render.MilkDropRenderer.MeshSize
import com.milkdrop.visualizer.render.MilkDropRenderer.Navigation
import com.milkdrop.visualizer.render.MilkDropSurfaceView
import com.milkdrop.visualizer.settings.AppSettings

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var surfaceView: MilkDropSurfaceView
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var presetAdapter: PresetListAdapter
    private lateinit var settings: AppSettings

    private var autoAdvanceEnabled = true
    private var shuffleEnabled = true
    private var hardCutEnabled = false
    private var allPresetEntries: List<PresetEntry> = emptyList()
    private var currentPlaylistPosition = 0
    private var fpsIndex = 0
    private var qualityIndex = 0

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { binding.overlayControls.visibility = View.GONE }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleOverlay()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                hideAllUi()
                return true
            }
        })
    }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                audioCaptureManager.requestInternalCapture(result.resultCode, data)
            } else {
                switchToMic()
            }
        }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startPersistedAudioSource()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveFullscreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        autoAdvanceEnabled = settings.autoAdvanceEnabled
        shuffleEnabled = settings.shuffleEnabled
        hardCutEnabled = settings.hardCutEnabled
        fpsIndex = settings.fpsIndex
        qualityIndex = settings.qualityIndex
        restoreButtonLabels()

        PresetPaths.ensureDirsExist()

        surfaceView = MilkDropSurfaceView(this, PresetPaths.texturesDir)
        binding.surfaceContainer.addView(surfaceView)
        applyRestoredRenderSettings()

        audioCaptureManager = AudioCaptureManager(this) { samples, frameCount, channels ->
            surfaceView.milkDropRenderer.feedPcm(samples, frameCount, channels)
        }

        setupGestures()
        setupOverlay()
        setupPlaylistPanel()
        requestPermissionsThenStartAudio()
        scanPresetsInBackground()
    }

    /** Button labels default to their "on"/index-0 state in the layout — override to match what was restored. */
    private fun restoreButtonLabels() {
        binding.btnShuffle.setText(if (shuffleEnabled) R.string.btn_shuffle_on else R.string.btn_shuffle_off)
        binding.btnPlayPause.setText(if (autoAdvanceEnabled) R.string.btn_auto_on else R.string.btn_auto_off)
        binding.btnTransition.setText(if (hardCutEnabled) R.string.transition_instant else R.string.transition_smooth)
        binding.btnFps.setText(FPS_LABELS[fpsIndex])
        binding.btnQuality.setText(QUALITY_LABELS[qualityIndex])
    }

    /**
     * Restored labels are cosmetic only — actually apply the settings. The renderer picks up
     * this state itself once its GL surface exists (see MilkDropRenderer's class doc).
     */
    private fun applyRestoredRenderSettings() {
        surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
        surfaceView.milkDropRenderer.autoAdvanceEnabled = autoAdvanceEnabled
        surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
        surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
        applyQuality()
    }

    private fun applyQuality() {
        surfaceView.setResolutionScale(QUALITY_SCALES[qualityIndex])
        surfaceView.milkDropRenderer.meshSize = QUALITY_MESH_SIZES[qualityIndex]
    }

    /**
     * Scanning the presets directory (thousands of files across packs) is too slow to do on the
     * GL thread without stalling the first rendered frame — walk it here instead, then hand the
     * results to the renderer once ready. projectM shows its idle preset until then.
     */
    private fun scanPresetsInBackground() {
        Thread({
            val paths = PresetPaths.scanPresets()
            surfaceView.milkDropRenderer.loadScannedPresets(paths)
        }, "milkdrop-preset-scan").start()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioCaptureManager.stopAll()
        surfaceView.queueEvent { surfaceView.milkDropRenderer.release() }
    }

    private fun setImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupGestures() {
        surfaceView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    private fun setupOverlay() {
        binding.btnNext.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Next)
            resetOverlayTimer()
        }
        binding.btnPrevious.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Previous)
            resetOverlayTimer()
        }
        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            settings.shuffleEnabled = shuffleEnabled
            binding.btnShuffle.setText(if (shuffleEnabled) R.string.btn_shuffle_on else R.string.btn_shuffle_off)
            surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
            resetOverlayTimer()
        }
        binding.btnPlaylist.setOnClickListener { openPlaylistPanel() }
        binding.btnPlayPause.setOnClickListener {
            autoAdvanceEnabled = !autoAdvanceEnabled
            settings.autoAdvanceEnabled = autoAdvanceEnabled
            binding.btnPlayPause.setText(if (autoAdvanceEnabled) R.string.btn_auto_on else R.string.btn_auto_off)
            surfaceView.milkDropRenderer.autoAdvanceEnabled = autoAdvanceEnabled
            resetOverlayTimer()
        }
        binding.btnMediaPlayPause.setOnClickListener {
            dispatchMediaPlayPause()
            resetOverlayTimer()
        }
        binding.btnAudioSource.setOnClickListener {
            audioCaptureManager.stopAll()
            if (audioCaptureManager.currentSource == AudioCaptureManager.SourceType.INTERNAL) {
                switchToMic()
            } else {
                settings.internalAudioSource = true
                binding.btnAudioSource.setText(R.string.audio_source_internal)
                requestInternalCapture()
            }
            resetOverlayTimer()
        }
        binding.btnFps.setOnClickListener {
            fpsIndex = (fpsIndex + 1) % FPS_OPTIONS.size
            settings.fpsIndex = fpsIndex
            binding.btnFps.setText(FPS_LABELS[fpsIndex])
            surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
            resetOverlayTimer()
        }
        binding.btnQuality.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % QUALITY_SCALES.size
            settings.qualityIndex = qualityIndex
            binding.btnQuality.setText(QUALITY_LABELS[qualityIndex])
            applyQuality()
            resetOverlayTimer()
        }
        binding.btnTransition.setOnClickListener {
            hardCutEnabled = !hardCutEnabled
            settings.hardCutEnabled = hardCutEnabled
            binding.btnTransition.setText(if (hardCutEnabled) R.string.transition_instant else R.string.transition_smooth)
            surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
            resetOverlayTimer()
        }
    }

    private fun setupPlaylistPanel() {
        binding.playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        presetAdapter = PresetListAdapter { entry ->
            surfaceView.milkDropRenderer.requestNavigation(Navigation.JumpTo(entry.index))
            closePlaylistPanel()
        }
        binding.playlistRecyclerView.adapter = presetAdapter

        binding.btnClosePlaylist.setOnClickListener { closePlaylistPanel() }
        binding.playlistSearch.doAfterTextChanged { filterPresetList(it?.toString().orEmpty()) }
    }

    private fun openPlaylistPanel() {
        binding.overlayControls.visibility = View.GONE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        binding.playlistSearch.setText("")
        binding.playlistPanel.visibility = View.VISIBLE

        surfaceView.queueEvent {
            val position = surfaceView.milkDropRenderer.playlistPosition()
            runOnUiThread {
                val entries = surfaceView.milkDropRenderer.playlistItems()
                    .mapIndexed { index, path -> PresetEntry(index, path) }
                allPresetEntries = entries
                currentPlaylistPosition = position
                presetAdapter.submit(entries, position)
                if (entries.isNotEmpty()) {
                    binding.playlistRecyclerView.scrollToPosition(position.coerceIn(0, entries.size - 1))
                }
            }
        }
    }

    private fun closePlaylistPanel() {
        binding.playlistPanel.visibility = View.GONE
    }

    private fun filterPresetList(query: String) {
        val filtered = if (query.isBlank()) {
            allPresetEntries
        } else {
            allPresetEntries.filter { it.path.contains(query, ignoreCase = true) }
        }
        presetAdapter.submit(filtered, currentPlaylistPosition)
    }

    private fun toggleOverlay() {
        if (binding.overlayControls.visibility == View.VISIBLE) {
            binding.overlayControls.visibility = View.GONE
            overlayHandler.removeCallbacks(hideOverlayRunnable)
        } else {
            binding.overlayControls.visibility = View.VISIBLE
            resetOverlayTimer()
        }
    }

    private fun hideAllUi() {
        binding.overlayControls.visibility = View.GONE
        binding.playlistPanel.visibility = View.GONE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
    }

    private fun resetOverlayTimer() {
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun dispatchMediaPlayPause() {
        val audioManager = getSystemService(AudioManager::class.java)
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
    }

    private fun requestPermissionsThenStartAudio() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            startPersistedAudioSource()
        }
    }

    private fun startPersistedAudioSource() {
        if (settings.internalAudioSource) {
            binding.btnAudioSource.setText(R.string.audio_source_internal)
            requestInternalCapture()
        } else {
            switchToMic()
        }
    }

    private fun requestInternalCapture() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun switchToMic() {
        audioCaptureManager.startMic()
        binding.btnAudioSource.setText(R.string.audio_source_mic)
        settings.internalAudioSource = false
    }

    private companion object {
        const val OVERLAY_HIDE_DELAY_MS = 3000L

        // 0 = uncapped. Index 0 (30fps) is the default performance-friendly setting.
        val FPS_OPTIONS = intArrayOf(30, 45, 60, 0)
        val FPS_LABELS = intArrayOf(R.string.fps_30, R.string.fps_45, R.string.fps_60, R.string.fps_uncapped)

        // Fraction of native resolution to render at; lower cuts fragment shader cost for heavy presets.
        val QUALITY_SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)
        // Coarser warp mesh at Low: fewer per-vertex equation evaluations on the CPU each frame.
        val QUALITY_MESH_SIZES = arrayOf(MeshSize.DEFAULT, MeshSize.DEFAULT, MeshSize(24, 18))
        val QUALITY_LABELS = intArrayOf(R.string.quality_high, R.string.quality_medium, R.string.quality_low)
    }
}
