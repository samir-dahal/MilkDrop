package com.milkdrop.visualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMeterTest {

    private val meter = FrameRateMeter()

    /** Feeds frames at a steady rate from [startNanos]; returns every average the meter reported. */
    private fun feed(fps: Int, frames: Int, startNanos: Long = 0L): List<Int> {
        val interval = 1_000_000_000L / fps
        return (0 until frames).mapNotNull { meter.onFrame(startNanos + it * interval) }
    }

    @Test
    fun `reports nothing before a full second has passed`() {
        assertEquals(emptyList<Int>(), feed(fps = 60, frames = 30))
    }

    @Test
    fun `reports the steady frame rate once per second`() {
        assertEquals(listOf(60, 60), feed(fps = 60, frames = 125))
    }

    @Test
    fun `measures low and high rates`() {
        assertEquals(listOf(30), feed(fps = 30, frames = 35))
        meter.reset()
        assertEquals(listOf(90), feed(fps = 90, frames = 100))
    }

    @Test
    fun `a long gap restarts the window instead of lowering the average`() {
        feed(fps = 60, frames = 40)
        val afterPause = 5_000_000_000L
        val reported = feed(fps = 60, frames = 70, startNanos = afterPause)
        assertEquals(listOf(60), reported)
    }

    @Test
    fun `reset starts a fresh window`() {
        feed(fps = 60, frames = 50)
        meter.reset()
        assertNull(meter.onFrame(10_000_000_000L))
    }
}
