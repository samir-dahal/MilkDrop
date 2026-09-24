package com.milkdrop.visualizer.render

import android.content.Context
import android.opengl.GLSurfaceView
import java.io.File

class MilkDropSurfaceView(
    context: Context,
    texturesDir: File,
) : GLSurfaceView(context) {

    val milkDropRenderer = MilkDropRenderer(texturesDir)

    init {
        setEGLContextClientVersion(3)
        setRenderer(milkDropRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
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
}
