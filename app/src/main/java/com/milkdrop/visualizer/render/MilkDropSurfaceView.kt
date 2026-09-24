package com.milkdrop.visualizer.render

import android.content.Context
import android.opengl.GLSurfaceView
import java.io.File

class MilkDropSurfaceView(
    context: Context,
    presetsDir: File,
    texturesDir: File,
) : GLSurfaceView(context) {

    val milkDropRenderer = MilkDropRenderer(presetsDir, texturesDir)

    init {
        setEGLContextClientVersion(3)
        setRenderer(milkDropRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
