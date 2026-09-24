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
import com.milkdrop.visualizer.render.MilkDropSurfaceView
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var surfaceView: MilkDropSurfaceView
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var presetAdapter: PresetListAdapter

    private var autoAdvanceEnabled = true
    private var shuffleEnabled = true
    private var hardCutEnabled = false
    private var allPresetEntries: List<PresetEntry> = emptyList()
    private var currentPlaylistPosition = 0
    private var fpsIndex = 0
    private var qualityIndex = 0

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { binding.overlayControls.visibility = View.GONE }

    private val autoAdvanceHandler = Handler(Looper.getMainLooper())
    private val autoAdvanceRunnable = object : Runnable {
        override fun run() {
            if (autoAdvanceEnabled) {
                val hardCut = hardCutEnabled
                surfaceView.queueEvent { surfaceView.milkDropRenderer.playNext(hardCut) }
            }
            autoAdvanceHandler.postDelayed(this, AUTO_ADVANCE_INTERVAL_MS)
        }
    }

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

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val startX = e1?.x ?: return false
                val deltaX = e2.x - startX
                val deltaY = e2.y - e1.y
                if (abs(deltaX) > SWIPE_DISTANCE_THRESHOLD_PX &&
                    abs(deltaX) > abs(deltaY) &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    val hardCut = hardCutEnabled
                    if (deltaX < 0) {
                        surfaceView.queueEvent { surfaceView.milkDropRenderer.playNext(hardCut) }
                    } else {
                        surfaceView.queueEvent { surfaceView.milkDropRenderer.playPrevious(hardCut) }
                    }
                    return true
                }
                return false
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
            switchToMic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveFullscreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PresetPaths.ensureDirsExist()

        surfaceView = MilkDropSurfaceView(this, PresetPaths.texturesDir)
        binding.surfaceContainer.addView(surfaceView)

        audioCaptureManager = AudioCaptureManager(this) { samples, frameCount, channels ->
            surfaceView.milkDropRenderer.feedPcm(samples, frameCount, channels)
        }

        setupGestures()
        setupOverlay()
        setupPlaylistPanel()
        requestPermissionsThenStartAudio()
        scanPresetsInBackground()
    }

    /**
     * Scanning the presets directory (thousands of files across packs) is too slow to do on the
     * GL thread without stalling the first rendered frame — walk it here instead, then hand the
     * results to the renderer once ready. The idle preset loaded in onSurfaceCreated covers the gap.
     */
    private fun scanPresetsInBackground() {
        Thread({
            val paths = PresetPaths.scanPresets()
            surfaceView.queueEvent { surfaceView.milkDropRenderer.loadScannedPresets(paths) }
        }, "milkdrop-preset-scan").start()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        autoAdvanceHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable)
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
            val hardCut = hardCutEnabled
            surfaceView.queueEvent { surfaceView.milkDropRenderer.playNext(hardCut) }
            resetOverlayTimer()
        }
        binding.btnPrevious.setOnClickListener {
            val hardCut = hardCutEnabled
            surfaceView.queueEvent { surfaceView.milkDropRenderer.playPrevious(hardCut) }
            resetOverlayTimer()
        }
        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            binding.btnShuffle.setText(if (shuffleEnabled) R.string.btn_shuffle_on else R.string.btn_shuffle_off)
            surfaceView.queueEvent { surfaceView.milkDropRenderer.setShuffle(shuffleEnabled) }
            resetOverlayTimer()
        }
        binding.btnPlaylist.setOnClickListener { openPlaylistPanel() }
        binding.btnPlayPause.setOnClickListener {
            autoAdvanceEnabled = !autoAdvanceEnabled
            binding.btnPlayPause.setText(if (autoAdvanceEnabled) R.string.btn_auto_on else R.string.btn_auto_off)
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
                binding.btnAudioSource.setText(R.string.audio_source_internal)
                requestInternalCapture()
            }
            resetOverlayTimer()
        }
        binding.btnFps.setOnClickListener {
            fpsIndex = (fpsIndex + 1) % FPS_OPTIONS.size
            binding.btnFps.setText(FPS_LABELS[fpsIndex])
            surfaceView.milkDropRenderer.targetFps = FPS_OPTIONS[fpsIndex]
            resetOverlayTimer()
        }
        binding.btnQuality.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % QUALITY_SCALES.size
            binding.btnQuality.setText(QUALITY_LABELS[qualityIndex])
            surfaceView.setResolutionScale(QUALITY_SCALES[qualityIndex])
            resetOverlayTimer()
        }
        binding.btnTransition.setOnClickListener {
            hardCutEnabled = !hardCutEnabled
            binding.btnTransition.setText(if (hardCutEnabled) R.string.transition_instant else R.string.transition_smooth)
            resetOverlayTimer()
        }
    }

    private fun setupPlaylistPanel() {
        binding.playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        presetAdapter = PresetListAdapter { entry ->
            val hardCut = hardCutEnabled
            surfaceView.queueEvent { surfaceView.milkDropRenderer.jumpToPreset(entry.index, hardCut) }
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
            val items = surfaceView.milkDropRenderer.playlistItems()
            val position = surfaceView.milkDropRenderer.playlistPosition()
            val entries = items.mapIndexed { index, path -> PresetEntry(index, path) }
            runOnUiThread {
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
    }

    private companion object {
        const val AUTO_ADVANCE_INTERVAL_MS = 15000L
        const val OVERLAY_HIDE_DELAY_MS = 3000L
        const val SWIPE_DISTANCE_THRESHOLD_PX = 120
        const val SWIPE_VELOCITY_THRESHOLD = 200

        // 0 = uncapped. Index 0 (30fps) is the default performance-friendly setting.
        val FPS_OPTIONS = intArrayOf(30, 45, 60, 0)
        val FPS_LABELS = intArrayOf(R.string.fps_30, R.string.fps_45, R.string.fps_60, R.string.fps_uncapped)

        // Fraction of native resolution to render at; lower cuts fragment shader cost for heavy presets.
        val QUALITY_SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)
        val QUALITY_LABELS = intArrayOf(R.string.quality_high, R.string.quality_medium, R.string.quality_low)
    }
}
