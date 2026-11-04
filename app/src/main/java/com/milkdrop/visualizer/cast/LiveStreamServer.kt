package com.milkdrop.visualizer.cast

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Session-scoped LAN server: opaque URLs, bounded storage, connections and request sizes. */
internal class LiveStreamServer : Closeable {
    private data class Segment(val number: Long, val duration: Double, val data: ByteArray, val discontinuity: Boolean)
    private val token = UUID.randomUUID().toString()
    private val segments = ArrayDeque<Segment>()
    private var sequence = 0L
    private var storedBytes = 0
    private var discontinuitiesRemoved = 0L
    private val socket = ServerSocket(0, 4)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 3, 10, TimeUnit.SECONDS,
        ArrayBlockingQueue(8), { r -> Thread(r, "MilkDrop-cast-http").apply { isDaemon = true } })
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients.add(client)
                try { workers.execute { serve(client) } } catch (_: RuntimeException) {
                    clients.remove(client); client.close()
                }
            }
        }, "MilkDrop-cast-listen").apply { isDaemon = true; start() }
    }

    fun url(address: String) = "http://$address:${socket.localPort}/$token/live.m3u8"

    @Synchronized fun publish(bytes: ByteArray, duration: Double, discontinuity: Boolean = false): Boolean {
        if (closed || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        require(duration > 0 && duration <= CastVideoTiming.TARGET_SEGMENT_SECONDS) { "Video segment exceeds its target duration" }
        segments.addLast(Segment(sequence++, duration, bytes, discontinuity))
        storedBytes += bytes.size
        while (segments.size > 12 || storedBytes > MAX_BYTES) {
            val removed = segments.removeFirst(); storedBytes -= removed.data.size
            if (removed.discontinuity) discontinuitiesRemoved++
        }
        return segments.sumOf { it.duration } >= CastVideoTiming.LIVE_WINDOW_SECONDS
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                client.soTimeout = 5000
                val input = client.getInputStream()
                val request = StringBuilder()
                while (request.length < 4096) {
                    val byte = input.read()
                    if (byte < 0) return
                    request.append(byte.toChar())
                    if (request.endsWith("\r\n\r\n")) break
                }
                if (!request.endsWith("\r\n\r\n")) return
                val parts = request.lineSequence().first().split(' ')
                if (parts.size != 3 || parts[0] !in listOf("GET", "HEAD", "OPTIONS")) return
                val path = parts[1].substringBefore('?')
                val response = synchronized(this) {
                    when {
                        path == "/$token/live.m3u8" && segments.isNotEmpty() -> {
                            // Keep older segments available for in-flight requests, but never
                            // advertise the entire retained cache as a playback backlog.
                            val live = ArrayDeque<Segment>()
                            var duration = 0.0
                            for (segment in segments.reversed()) {
                                live.addFirst(segment)
                                duration += segment.duration
                                if (duration >= CastVideoTiming.LIVE_WINDOW_SECONDS) break
                            }
                            val discontinuities = discontinuitiesRemoved +
                                segments.takeWhile { it.number < live.first().number }.count { it.discontinuity }
                            val list = buildString {
                                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n")
                                append("#EXT-X-TARGETDURATION:${CastVideoTiming.TARGET_SEGMENT_SECONDS}\n#EXT-X-MEDIA-SEQUENCE:${live.first().number}\n")
                                append("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuities\n")
                                for (segment in live) {
                                    if (segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                                    append(String.format(Locale.US, "#EXTINF:%.3f,\n%d.ts\n", segment.duration, segment.number))
                                }
                            }
                            "application/vnd.apple.mpegurl" to list.toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/$token/") && path.endsWith(".ts") -> {
                            val number = path.removePrefix("/$token/").removeSuffix(".ts").toLongOrNull()
                            segments.firstOrNull { it.number == number }?.let { "video/mp2t" to it.data }
                        }
                        else -> null
                    }
                }
                val body = if (parts[0] == "OPTIONS") ByteArray(0) else response?.second ?: ByteArray(0)
                val header = "HTTP/1.1 ${if (response == null) "404 Not Found" else "200 OK"}\r\n" +
                    "Content-Type: ${response?.first ?: "text/plain"}\r\nContent-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Range\r\nAccess-Control-Allow-Private-Network: true\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII))
                    if (parts[0] == "GET") write(body)
                    flush()
                }
            }
        } catch (_: Exception) {
            // Receiver disconnects, reloads and timeouts are ordinary session events.
        } finally { clients.remove(client) }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        synchronized(this) { segments.clear(); storedBytes = 0 }
    }

    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
