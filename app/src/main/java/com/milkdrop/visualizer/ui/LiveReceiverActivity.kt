package com.milkdrop.visualizer.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.cast.DirectCastReceiver

/** Receiver-only entry point: never creates projectM, an EGL context or audio capture. */
class LiveReceiverActivity : AppCompatActivity() {
    private lateinit var video: SurfaceView
    private lateinit var status: TextView
    private var receiver: DirectCastReceiver? = null
    private var receiving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_receiver)
        video = findViewById(R.id.liveReceiverVideo)
        status = findViewById(R.id.liveReceiverStatus)
        showWaiting()
        receiver = DirectCastReceiver(this, onIncoming = { sender ->
            receiving = true
            status.text = getString(R.string.receiver_only_connecting, sender)
            status.visibility = View.VISIBLE
            receiver?.offerSurface(video.holder.surface)
        }, onPresented = {
            status.visibility = View.GONE
        }, onStopped = {
            showWaiting()
        }, onError = {
            Toast.makeText(this, R.string.cast_failed, Toast.LENGTH_LONG).show()
        })
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { receiver?.offerSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                receiver?.offerSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { receiver?.stopStream() }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!receiving) finish() }
        })
    }

    private fun showWaiting() {
        receiving = false
        status.setText(R.string.receiver_only_waiting)
        // Opaque overlay hides the decoder's last frame after disconnection.
        status.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        receiver?.start()
    }

    override fun onPause() {
        receiver?.stop()
        showWaiting()
        super.onPause()
    }

    override fun onDestroy() {
        receiver?.close()
        receiver = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!receiving && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
