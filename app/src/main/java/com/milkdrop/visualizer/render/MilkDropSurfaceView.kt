package com.milkdrop.visualizer.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import java.io.File

class MilkDropSurfaceView(
    context: Context,
    texturesDir: File,
) : GLSurfaceView(context) {

    val milkDropRenderer = MilkDropRenderer(texturesDir)

    /** 0 means uncapped (render on every vsync). */
    var targetFps: Int = 0
        set(value) {
            field = value
            frameIntervalNanos = if (value <= 0) 0L else 1_000_000_000L / value
            nextRenderNanos = 0L
        }

    private var frameIntervalNanos = 0L
    private var nextRenderNanos = 0L
    private var pacing = false

    /**
     * Frame pacing is driven by vsync instead of sleeping on the GL thread. Sleeping after each
     * frame drifted out of phase with the display: on this 90 Hz panel a 45 fps cap came out as an
     * uneven mix of 2- and 3-vsync frames (~21–34 ms), which reads as stutter even at a decent
     * average fps. Requesting renders on whole vsyncs keeps frame times even.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pacing) return
            val interval = frameIntervalNanos
            if (interval == 0L) {
                requestRender()
            } else if (frameTimeNanos >= nextRenderNanos - VSYNC_SLACK_NANOS) {
                requestRender()
                // Advance on a fixed schedule so rates that don't divide the refresh rate still
                // average out right, but resync after a long stall instead of bursting to catch up.
                nextRenderNanos = if (frameTimeNanos - nextRenderNanos > interval) {
                    frameTimeNanos + interval
                } else {
                    nextRenderNanos + interval
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setEGLContextClientVersion(3)
        // Keeps projectM's GL objects (and the loaded playlist) alive across pause/resume, e.g.
        // behind the permission and screen-capture dialogs, instead of rebuilding it every time.
        preserveEGLContextOnPause = true
        setRenderer(milkDropRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onResume() {
        super.onResume()
        if (!pacing) {
            pacing = true
            nextRenderNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onPause() {
        pacing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onPause()
    }

    /**
     * Renders into a smaller native buffer that the compositor upscales to fill the view —
     * cuts per-frame fragment shader cost for heavy presets without any FBO/blit code of our own.
     * Must run after layout, since it needs the view's actual on-screen size.
     */
    fun setResolutionScale(scale: Float) {
        post {
            if (width > 0 && height > 0) {
                val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
                holder.setFixedSize(scaledWidth, scaledHeight)
            }
        }
    }

    private companion object {
        /** Tolerance for vsync timestamp jitter, so a render due "right about now" isn't pushed a whole vsync late. */
        const val VSYNC_SLACK_NANOS = 4_000_000L
    }
}
