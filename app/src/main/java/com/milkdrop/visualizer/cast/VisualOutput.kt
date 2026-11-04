package com.milkdrop.visualizer.cast

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import java.io.Closeable

/** Copies only projectM's completed framebuffer to a display or video encoder surface. */
class VisualOutput(
    private val surface: Surface,
    width: Int,
    height: Int,
    private val encoded: Boolean,
    private val onError: (Exception) -> Unit,
    private val onStarted: () -> Unit = {},
) : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var producer: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cleanupSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var nextFrame = 0L
    private var closed = false
    private var started = false
    private var displayInitialized = false
    private var consumerFence = 0L
    @Volatile private var dimensions = width to height
    /** 0 follows every rendered frame; encoders may supply their negotiated hardware limit. */
    @Volatile var maxFramesPerSecond: Int = 0

    fun resize(width: Int, height: Int) {
        if (width > 0 && height > 0) dimensions = width to height
    }

    /** Called on the existing render thread. Neither UI views nor audio enter this copy. */
    @Synchronized fun draw(w: Int, h: Int) {
        val (width, height) = dimensions
        val now = System.nanoTime()
        if (closed || now + 2_000_000 < nextFrame || w <= 0 || h <= 0) return
        val interval = maxFramesPerSecond.takeIf { it > 0 }?.let { 1_000_000_000L / it } ?: 0L
        nextFrame = if (nextFrame == 0L || now - nextFrame > interval) now + interval else nextFrame + interval
        val oldDisplay = EGL14.eglGetCurrentDisplay()
        val oldContext = EGL14.eglGetCurrentContext()
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var fence = 0L
        try {
            check(surface.isValid) { "Casting surface disconnected" }
            if (context == EGL14.EGL_NO_CONTEXT) initialize(oldDisplay, oldContext)
            check(producer == oldContext) { "Graphics context changed during casting" }
            if (consumerFence != 0L) {
                GLES30.glWaitSync(consumerFence, 0, GLES30.GL_TIMEOUT_IGNORED)
                GLES30.glDeleteSync(consumerFence); consumerFence = 0
            }
            val active = integer(GLES30.GL_ACTIVE_TEXTURE)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            val binding = integer(GLES30.GL_TEXTURE_BINDING_2D)
            val readBuffer = integer(GLES30.GL_READ_FRAMEBUFFER_BINDING)
            try {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                if (sourceWidth != w || sourceHeight != h) {
                    // GLSurfaceView requests RGB without alpha. ES disallows copying RGB
                    // into RGBA; RGB accepts both RGB and RGBA source framebuffers.
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB, w, h, 0,
                        GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, null)
                    sourceWidth = w; sourceHeight = h
                }
                GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
                fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                GLES30.glFlush()
            } finally {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readBuffer)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, binding)
                GLES30.glActiveTexture(active)
            }
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "Unable to draw on receiver" }
            EGL14.eglSwapInterval(display, 0)
            if (fence != 0L) { GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED); GLES30.glDeleteSync(fence); fence = 0 }
            if (program == 0) program = createProgram()
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val scale = minOf(width.toFloat() / w, height.toFloat() / h)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            GLES30.glViewport((width - targetW) / 2, (height - targetH) / 2, targetW, targetH)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "frame"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            consumerFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            if (encoded) EGLExt.eglPresentationTimeANDROID(display, window, now)
            check(EGL14.eglSwapBuffers(display, window)) { "Receiver stopped accepting frames" }
            if (!started) { started = true; onStarted() }
        } catch (e: Exception) {
            if (!closed) { closed = true; onError(e) }
        } finally {
            if (fence != 0L) GLES30.glDeleteSync(fence)
            EGL14.eglMakeCurrent(oldDisplay, oldDraw, oldRead, oldContext)
        }
    }

    private fun initialize(currentDisplay: EGLDisplay, shared: EGLContext) {
        check(currentDisplay != EGL14.EGL_NO_DISPLAY && shared != EGL14.EGL_NO_CONTEXT)
        display = currentDisplay; producer = shared
        // Hold our own Android EGL display reference until asynchronous cleanup finishes,
        // even if GLSurfaceView detaches and terminates its reference first.
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        displayInitialized = true
        val attributes = mutableListOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
        if (encoded) attributes.addAll(listOf(0x3142, 1)) // EGL_RECORDABLE_ANDROID
        attributes.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No compatible casting EGL configuration"
        }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, shared, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to share renderer with receiver" }
        cleanupSurface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE && cleanupSurface != EGL14.EGL_NO_SURFACE)
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0); texture = textures[0]
        val active = integer(GLES30.GL_ACTIVE_TEXTURE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val binding = integer(GLES30.GL_TEXTURE_BINDING_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, binding); GLES30.glActiveTexture(active)
    }

    private fun createProgram(): Int {
        val shaders = mutableListOf<Int>()
        var result = 0
        try {
            for ((type, source) in listOf(
                GLES30.GL_VERTEX_SHADER to "#version 300 es\nout highp vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}",
                GLES30.GL_FRAGMENT_SHADER to "#version 300 es\nprecision highp float;uniform highp sampler2D frame;in highp vec2 uv;out vec4 color;void main(){color=texture(frame,uv);}",
            )) {
                val shader = GLES30.glCreateShader(type); shaders.add(shader)
                GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
                val status = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            }
            result = GLES30.glCreateProgram(); shaders.forEach { GLES30.glAttachShader(result, it) }
            GLES30.glLinkProgram(result)
            val status = IntArray(1); GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(result) }
            return result
        } catch (e: Exception) { if (result != 0) GLES30.glDeleteProgram(result); throw e }
        finally { shaders.forEach(GLES30::glDeleteShader) }
    }

    /** Serialized with draw; safe from the session cleanup worker after removing the output. */
    @Synchronized override fun close() {
        closed = true
        if (display == EGL14.EGL_NO_DISPLAY) return
        val oldDisplay = EGL14.eglGetCurrentDisplay(); val oldContext = EGL14.eglGetCurrentContext()
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (context != EGL14.EGL_NO_CONTEXT && cleanupSurface != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, cleanupSurface, cleanupSurface, context)) {
            if (consumerFence != 0L) { GLES30.glDeleteSync(consumerFence); consumerFence = 0 }
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (program != 0) GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (cleanupSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, cleanupSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        if (displayInitialized) { EGL14.eglTerminate(display); displayInitialized = false }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT
        if (oldDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglMakeCurrent(oldDisplay, oldDraw, oldRead, oldContext)
    }

    private fun integer(name: Int) = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]
}
