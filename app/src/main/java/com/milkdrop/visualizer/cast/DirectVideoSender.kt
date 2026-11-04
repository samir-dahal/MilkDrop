package com.milkdrop.visualizer.cast

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Small socket buffers and one pending frame prevent an expanding video backlog. */
internal class DirectVideoSender(
    private val onPresented: () -> Unit,
    private val onStopped: () -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private data class Configuration(val width: Int, val height: Int, val headers: ByteArray)
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val frames = LiveFrameQueue()
    private val signal = Semaphore(0)
    // Acknowledge decoder input separately from presentation: older codecs may need several
    // input frames before producing output. Network buffers still cannot hide a growing queue.
    private val credit = Semaphore(3)
    @Volatile private var configuration: Configuration? = null
    @Volatile private var lastSentAt = 0L
    @Volatile var requestKeyFrame: (() -> Unit)? = null

    /** Session worker calls this; exposing the socket early makes cancellation interrupt connect. */
    fun connect(device: DirectCastDevice, sender: String) {
        check(!closed.get())
        socket.tcpNoDelay = true
        socket.sendBufferSize = 64 * 1024
        socket.receiveBufferSize = 16 * 1024
        socket.soTimeout = 8000
        socket.connect(InetSocketAddress(device.host, device.port), 5000)
        val output = DataOutputStream(socket.getOutputStream())
        output.writeInt(DirectCastProtocol.MAGIC); output.writeUTF(device.token)
        output.writeUTF(sender.take(80)); output.flush()
        val input = DataInputStream(socket.getInputStream())
        check(input.readUnsignedByte() == 1) { "TV receiver is busy or refused the connection" }
        check(!closed.get())
        Thread({ write(output) }, "MilkDrop-live-send").apply { isDaemon = true; start() }
        Thread({ feedback(input) }, "MilkDrop-live-feedback").apply { isDaemon = true; start() }
    }

    fun configure(width: Int, height: Int, headers: ByteArray) {
        val old = configuration
        if (old != null && old.width == width && old.height == height && old.headers.contentEquals(headers)) return
        configuration = Configuration(width, height, headers.copyOf())
        frames.reset(); requestKeyFrame?.invoke()
    }

    fun offer(bytes: ByteArray, timeUs: Long, key: Boolean) {
        if (closed.get()) return
        if (!frames.offer(LiveFrameQueue.Frame(bytes, timeUs, key))) requestKeyFrame?.invoke()
        if (signal.availablePermits() == 0) signal.release()
    }

    private fun write(output: DataOutputStream) {
        var sent: Configuration? = null
        try {
            while (!closed.get()) {
                signal.acquire()
                credit.acquire()
                if (closed.get()) break
                val frame = frames.take()
                val config = configuration
                if (frame == null || config == null) { credit.release(); continue }
                if (sent !== config) {
                    if (!frame.key) { credit.release(); requestKeyFrame?.invoke(); continue }
                    DirectCastProtocol.writeConfig(output, config.width, config.height, config.headers)
                    sent = config
                }
                DirectCastProtocol.writeFrame(output, frame.bytes, frame.timeUs, frame.key)
                output.flush()
                lastSentAt = System.nanoTime()
            }
        } catch (e: Exception) { error(e) }
    }

    private fun feedback(input: DataInputStream) {
        var presented = false
        var lastPresentedAt = System.nanoTime()
        try {
            while (!closed.get()) when (input.readUnsignedByte()) {
                DirectCastProtocol.PRESENTED -> {
                    input.readLong()
                    lastPresentedAt = System.nanoTime()
                    if (!presented) { presented = true; onPresented() }
                }
                DirectCastProtocol.ACCEPTED -> {
                    input.readLong()
                    if (credit.availablePermits() < 3) credit.release()
                    val now = System.nanoTime()
                    if (presented && now - lastPresentedAt > 500_000_000 && now - lastSentAt < 1_000_000_000) {
                        requestKeyFrame?.invoke()
                        check(now - lastPresentedAt < 8_000_000_000L) { "TV decoder stopped displaying frames" }
                    }
                }
                DirectCastProtocol.REQUEST_KEY -> { frames.reset(); requestKeyFrame?.invoke() }
                DirectCastProtocol.STOPPED -> { close(); onStopped(); return }
                else -> throw java.io.IOException("Invalid TV feedback")
            }
        } catch (e: java.io.EOFException) { if (!closed.get()) { close(); onStopped() } }
        catch (e: java.net.SocketException) {
            if (presented && !closed.get()) { close(); onStopped() } else error(e)
        }
        catch (e: Exception) { error(e) }
    }

    private fun error(error: Exception) {
        if (!closed.get() && failed.compareAndSet(false, true)) { close(); onError(error) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestKeyFrame = null
        runCatching { socket.close() }
        frames.reset(); signal.release(); credit.release()
    }
}
