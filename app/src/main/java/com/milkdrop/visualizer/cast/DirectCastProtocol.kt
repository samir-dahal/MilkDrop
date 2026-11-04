package com.milkdrop.visualizer.cast

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Bounded, versioned AVC records. No screen/audio capture or remote control commands. */
internal object DirectCastProtocol {
    const val SERVICE = "_milkdropcast._tcp."
    const val MAGIC = 0x4d445631 // MDV1
    const val CONFIG = 1
    const val FRAME = 2
    const val PRESENTED = 2
    const val REQUEST_KEY = 3
    const val STOPPED = 4
    const val ACCEPTED = 5
    const val MAX_RECORD = 4 * 1024 * 1024 + 16
    const val MAX_CONFIG = 128 * 1024

    fun readRecord(input: DataInputStream, firstByte: Int? = null): ByteArray {
        val length = if (firstByte == null) input.readInt() else
            (firstByte shl 24) or (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        if (length !in 1..MAX_RECORD) throw IOException("Invalid live-cast record size")
        return ByteArray(length).also(input::readFully)
    }

    fun writeConfig(output: DataOutputStream, width: Int, height: Int, headers: ByteArray) {
        require(width in 2..1920 && height in 2..1920 && headers.size <= MAX_CONFIG)
        output.writeInt(9 + headers.size); output.writeByte(CONFIG)
        output.writeInt(width); output.writeInt(height); output.write(headers)
    }

    fun writeFrame(output: DataOutputStream, bytes: ByteArray, timeUs: Long, key: Boolean) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD - 10)
        output.writeInt(10 + bytes.size); output.writeByte(FRAME)
        output.writeLong(timeUs); output.writeBoolean(key); output.write(bytes)
    }
}

/** One pending frame. A dropped reference frame requires a new IDR before continuing. */
internal class LiveFrameQueue {
    data class Frame(val bytes: ByteArray, val timeUs: Long, val key: Boolean)
    private var pending: Frame? = null
    private var needsKey = true

    /** false asks the encoder for a fresh key frame; never enqueue a broken prediction chain. */
    @Synchronized fun offer(frame: Frame): Boolean {
        if (needsKey && !frame.key) return false
        if (pending != null && !frame.key) {
            pending = null; needsKey = true
            return false
        }
        pending = frame; needsKey = false
        return true
    }

    @Synchronized fun take(): Frame? = pending.also { pending = null }
    @Synchronized fun reset() { pending = null; needsKey = true }
}
