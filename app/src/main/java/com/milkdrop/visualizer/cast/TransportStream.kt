package com.milkdrop.visualizer.cast

import java.io.ByteArrayOutputStream

/** Small, video-only MPEG-TS muxer. Every segment starts at an AVC key frame. */
internal class TransportStream {
    private val continuity = IntArray(8192)
    private var output = ByteArrayOutputStream()

    fun begin() {
        output = ByteArrayOutputStream()
        section(0, byteArrayOf(0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0))
        section(4096, byteArrayOf(2, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0,
            0xe1.toByte(), 0, 0xf0.toByte(), 0, 0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0))
    }

    fun video(annexB: ByteArray, timeUs: Long) {
        val pts = (timeUs * 90 / 1000 + 90_000) and 0x1ffffffffL
        val pes = ByteArrayOutputStream(annexB.size + 14)
        pes.write(byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or (((pts ushr 30).toInt() and 7) shl 1)).toByte(), (pts ushr 22).toByte(),
            ((((pts ushr 15).toInt() and 127) shl 1) or 1).toByte(), (pts ushr 7).toByte(),
            (((pts.toInt() and 127) shl 1) or 1).toByte()))
        pes.write(annexB)
        packets(256, pes.toByteArray(), pts)
    }

    fun bytes(): ByteArray = output.toByteArray()
    val byteCount: Int get() = output.size()

    private fun section(pid: Int, body: ByteArray) {
        var crc = -1
        for (value in body) {
            crc = crc xor ((value.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        packets(pid, byteArrayOf(0) + body + byteArrayOf((crc ushr 24).toByte(),
            (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()), null)
    }

    private fun packets(pid: Int, data: ByteArray, pcr: Long?) {
        var offset = 0
        while (offset < data.size) {
            val first = offset == 0
            val clock = if (first) pcr else null
            val count = minOf(data.size - offset, if (clock != null) 176 else 184)
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = ((pid ushr 8) or if (first) 0x40 else 0).toByte()
            packet[2] = pid.toByte()
            val adaptation = 184 - count
            packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or continuity[pid]).toByte()
            continuity[pid] = (continuity[pid] + 1) and 15
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) packet[5] = if (clock != null) 0x10 else 0
                if (clock != null) {
                    packet[6] = (clock ushr 25).toByte(); packet[7] = (clock ushr 17).toByte()
                    packet[8] = (clock ushr 9).toByte(); packet[9] = (clock ushr 1).toByte()
                    packet[10] = (((clock and 1).toInt() shl 7) or 0x7e).toByte(); packet[11] = 0
                }
            }
            data.copyInto(packet, 4 + adaptation, offset, offset + count)
            output.write(packet)
            offset += count
        }
    }
}
