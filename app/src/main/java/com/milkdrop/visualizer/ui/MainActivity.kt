package com.milkdrop.visualizer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.audio.AudioCaptureManager
import com.milkdrop.visualizer.databinding.ActivityMainBinding
import com.milkdrop.visualizer.presets.PresetPaths
import com.milkdrop.visualizer.render.MilkDropSurfaceView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var surfaceView: MilkDropSurfaceView
    private lateinit var audioCaptureManager: AudioCaptureManager

    private var autoAdvanceEnabled = true

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { binding.overlayControls.visibility = View.GONE }

    private val autoAdvanceHandler = Handler(Looper.getMainLooper())
    private val autoAdvanceRunnable = object : Runnable {
        override fun run() {
            if (autoAdvanceEnabled) {
                surfaceView.queueEvent { surfaceView.milkDropRenderer.autoAdvance() }
            }
            autoAdvanceHandler.postDelayed(this, AUTO_ADVANCE_INTERVAL_MS)
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
            requestInternalCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveFullscreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PresetPaths.ensureDirsExist()

        surfaceView = MilkDropSurfaceView(this, PresetPaths.presetsDir, PresetPaths.texturesDir)
        binding.surfaceContainer.addView(surfaceView)

        audioCaptureManager = AudioCaptureManager(this) { samples, frameCount, channels ->
            surfaceView.milkDropRenderer.feedPcm(samples, frameCount, channels)
        }

        setupOverlay()
        requestPermissionsThenStartAudio()
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

    private fun setupOverlay() {
        surfaceView.setOnClickListener { toggleOverlay() }

        binding.btnNext.setOnClickListener {
            surfaceView.queueEvent { surfaceView.milkDropRenderer.playNext() }
            resetOverlayTimer()
        }
        binding.btnPrevious.setOnClickListener {
            surfaceView.queueEvent { surfaceView.milkDropRenderer.playPrevious() }
            resetOverlayTimer()
        }
        binding.btnRandom.setOnClickListener {
            surfaceView.queueEvent { surfaceView.milkDropRenderer.playRandom() }
            resetOverlayTimer()
        }
        binding.btnPlayPause.setOnClickListener {
            autoAdvanceEnabled = !autoAdvanceEnabled
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

    private fun resetOverlayTimer() {
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
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
            requestInternalCapture()
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
    }
}
