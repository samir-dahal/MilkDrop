package com.milkdrop.visualizer.cast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Foreground TV receiver: a single authenticated LAN peer, video only, no playback timeline. */
class DirectCastReceiver(
    context: Context,
    private val onIncoming: (String) -> Unit,
    private val onPresented: () -> Unit,
    private val onStopped: () -> Unit,
    private val onError: () -> Unit,
) : Closeable {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val identity = directCastIdentity(context)
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicReference<Peer?>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var registration: NsdManager.RegistrationListener? = null
    private var server: ServerSocket? = null
    @Volatile private var generation = 0

    fun start() {
        if (server != null) return
        val id = ++generation
        try {
            val listener = ServerSocket(0, 2)
            server = listener
            val token = UUID.randomUUID().toString()
            val info = NsdServiceInfo().apply {
                serviceType = DirectCastProtocol.SERVICE
                serviceName = "MilkDrop-${identity.take(8)}"
                port = listener.localPort
                setAttribute("version", "1"); setAttribute("id", identity)
                setAttribute("token", token)
                setAttribute("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
            }
            val callback = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                    main.post { if (id == generation) stop() }
                }
            }
            registration = callback
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback)
            Thread({ accept(listener, token, id) }, "MilkDrop-live-listen").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            Log.i("MilkDropCast", "Direct TV discovery unavailable", e)
            stop()
        }
    }

    private fun accept(listener: ServerSocket, token: String, id: Int) {
        while (id == generation) {
            val socket = try { listener.accept() } catch (_: Exception) { return }
            sockets.add(socket)
            var handedOff = false
            try {
                if (id != generation) continue
                socket.tcpNoDelay = true; socket.soTimeout = 8000
                socket.receiveBufferSize = 64 * 1024
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                if (input.readInt() != DirectCastProtocol.MAGIC || input.readUTF() != token) continue
                val name = input.readUTF().take(80)
                val peer = Peer(socket, input, output)
                if (!active.compareAndSet(null, peer)) { output.writeByte(0); output.flush(); continue }
                if (id != generation) { active.compareAndSet(peer, null); peer.close(); continue }
                main.post { if (id == generation && active.get() === peer) onIncoming(name) }
                Thread({ receive(peer, id) }, "MilkDrop-live-decode").apply { isDaemon = true; start() }
                handedOff = true
            } catch (_: Exception) {
                // Invalid handshakes never change the TV's local visualizer or show an error.
            } finally {
                if (!handedOff) { sockets.remove(socket); runCatching { socket.close() } }
            }
        }
    }

    fun offerSurface(surface: Surface) {
        if (surface.isValid) active.get()?.let { it.surface = surface; it.surfaceReady.countDown() }
    }

    fun stopStream() {
        active.getAndSet(null)?.let { peer ->
            peer.requestStop()
            // Give the decoder worker a short chance to notify the phone, with a hard close
            // if its output is blocked. Socket writes and codec release never run on the UI.
            main.postDelayed({ peer.close() }, 200)
            onStopped()
        }
    }

    private fun receive(peer: Peer, id: Int) {
        var decoder: MediaCodec? = null
        var presented = false
        var needsKey = true
        var failed = false
        val info = MediaCodec.BufferInfo()
        fun feedback(type: Int, pts: Long = 0) {
            peer.output.writeByte(type)
            if (type == DirectCastProtocol.PRESENTED || type == DirectCastProtocol.ACCEPTED) peer.output.writeLong(pts)
            peer.output.flush()
        }
        fun drain(waitUs: Long = 0) {
            val codec = decoder ?: return
            var latest = -1
            var latestPts = 0L
            var wait = waitUs
            while (true) {
                val index = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                if (index < 0) break
                if (latest >= 0) codec.releaseOutputBuffer(latest, false)
                latest = index; latestPts = info.presentationTimeUs
            }
            if (latest >= 0) {
                codec.releaseOutputBuffer(latest, true)
                feedback(DirectCastProtocol.PRESENTED, latestPts)
                if (!presented) {
                    presented = true
                    main.post { if (id == generation && active.get() === peer) onPresented() }
                }
            }
        }
        try {
            check(peer.surfaceReady.await(8, TimeUnit.SECONDS) && !peer.closed.get() && !peer.stopping.get()) { "No TV video surface" }
            peer.output.writeByte(1); peer.output.flush()
            while (!peer.closed.get() && !peer.stopping.get() && id == generation) {
                // Poll output even when the sender is waiting for decoder acknowledgements.
                peer.socket.soTimeout = 10
                val first = try { peer.input.read() } catch (_: java.net.SocketTimeoutException) {
                    drain(); continue
                }
                if (first < 0) break
                peer.socket.soTimeout = 8000
                val record = DirectCastProtocol.readRecord(peer.input, first)
                val data = ByteBuffer.wrap(record)
                when (data.get().toInt()) {
                    DirectCastProtocol.CONFIG -> {
                        require(data.remaining() in 9..(DirectCastProtocol.MAX_CONFIG + 8))
                        val width = data.int; val height = data.int
                        require(width in 2..1920 && height in 2..1920)
                        val headers = AvcHeaders().apply { add(ByteArray(data.remaining()).also(data::get)) }
                        require(headers.complete) { "Missing AVC parameter sets" }
                        decoder?.let { runCatching { it.stop() }; it.release() }; decoder = null
                        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        decoder = codec
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(headers.sequence()))
                            setByteBuffer("csd-1", ByteBuffer.wrap(headers.picture()))
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            if (Build.VERSION.SDK_INT >= 30 && codec.codecInfo
                                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                            }
                        }
                        codec.configure(format, peer.surface, null, 0); codec.start(); needsKey = true
                    }
                    DirectCastProtocol.FRAME -> {
                        require(data.remaining() >= 10)
                        val pts = data.long; val key = data.get() != 0.toByte()
                        require(pts >= 0)
                        val codec = checkNotNull(decoder) { "Video arrived before configuration" }
                        drain()
                        if (needsKey && !key) { feedback(DirectCastProtocol.ACCEPTED, pts); continue }
                        val index = codec.dequeueInputBuffer(5_000)
                        if (index < 0) {
                            codec.flush() // Discard queued work rather than fall behind the phone.
                            needsKey = true
                            feedback(DirectCastProtocol.ACCEPTED, pts); feedback(DirectCastProtocol.REQUEST_KEY)
                            continue
                        }
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        require(data.remaining() <= buffer.remaining()) { "AVC frame exceeds decoder capacity" }
                        val size = data.remaining(); buffer.put(data)
                        codec.queueInputBuffer(index, 0, size, pts, 0); needsKey = false
                        feedback(DirectCastProtocol.ACCEPTED, pts)
                        drain(10_000)
                    }
                    else -> error("Unsupported live-cast record")
                }
            }
        } catch (e: Exception) {
            if (!peer.closed.get() && !peer.stopping.get() && id == generation) {
                failed = true
                Log.i("MilkDropCast", "Direct receiver disconnected", e)
            }
        } finally {
            if (peer.stopping.get()) runCatching { feedback(DirectCastProtocol.STOPPED) }
            peer.close(); sockets.remove(peer.socket)
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (active.compareAndSet(peer, null)) main.post {
                if (id == generation && active.get() == null) { onStopped(); if (failed) onError() }
            }
        }
    }

    fun stop() {
        generation++
        runCatching { server?.close() }; server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        stopStream()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
    }

    override fun close() { stop(); main.removeCallbacksAndMessages(null) }

    private class Peer(val socket: Socket, val input: DataInputStream, val output: DataOutputStream) : Closeable {
        val closed = AtomicBoolean(false)
        val stopping = AtomicBoolean(false)
        val surfaceReady = CountDownLatch(1)
        @Volatile var surface: Surface? = null
        fun requestStop() {
            if (!stopping.compareAndSet(false, true)) return
            surfaceReady.countDown()
            runCatching { socket.shutdownInput() }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            surfaceReady.countDown()
            runCatching { socket.close() }
        }
    }
}
