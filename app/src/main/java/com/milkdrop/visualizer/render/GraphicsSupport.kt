package com.milkdrop.visualizer.render

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log

/** Check before GLSurfaceView starts its thread: EGL creation failures there terminate the app. */
internal object GraphicsSupport {
    data class Result(val supported: Boolean, val version: String)

    fun check(context: Context): Result {
        val advertised = context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val advertisedVersion = "${advertised ushr 16}.${advertised and 0xffff}"
        if (advertised in 1 until 0x30000) return Result(false, advertisedVersion)

        // Some projector firmware misreports capabilities. Verify that an ES 3 context can
        // actually be made current, without loading projectM or starting audio capture.
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return Result(false, advertisedVersion)
        var eglContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var initialized = false
        try {
            if (!EGL14.eglInitialize(display, null, 0, null, 0)) return Result(false, advertisedVersion)
            initialized = true
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return Result(false, advertisedVersion)
            }
            val config = configs[0] ?: return Result(false, advertisedVersion)
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return Result(false, advertisedVersion)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                return Result(false, advertisedVersion)
            }
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            Log.i("GraphicsSupport", "Graphics probe: $version; renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}")
            return Result(version.startsWith("OpenGL ES 3."), version.ifBlank { advertisedVersion })
        } catch (e: RuntimeException) {
            Log.w("GraphicsSupport", "Unable to initialize OpenGL ES 3", e)
            return Result(false, advertisedVersion)
        } finally {
            if (initialized) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
        }
    }
}
