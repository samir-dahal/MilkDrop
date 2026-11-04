package com.milkdrop.visualizer.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Surface-input AVC encoding; never reads the screen or captures the app's controls/audio. */
internal class VisualStreamEncoder(
    private val server: LiveStreamServer? = null,
    private val onReady: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val direct: DirectVideoSender? = null,
    requestedFramesPerSecond: Int = 60,
    private val live: LiveCastServer? = null,
    fullHdSupported: Boolean = false,
) : Closeable {
    val width: Int
    val height: Int
    val framesPerSecond: Int
    private val codec: MediaCodec
    val surface: Surface
    private val running = AtomicBoolean(true)
    private val drainThread: Thread
    private val syncRequested = AtomicBoolean(false)

    init {
        var selected: MediaCodec? = null
        var selectedSurface: Surface? = null
        var size = 960 to 544
        var selectedRate = 0
        var failure: Exception? = null
        // Some older vendor codecs reject optional realtime hints. Retry the same
        // mode without hints before sacrificing resolution or frame rate.
        val attempts = CastEncodingPolicy.modes(requestedFramesPerSecond, fullHdSupported).flatMap { mode ->
            listOf(mode to true, mode to false)
        }
        for ((mode, realtime) in attempts) {
            val (w, h, requestedRate) = mode
            val candidate = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                catch (e: Exception) { failure = e; continue }
            try {
                val codecCapabilities = candidate.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val capabilities = checkNotNull(codecCapabilities.videoCapabilities)
                val rates = capabilities.getSupportedFrameRatesFor(w, h)
                // Keep support for older encoders whose ceiling is below 30 fps.
                // High-rate attempts must not silently become a slow HD mode.
                val fps = if (requestedRate <= 30) minOf(requestedRate, kotlin.math.floor(rates.upper).toInt()) else requestedRate
                check(capabilities.areSizeAndRateSupported(w, h, fps.toDouble())) { "Encoder cannot sustain $w x $h at $fps fps" }
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, capabilities.bitrateRange.clamp(CastEncodingPolicy.bitRate(mode.copy(fps = fps))))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, CastVideoTiming.KEY_FRAME_SECONDS)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    if (realtime) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setInteger(MediaFormat.KEY_LATENCY, 0)
                        setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                        if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        if (codecCapabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                        }
                    }
                }
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                selectedSurface = candidate.createInputSurface()
                candidate.start()
                selected = candidate; size = w to h; selectedRate = fps
                Log.i("VisualStreamEncoder", "Cast encoder ${candidate.name}: ${w}x$h at $fps fps, realtime hints=$realtime")
                break
            } catch (e: Exception) {
                failure = e
                selectedSurface?.release(); selectedSurface = null
                runCatching { candidate.release() }
            }
        }
        codec = selected ?: throw IllegalStateException("No compatible AVC surface encoder", failure)
        surface = checkNotNull(selectedSurface)
        width = size.first; height = size.second
        framesPerSecond = selectedRate
        direct?.requestKeyFrame = { syncRequested.set(true) }
        live?.requestKeyFrame = { syncRequested.set(true) }
        drainThread = Thread(::drain, "MilkDrop-cast-encoder").apply { isDaemon = true; start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val muxer = TransportStream()
        val headers = AvcHeaders()
        var segmentStart = -1L
        var firstTimestamp = -1L
        var previousTime = -1L
        var discontinuity = false
        var nextDiscontinuity = false
        var ready = false
        var lastKeyRequest = 0L
        var pendingLive: Triple<ByteArray, Long, Boolean>? = null
        val frameDurationUs = 1_000_000L / framesPerSecond
        val maxSegmentUs = CastVideoTiming.TARGET_SEGMENT_SECONDS * 1_000_000L
        fun publish(duration: Double) {
            val available = server?.publish(muxer.bytes(), duration.coerceAtLeast(0.001), discontinuity) == true
            if (available && !ready) { ready = true; onReady() }
        }
        fun requestKeyFrame() {
            val now = System.nanoTime()
            if (now - lastKeyRequest < 200_000_000) return
            lastKeyRequest = now
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            while (running.get()) {
                if (syncRequested.getAndSet(false)) requestKeyFrame()
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    listOf("csd-0", "csd-1").mapNotNull { codec.outputFormat.getByteBuffer(it) }
                        .forEach { headers.add(annexB(it.duplicate())) }
                } else if (index >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        if (info.size <= 0) continue
                        check(info.offset >= 0 && info.offset <= buffer.capacity() && info.size <= buffer.capacity() - info.offset) {
                            "Invalid encoder output range"
                        }
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = annexB(buffer.slice())
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            headers.add(bytes); continue
                        }
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (firstTimestamp < 0) firstTimestamp = info.presentationTimeUs
                        val timeUs = (info.presentationTimeUs - firstTimestamp).coerceAtLeast(0)
                        if (!headers.complete) { requestKeyFrame(); continue }
                        if (live != null) {
                            live.configure(width, height, framesPerSecond, headers.sequence(), headers.picture())
                            val previous = pendingLive
                            if (previous != null) {
                                val publishedKey = live.offer(previous.first, previous.second,
                                    (timeUs - previous.second).coerceAtLeast(1), previous.third)
                                if (publishedKey && !ready) { ready = true; onReady() }
                            }
                            pendingLive = Triple(bytes, timeUs, key)
                            if (!ready && !key) requestKeyFrame()
                            continue
                        }
                        if (direct != null) {
                            direct.configure(width, height, headers.bytes)
                            direct.offer((if (key) headers.bytes else byteArrayOf()) + bytes, timeUs, key)
                            continue
                        }
                        // A slow preset load can leave a gap. End the old segment before the
                        // gap and restart at a key frame with an explicit HLS discontinuity.
                        if (segmentStart >= 0 && previousTime >= 0 && timeUs - previousTime > 500_000) {
                            publish((previousTime - segmentStart + frameDurationUs).coerceAtMost(maxSegmentUs) / 1_000_000.0)
                            segmentStart = -1; nextDiscontinuity = true
                        }
                        if (segmentStart < 0 && !key) { requestKeyFrame(); continue }
                        if (segmentStart >= 0 && timeUs - segmentStart > maxSegmentUs) {
                            publish((previousTime - segmentStart + frameDurationUs).coerceAtMost(maxSegmentUs) / 1_000_000.0)
                            segmentStart = -1; nextDiscontinuity = true
                            if (!key) { requestKeyFrame(); continue }
                        }
                        if (segmentStart >= 0 && !key && timeUs - segmentStart >= 450_000) requestKeyFrame()
                        if (key) {
                            if (segmentStart >= 0) {
                                publish((timeUs - segmentStart) / 1_000_000.0)
                            }
                            muxer.begin(); segmentStart = timeUs
                            discontinuity = nextDiscontinuity; nextDiscontinuity = false
                        }
                        muxer.video(byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte()) +
                            (if (key) headers.bytes else ByteArray(0)) + bytes, timeUs)
                        check(muxer.byteCount <= 8 * 1024 * 1024) { "Encoder did not produce key frames" }
                        previousTime = timeUs
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError(e)
        }
    }

    /** Called after the GL output is detached, so no producer can write into a released surface. */
    override fun close() {
        if (!running.getAndSet(false)) return
        direct?.requestKeyFrame = null
        live?.requestKeyFrame = null
        if (Thread.currentThread() !== drainThread) drainThread.join(1000)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        surface.release()
    }

    private fun annexB(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) return bytes
        val output = java.io.ByteArrayOutputStream()
        val data = ByteBuffer.wrap(bytes)
        while (data.remaining() >= 4) {
            val length = data.int
            require(length > 0 && length <= data.remaining()) { "Invalid AVC packet" }
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(ByteArray(length).also { data.get(it) })
        }
        require(!data.hasRemaining()) { "Truncated AVC packet" }
        return output.toByteArray()
    }

}
