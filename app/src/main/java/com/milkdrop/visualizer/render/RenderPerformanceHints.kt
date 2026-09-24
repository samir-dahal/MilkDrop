package com.milkdrop.visualizer.render

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicLong

/**
 * Reports each frame's real duration against its target to Android's Performance Hint API.
 *
 * Power governors judge load per thread, so a frame whose work is spread over the GL thread and
 * the GPU driver's threads can look light even while it badly misses its deadline. Reporting
 * "this frame took 130 ms against a 22 ms target" lets platforms with a working hint implementation
 * (e.g. uclamp-based ones) raise clocks for the session's threads. On the Galaxy F15 (Dimensity
 * 6100+) the session was accepted but had no measurable effect, which is why [GpuDriverThreads]
 * also pins the driver threads; this stays for devices where the hints do work.
 *
 * The duration reported is roughly the frame's own work, including time blocked waiting on the
 * GPU, but not the idle wait for the next scheduled frame (otherwise light presets would look busy
 * too and keep clocks needlessly high). The split is approximate, off by at most one frame interval
 * when the GPU is the bottleneck, which is still far over target and so still triggers a boost.
 *
 * [onRenderThreadStarted], [onFrameStart], [onFrameEnd] and [release] must run on the GL thread;
 * [setTargetFrameNanos] and [onRenderRequested] are safe from any thread.
 */
class RenderPerformanceHints(context: Context) {

    private val appContext = context.applicationContext

    private var session: HintSession? = null

    @Volatile
    private var targetFrameNanos: Long = 0L
    private var appliedTargetFrameNanos: Long = 0L

    /** Time of the first render request since the last frame started; 0 when none is pending. */
    private val pendingRequestNanos = AtomicLong(0L)
    private var lastFrameStartNanos = 0L
    private var lastFrameEndNanos = 0L

    fun setTargetFrameNanos(nanos: Long) {
        targetFrameNanos = nanos
    }

    /** Called by the pacing loop right before it asks GLSurfaceView for a frame. */
    fun onRenderRequested(nowNanos: Long) {
        pendingRequestNanos.compareAndSet(0L, nowNanos)
    }

    fun onRenderThreadStarted() {
        session?.close()
        session = null
        lastFrameStartNanos = 0L
        val target = targetFrameNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && target > 0L) {
            val glTid = intArrayOf(Process.myTid())
            // Fall back to the GL thread alone if the platform rejects the driver threads.
            session = HintSession.create(appContext, glTid + GpuDriverThreads.threadIds(), target)
                ?: HintSession.create(appContext, glTid, target)
            appliedTargetFrameNanos = target
        }
    }

    fun onFrameStart(nowNanos: Long) {
        val requestNanos = pendingRequestNanos.getAndSet(0L)
        val currentSession = session ?: return

        val target = targetFrameNanos
        if (target > 0L && target != appliedTargetFrameNanos) {
            currentSession.updateTarget(target)
            appliedTargetFrameNanos = target
        }

        val previousStart = lastFrameStartNanos
        lastFrameStartNanos = nowNanos
        if (previousStart == 0L) return

        // Idle = time the previous frame had finished but the next one wasn't requested yet.
        val idleNanos = if (requestNanos > lastFrameEndNanos) requestNanos - lastFrameEndNanos else 0L
        val workNanos = nowNanos - previousStart - idleNanos
        // Skip gaps from pauses or surface changes, which aren't real frames.
        if (workNanos in 1 until MAX_REPORTED_FRAME_NANOS) {
            currentSession.reportActual(workNanos)
        }
    }

    fun onFrameEnd(nowNanos: Long) {
        lastFrameEndNanos = nowNanos
    }

    fun release() {
        session?.close()
        session = null
    }

    private interface HintSession {
        fun reportActual(nanos: Long)
        fun updateTarget(nanos: Long)
        fun close()

        companion object {
            @RequiresApi(Build.VERSION_CODES.S)
            fun create(context: Context, tids: IntArray, targetNanos: Long): HintSession? {
                val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
                val platformSession = runCatching { manager.createHintSession(tids, targetNanos) }.getOrNull()
                    ?: return null
                return object : HintSession {
                    override fun reportActual(nanos: Long) = platformSession.reportActualWorkDuration(nanos)
                    override fun updateTarget(nanos: Long) = platformSession.updateTargetWorkDuration(nanos)
                    override fun close() = platformSession.close()
                }
            }
        }
    }

    private companion object {
        const val MAX_REPORTED_FRAME_NANOS = 1_000_000_000L
    }
}
