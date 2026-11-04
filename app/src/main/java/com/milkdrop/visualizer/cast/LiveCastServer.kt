package com.milkdrop.visualizer.cast

import java.io.Closeable
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** One persistent HTTP response carries complete fMP4 records as soon as each frame is encoded. */
internal class LiveCastServer : Closeable {
    private data class Frame(val number: Long, val timeUs: Long, val key: Boolean, val bytes: ByteArray)
    private val lock = Object()
    private val token = UUID.randomUUID().toString()
    private val listener = ServerSocket(0, 2)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(2)
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "MilkDrop-live-http").apply { isDaemon = true } }
    private val frames = ArrayDeque<Frame>()
    private var muxer: FragmentedMp4? = null
    private var nextNumber = 1L
    private var fps = 60
    private var sendBuffer = 1
    var recordLimit: Int = 0
        private set
    @Volatile private var closed = false
    @Volatile var requestKeyFrame: (() -> Unit)? = null
    val mime: String get() = synchronized(lock) { checkNotNull(muxer).mime }

    init {
        Thread({
            while (!closed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                clients.add(client)
                try { workers.execute { serve(client) } } catch (_: RuntimeException) {
                    clients.remove(client); slots.release(); client.close()
                }
            }
        }, "MilkDrop-live-listen").apply { isDaemon = true; start() }
    }

    fun url(address: String) = "http://$address:${listener.localPort}/$token/frames"

    fun configure(width: Int, height: Int, frameRate: Int, sps: ByteArray, pps: ByteArray) = synchronized(lock) {
        if (muxer == null) {
            muxer = FragmentedMp4(width, height, sps, pps)
            fps = frameRate.coerceAtLeast(1)
            recordLimit = Math.multiplyExact(Math.multiplyExact(width, height), 4)
            sendBuffer = (CastEncodingPolicy.bitRate(CastEncodingPolicy.Mode(width, height, fps)) / fps / 8 * 2).coerceAtLeast(1)
            lock.notifyAll()
        }
    }

    /** Never waits for the network. Slow consumers resume from a complete recent key frame. */
    fun offer(bytes: ByteArray, timeUs: Long, durationUs: Long, key: Boolean): Boolean = synchronized(lock) {
        if (closed) return false
        val mp4 = muxer ?: return false
        if (frames.isEmpty() && !key) return false
        val number = nextNumber++
        frames.addLast(Frame(number, timeUs, key, mp4.fragment(number, bytes, timeUs, durationUs, key)))
        while (frames.size > fps || frames.last().timeUs - frames.first().timeUs > 500_000) frames.removeFirst()
        lock.notifyAll()
        key
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                client.soTimeout = 5000; client.tcpNoDelay = true
                val input = client.getInputStream()
                val request = StringBuilder()
                while (request.length < 4096 && !request.endsWith("\r\n\r\n")) {
                    val value = input.read(); if (value < 0) return; request.append(value.toChar())
                }
                if (!request.endsWith("\r\n\r\n")) return
                val parts = request.lineSequence().first().split(' ')
                if (parts.size != 3) return
                val valid = parts[1] == "/$token/frames" && parts[0] in listOf("GET", "OPTIONS")
                val output = DataOutputStream(client.getOutputStream())
                val cors = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\n" +
                    "Access-Control-Allow-Private-Network: true\r\nCache-Control: no-store\r\n"
                if (!valid || parts[0] == "OPTIONS") {
                    output.writeBytes("HTTP/1.1 ${if (valid) "204 No Content" else "404 Not Found"}\r\n${cors}Content-Length: 0\r\nConnection: close\r\n\r\n")
                    output.flush(); return
                }
                val init = synchronized(lock) {
                    while (!closed && muxer == null) lock.wait(1000)
                    if (closed) return
                    client.sendBufferSize = sendBuffer
                    checkNotNull(muxer).initialization
                }
                output.writeBytes("HTTP/1.1 200 OK\r\n${cors}Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                fun record(bytes: ByteArray) {
                    output.writeBytes("${(bytes.size.toLong() + 4).toString(16)}\r\n")
                    output.writeInt(bytes.size); output.write(bytes); output.writeBytes("\r\n"); output.flush()
                }
                record(init)
                var last = -1L
                while (!closed) {
                    val frame = synchronized(lock) {
                        var next: Frame? = null
                        while (!closed && next == null) {
                            next = if (last < 0 || frames.firstOrNull()?.number?.let { it > last + 1 } == true) {
                                frames.lastOrNull { it.key }
                            } else frames.firstOrNull { it.number > last }
                            if (next == null) { requestKeyFrame?.invoke(); lock.wait(500) }
                        }
                        next
                    } ?: break
                    record(frame.bytes); last = frame.number
                }
            }
        } catch (_: Exception) {
            // Disconnection and cancellation close the response/socket normally.
        } finally { clients.remove(client); slots.release() }
    }

    override fun close() {
        closed = true; requestKeyFrame = null
        runCatching { listener.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        synchronized(lock) { frames.clear(); muxer = null; lock.notifyAll() }
    }
}
