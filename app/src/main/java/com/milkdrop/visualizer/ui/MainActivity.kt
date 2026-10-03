package com.milkdrop.visualizer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.audio.AudioCaptureManager
import com.milkdrop.visualizer.audio.AudioInput
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
    private var fpsIndex = 0
    private var qualityIndex = 0

    /** Set while code (not the user) changes a setting's selection, so its listener ignores it. */
    private var updatingControls = false

    /** Loading/error message for the top label; while set, it shows instead of the preset name. */
    @StringRes
    private var statusMessage: Int? = R.string.presets_loading
    private var currentPresetName: String? = null
    private val hidePresetName = Runnable { updateTopLabel() }
    private var presetNameShownUntil = 0L

    /** Back closes the open panel (preset list or settings) instead of exiting the app. */
    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.playlistPanel.isVisible) closePlaylistPanel() else closeSettingsSheet(showControls = true)
        }
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
        restoreControlStates()

        PresetPaths.ensureDirsExist()

        surfaceView = MilkDropSurfaceView(this, PresetPaths.texturesDir)
        binding.surfaceContainer.addView(surfaceView)
        applyRestoredRenderSettings()

        audioCaptureManager = AudioCaptureManager(this) { samples, frameCount, channels ->
            surfaceView.milkDropRenderer.feedPcm(samples, frameCount, channels)
        }

        setupGestures()
        setupControlBar()
        setupSettingsSheet()
        setupPlaylistPanel()
        onBackPressedDispatcher.addCallback(this, closePanelOnBack)
        applyOrientation(resources.configuration.orientation)
        applySystemBarInsets()
        requestPermissionsThenStartAudio()
        loadPresetsInBackground()
    }

    /** The layout shows defaults; set every control to match what was restored. */
    private fun restoreControlStates() {
        updateShuffleButton()
        updatingControls = true
        binding.switchAutoAdvance.isChecked = autoAdvanceEnabled
        binding.groupTransition.check(if (hardCutEnabled) R.id.transitionInstant else R.id.transitionSmooth)
        binding.groupFrameRate.check(FPS_BUTTON_IDS[fpsIndex])
        binding.groupQuality.check(QUALITY_BUTTON_IDS[qualityIndex])
        binding.groupAudioSource.check(AUDIO_INPUT_BUTTON_IDS.getValue(settings.audioInput))
        updatingControls = false
        binding.audioSourceDescription.setText(AUDIO_INPUT_DESCRIPTIONS.getValue(settings.audioInput))
    }

    /**
     * Restored control states are cosmetic only — actually apply the settings. The renderer picks
     * up this state itself once its GL surface exists (see MilkDropRenderer's class doc).
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
        renderer.onPresetChanged = { path ->
            settings.lastPresetPath = path
            runOnUiThread { showPresetName(File(path).nameWithoutExtension) }
        }
        renderer.onPresetsReady = { runOnUiThread { setStatusMessage(null) } }
        setStatusMessage(R.string.presets_loading)

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
                runOnUiThread { setStatusMessage(message) }
            }
        }, "milkdrop-preset-scan").start()
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun setStatusMessage(@StringRes message: Int?) {
        statusMessage = message
        updateTopLabel()
    }

    /** The preset name shows while the controls are open, and briefly whenever the preset changes. */
    private fun showPresetName(name: String) {
        currentPresetName = name
        presetNameShownUntil = SystemClock.uptimeMillis() + PRESET_NAME_FLASH_MS
        binding.presetStatus.removeCallbacks(hidePresetName)
        binding.presetStatus.postDelayed(hidePresetName, PRESET_NAME_FLASH_MS)
        updateTopLabel()
    }

    private fun updateTopLabel() {
        val label = binding.presetStatus
        val message = statusMessage
        if (message != null) {
            label.setText(message)
            label.isVisible = true
            return
        }
        // Not with the settings sheet open: in landscape the sheet reaches the top, under the name.
        val name = currentPresetName
        val shown = binding.controlBar.isVisible ||
            (!binding.settingsSheet.isVisible && SystemClock.uptimeMillis() < presetNameShownUntil)
        label.text = name
        label.isVisible = name != null && shown
    }

    /**
     * Keeps the controls clear of the system bars even though they're hidden: in immersive mode
     * the bars reappear transiently over the app, and the bottom row of buttons sat right under
     * the navigation bar's Home button. In landscape the navigation bar and cutout sit at a side.
     */
    private fun applySystemBarInsets() {
        val barPadding = Padding.of(binding.controlBar)
        val sheetPadding = Padding.of(binding.settingsSheet)
        val panelPadding = Padding.of(binding.playlistPanel)
        val statusTopMargin = (binding.presetStatus.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.controlBar.updatePadding(
                left = barPadding.left + bars.left, right = barPadding.right + bars.right,
                bottom = barPadding.bottom + bars.bottom,
            )
            binding.settingsSheet.updatePadding(
                left = sheetPadding.left + bars.left, right = sheetPadding.right + bars.right,
                bottom = sheetPadding.bottom + bars.bottom,
            )
            binding.playlistPanel.updatePadding(
                left = panelPadding.left + bars.left, top = panelPadding.top + bars.top,
                right = panelPadding.right + bars.right, bottom = panelPadding.bottom + bars.bottom,
            )
            binding.presetStatus.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusTopMargin + bars.top + (STATUS_TOP_GAP_DP * resources.displayMetrics.density).toInt()
            }
            insets
        }
    }

    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        companion object {
            fun of(view: View) = Padding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        }
    }

    /** Rotation doesn't recreate the activity (see the manifest), so the control layout switches here. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    /**
     * Portrait: preset controls, a divider, then the media keys below. Landscape: a single row —
     * preset controls (Settings last), a vertical divider, then the media keys.
     */
    private fun applyOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        binding.controlBar.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.presetControls.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            weight = if (landscape) 1f else 0f
        }
        binding.controlDivider.updateLayoutParams<LinearLayout.LayoutParams> {
            val gap = (DIVIDER_GAP_DP * density).toInt()
            if (landscape) {
                width = density.toInt().coerceAtLeast(1)
                height = (LANDSCAPE_DIVIDER_HEIGHT_DP * density).toInt()
                setMargins(gap, 0, gap, 0)
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = density.toInt().coerceAtLeast(1)
                setMargins(0, gap, 0, gap)
            }
        }
        binding.settingsSheet.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (landscape) (LANDSCAPE_SHEET_WIDTH_DP * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
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

    /**
     * A plain tap toggles the controls: tap to show, tap again to hide. No auto-hide, no double-tap.
     * With the settings sheet open, a tap on the visuals closes it.
     */
    private fun setupGestures() {
        surfaceView.setOnClickListener {
            if (binding.settingsSheet.isVisible) closeSettingsSheet(showControls = false) else toggleControlBar()
        }
    }

    private fun setupControlBar() {
        binding.btnNext.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Next)
        }
        binding.btnPrevious.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Previous)
        }
        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            settings.shuffleEnabled = shuffleEnabled
            updateShuffleButton()
            surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
        }
        binding.btnPlaylist.setOnClickListener { openPlaylistPanel() }
        binding.btnSettings.setOnClickListener { openSettingsSheet() }
        binding.btnMediaPrevious.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.btnMediaPlayPause.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.btnMediaNext.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    private fun updateShuffleButton() {
        binding.btnShuffle.isActivated = shuffleEnabled
        ViewCompat.setStateDescription(
            binding.btnShuffle,
            getString(if (shuffleEnabled) R.string.state_on else R.string.state_off),
        )
    }

    private fun setupSettingsSheet() {
        binding.btnCloseSettings.setOnClickListener { closeSettingsSheet(showControls = true) }
        binding.switchAutoAdvance.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            autoAdvanceEnabled = checked
            settings.autoAdvanceEnabled = checked
            surfaceView.milkDropRenderer.autoAdvanceEnabled = checked
        }
        binding.groupTransition.onUserSelection { id ->
            hardCutEnabled = id == R.id.transitionInstant
            settings.hardCutEnabled = hardCutEnabled
            surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
        }
        binding.groupFrameRate.onUserSelection { id ->
            fpsIndex = FPS_BUTTON_IDS.indexOf(id)
            settings.fpsIndex = fpsIndex
            surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
        }
        binding.groupQuality.onUserSelection { id ->
            qualityIndex = QUALITY_BUTTON_IDS.indexOf(id)
            settings.qualityIndex = qualityIndex
            applyQuality()
        }
        binding.groupAudioSource.onUserSelection { id ->
            audioCaptureManager.stopAll()
            startAudioInput(AUDIO_INPUT_BUTTON_IDS.entries.first { it.value == id }.key)
        }
    }

    /** Runs [action] when the user picks a different option, not when code changes the selection. */
    private fun RadioGroup.onUserSelection(action: (checkedId: Int) -> Unit) {
        setOnCheckedChangeListener { _, checkedId ->
            if (!updatingControls && checkedId != View.NO_ID) action(checkedId)
        }
    }

    private fun openSettingsSheet() {
        binding.controlBar.isVisible = false
        binding.settingsSheet.isVisible = true
        binding.settingsScroll.scrollTo(0, 0)
        closePanelOnBack.isEnabled = true
        updateTopLabel()
    }

    private fun closeSettingsSheet(showControls: Boolean) {
        binding.settingsSheet.isVisible = false
        binding.controlBar.isVisible = showControls
        closePanelOnBack.isEnabled = binding.playlistPanel.isVisible
        updateTopLabel()
    }

    private fun setupPlaylistPanel() {
        binding.playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        presetAdapter = PresetListAdapter { entry ->
            surfaceView.milkDropRenderer.requestNavigation(Navigation.JumpTo(entry.index))
            closePlaylistPanel()
        }
        binding.playlistRecyclerView.adapter = presetAdapter

        binding.btnClosePlaylist.setOnClickListener { closePlaylistPanel() }
        binding.playlistSearch.doAfterTextChanged { scheduleSearch(it?.toString().orEmpty()) }
    }

    private fun openPlaylistPanel() {
        binding.controlBar.isVisible = false
        binding.playlistSearch.setText("")
        binding.playlistPanel.isVisible = true
        closePanelOnBack.isEnabled = true
        updateTopLabel()

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
        // swallowing taps meant for) the controls.
        binding.playlistSearch.clearFocus()
        WindowCompat.getInsetsController(window, binding.playlistSearch).hide(WindowInsetsCompat.Type.ime())
        binding.playlistPanel.isVisible = false
        closePanelOnBack.isEnabled = binding.settingsSheet.isVisible
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

    private fun toggleControlBar() {
        binding.controlBar.isVisible = !binding.controlBar.isVisible
        updateTopLabel()
    }

    /** Sends a media key to whichever app is playing, as headset buttons would. */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(AudioManager::class.java)
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
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

    private fun startPersistedAudioSource() = startAudioInput(settings.audioInput)

    private fun startAudioInput(input: AudioInput) {
        when (input) {
            AudioInput.MIC -> switchToMic()
            AudioInput.PHONE_AUDIO -> {
                showAudioInput(AudioInput.PHONE_AUDIO)
                requestInternalCapture()
            }
            AudioInput.OUTPUT_MIX ->
                if (audioCaptureManager.startOutputMix()) {
                    showAudioInput(AudioInput.OUTPUT_MIX)
                } else {
                    switchToMic()
                    binding.audioSourceDescription.setText(R.string.audio_source_output_mix_unavailable)
                }
        }
    }

    private fun requestInternalCapture() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /** Also the fallback when another source fails or is declined, so it moves the selection to Mic. */
    private fun switchToMic() {
        audioCaptureManager.startMic()
        showAudioInput(AudioInput.MIC)
    }

    /** Saves [input] and shows it as selected, with its description. */
    private fun showAudioInput(input: AudioInput) {
        settings.audioInput = input
        updatingControls = true
        binding.groupAudioSource.check(AUDIO_INPUT_BUTTON_IDS.getValue(input))
        updatingControls = false
        binding.audioSourceDescription.setText(AUDIO_INPUT_DESCRIPTIONS.getValue(input))
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PRESET_CACHE_FILE = "preset_list_cache.txt"
        const val SEARCH_DEBOUNCE_MS = 250L
        const val STATUS_TOP_GAP_DP = 12
        const val PRESET_NAME_FLASH_MS = 2000L
        const val DIVIDER_GAP_DP = 4
        const val LANDSCAPE_DIVIDER_HEIGHT_DP = 32
        const val LANDSCAPE_SHEET_WIDTH_DP = 480

        // 0 = uncapped ("Max"). Index 0 (30fps) is the default performance-friendly setting.
        val FPS_OPTIONS = intArrayOf(30, 45, 60, 0)
        val FPS_BUTTON_IDS = intArrayOf(R.id.fps30, R.id.fps45, R.id.fps60, R.id.fpsMax)

        // Fraction of native resolution to render at; lower cuts fragment shader cost for heavy presets.
        val QUALITY_SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)
        // Coarser warp mesh at Low: fewer per-vertex equation evaluations on the CPU each frame.
        val QUALITY_MESH_SIZES = arrayOf(MeshSize.DEFAULT, MeshSize.DEFAULT, MeshSize(24, 18))
        val QUALITY_BUTTON_IDS = intArrayOf(R.id.qualityHigh, R.id.qualityMedium, R.id.qualityLow)

        val AUDIO_INPUT_BUTTON_IDS = mapOf(
            AudioInput.MIC to R.id.audioSourceMic,
            AudioInput.PHONE_AUDIO to R.id.audioSourcePhone,
            AudioInput.OUTPUT_MIX to R.id.audioSourceOutputMix,
        )
        val AUDIO_INPUT_DESCRIPTIONS = mapOf(
            AudioInput.MIC to R.string.audio_source_mic_description,
            AudioInput.PHONE_AUDIO to R.string.audio_source_phone_description,
            AudioInput.OUTPUT_MIX to R.string.audio_source_output_mix_description,
        )
    }
}
