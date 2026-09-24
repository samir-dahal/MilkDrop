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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.audio.AudioCaptureManager
import com.milkdrop.visualizer.databinding.ActivityMainBinding
import com.milkdrop.visualizer.presets.PresetListCache
import com.milkdrop.visualizer.presets.PresetPaths
import com.milkdrop.visualizer.render.MilkDropRenderer.MeshSize
import com.milkdrop.visualizer.render.MilkDropRenderer.Navigation
import com.milkdrop.visualizer.render.MilkDropSurfaceView
import com.milkdrop.visualizer.settings.AppSettings
import java.io.File
import java.util.concurrent.Executors

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
    private var allPresetEntriesSource: List<String>? = null
    private var currentPlaylistPosition = 0
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private var pendingSearch = Runnable {}
    private var searchGeneration = 0

    /** Back closes the preset list instead of exiting the app while the list is open. */
    private val closePlaylistOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePlaylistPanel()
    }
    private var fpsIndex = 0
    private var qualityIndex = 0

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
        applySystemBarInsets()
        requestPermissionsThenStartAudio()
        loadPresetsInBackground()
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
     * Scanning the presets directory takes seconds, so a launch starts from the list cached by the
     * previous run (and resumes the last preset) almost immediately, then rescans in the background
     * and swaps in the new list only if the folder changed. projectM shows its idle preset, with a
     * "Loading presets…" line, until the first list arrives — i.e. only on the very first launch.
     */
    private fun loadPresetsInBackground() {
        val renderer = surfaceView.milkDropRenderer
        renderer.startPresetPath = settings.lastPresetPath
        renderer.onPresetChanged = { path -> settings.lastPresetPath = path }
        renderer.onPresetsReady = { runOnUiThread { binding.presetStatus.visibility = View.GONE } }
        binding.presetStatus.setText(R.string.presets_loading)
        binding.presetStatus.visibility = View.VISIBLE

        val cache = PresetListCache(File(filesDir, PRESET_CACHE_FILE))
        Thread({
            val cached = cache.read()
            if (cached.isNotEmpty()) {
                renderer.loadScannedPresets(cached)
            }

            val startMs = SystemClock.elapsedRealtime()
            val scanned = PresetPaths.scanPresets()
            Log.d(TAG, "Scanned ${scanned.size} presets in ${SystemClock.elapsedRealtime() - startMs} ms")
            // An empty scan (e.g. storage access not granted yet) never overwrites a good cache.
            if (scanned.isNotEmpty() && scanned != cached) {
                cache.write(scanned)
                renderer.loadScannedPresets(scanned)
            }
            if (scanned.isEmpty() && cached.isEmpty()) {
                val message = if (hasAllFilesAccess()) R.string.presets_none_found else R.string.presets_need_access
                runOnUiThread { binding.presetStatus.setText(message) }
            }
        }, "milkdrop-preset-scan").start()
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Keeps the controls clear of the system bars even though they're hidden: in immersive mode
     * the bars reappear transiently over the app, and the bottom row of buttons sat right under
     * the navigation bar's Home button.
     */
    private fun applySystemBarInsets() {
        val overlayBottomPadding = binding.overlayControls.paddingBottom
        val panelTopPadding = binding.playlistPanel.paddingTop
        val panelBottomPadding = binding.playlistPanel.paddingBottom
        val statusTopMargin = (binding.presetStatus.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.overlayControls.updatePadding(bottom = overlayBottomPadding + bars.bottom)
            binding.playlistPanel.updatePadding(top = panelTopPadding + bars.top, bottom = panelBottomPadding + bars.bottom)
            binding.presetStatus.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusTopMargin + bars.top + (STATUS_TOP_GAP_DP * resources.displayMetrics.density).toInt()
            }
            insets
        }
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
        searchExecutor.shutdownNow()
    }

    private fun setImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** A plain tap toggles the controls: tap to show, tap again to hide. No auto-hide, no double-tap. */
    private fun setupGestures() {
        surfaceView.setOnClickListener { toggleOverlay() }
    }

    private fun setupOverlay() {
        binding.btnNext.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Next)
        }
        binding.btnPrevious.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Previous)
        }
        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            settings.shuffleEnabled = shuffleEnabled
            binding.btnShuffle.setText(if (shuffleEnabled) R.string.btn_shuffle_on else R.string.btn_shuffle_off)
            surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
        }
        binding.btnPlaylist.setOnClickListener { openPlaylistPanel() }
        binding.btnPlayPause.setOnClickListener {
            autoAdvanceEnabled = !autoAdvanceEnabled
            settings.autoAdvanceEnabled = autoAdvanceEnabled
            binding.btnPlayPause.setText(if (autoAdvanceEnabled) R.string.btn_auto_on else R.string.btn_auto_off)
            surfaceView.milkDropRenderer.autoAdvanceEnabled = autoAdvanceEnabled
        }
        binding.btnMediaPlayPause.setOnClickListener {
            dispatchMediaPlayPause()
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
        }
        binding.btnFps.setOnClickListener {
            fpsIndex = (fpsIndex + 1) % FPS_OPTIONS.size
            settings.fpsIndex = fpsIndex
            binding.btnFps.setText(FPS_LABELS[fpsIndex])
            surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
        }
        binding.btnQuality.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % QUALITY_SCALES.size
            settings.qualityIndex = qualityIndex
            binding.btnQuality.setText(QUALITY_LABELS[qualityIndex])
            applyQuality()
        }
        binding.btnTransition.setOnClickListener {
            hardCutEnabled = !hardCutEnabled
            settings.hardCutEnabled = hardCutEnabled
            binding.btnTransition.setText(if (hardCutEnabled) R.string.transition_instant else R.string.transition_smooth)
            surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
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
        onBackPressedDispatcher.addCallback(this, closePlaylistOnBack)
        binding.playlistSearch.doAfterTextChanged { scheduleSearch(it?.toString().orEmpty()) }
    }

    private fun openPlaylistPanel() {
        binding.overlayControls.visibility = View.GONE
        binding.playlistSearch.setText("")
        binding.playlistPanel.visibility = View.VISIBLE
        closePlaylistOnBack.isEnabled = true

        surfaceView.queueEvent {
            val position = surfaceView.milkDropRenderer.playlistPosition()
            runOnUiThread {
                val items = surfaceView.milkDropRenderer.playlistItems()
                if (items !== allPresetEntriesSource) {
                    allPresetEntries = items.mapIndexed { index, path -> PresetEntry(index, path) }
                    allPresetEntriesSource = items
                }
                currentPlaylistPosition = position
                presetAdapter.submit(allPresetEntries, position)
                if (allPresetEntries.isNotEmpty()) {
                    binding.playlistRecyclerView.scrollToPosition(position.coerceIn(0, allPresetEntries.size - 1))
                }
            }
        }
    }

    private fun closePlaylistPanel() {
        // The search box's keyboard otherwise stays up after picking a preset, covering (and
        // swallowing taps meant for) the bottom rows of controls.
        binding.playlistSearch.clearFocus()
        WindowCompat.getInsetsController(window, binding.playlistSearch).hide(WindowInsetsCompat.Type.ime())
        binding.playlistPanel.visibility = View.GONE
        closePlaylistOnBack.isEnabled = false
    }

    /**
     * Waits for a pause in typing, then filters ~15k paths off the main thread, so each keystroke
     * doesn't re-filter and rebind the whole list. Results from an older query are dropped.
     */
    private fun scheduleSearch(query: String) {
        binding.playlistSearch.removeCallbacks(pendingSearch)
        pendingSearch = Runnable {
            val generation = ++searchGeneration
            val entries = allPresetEntries
            searchExecutor.execute {
                val filtered = if (query.isBlank()) {
                    entries
                } else {
                    entries.filter { it.path.contains(query, ignoreCase = true) }
                }
                runOnUiThread {
                    if (generation == searchGeneration) {
                        presetAdapter.submit(filtered, currentPlaylistPosition)
                    }
                }
            }
        }
        binding.playlistSearch.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS)
    }

    private fun toggleOverlay() {
        binding.overlayControls.visibility =
            if (binding.overlayControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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

        if (!hasAllFilesAccess()) {
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
        const val TAG = "MainActivity"
        const val PRESET_CACHE_FILE = "preset_list_cache.txt"
        const val SEARCH_DEBOUNCE_MS = 250L
        const val STATUS_TOP_GAP_DP = 12

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
