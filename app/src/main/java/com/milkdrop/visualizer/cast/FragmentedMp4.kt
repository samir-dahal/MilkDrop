package com.milkdrop.visualizer.cast

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Video-only ISO BMFF: an AVC initialization segment and one independently timed sample per fragment. */
internal class FragmentedMp4(width: Int, height: Int, sequence: ByteArray, picture: ByteArray) {
    private val sps = units(sequence).first { it[0].toInt() and 31 == 7 }
    private val pps = units(picture).first { it[0].toInt() and 31 == 8 }
    val mime: String
    val initialization: ByteArray

    init {
        require(width in 1..65535 && height in 1..65535 && sps.size >= 4)
        require(sps.size <= 65535 && pps.size <= 65535)
        mime = "video/mp4; codecs=\"avc1." + sps.slice(1..3).joinToString("") { "%02x".format(it.toInt() and 255) } + "\""
        val avcC = box("avcC", data {
            writeByte(1); write(sps, 1, 3); writeByte(255); writeByte(225)
            writeShort(sps.size); write(sps); writeByte(1); writeShort(pps.size); write(pps)
        })
        val avc1 = box("avc1", data {
            write(ByteArray(6)); writeShort(1); write(ByteArray(16)); writeShort(width); writeShort(height)
            writeInt(0x00480000); writeInt(0x00480000); writeInt(0); writeShort(1)
            write(ByteArray(32)); writeShort(24); writeShort(-1); write(avcC)
        })
        val stbl = box("stbl", full("stsd", 0, data { writeInt(1); write(avc1) }),
            full("stts", 0, data { writeInt(0) }), full("stsc", 0, data { writeInt(0) }),
            full("stsz", 0, data { writeInt(0); writeInt(0) }), full("stco", 0, data { writeInt(0) }))
        val dinf = box("dinf", full("dref", 0, data { writeInt(1); write(full("url ", 1)) }))
        val minf = box("minf", full("vmhd", 1, ByteArray(8)), dinf, stbl)
        val mdia = box("mdia", full("mdhd", 0, data {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0); writeShort(0x55c4); writeShort(0)
        }), full("hdlr", 0, data {
            writeInt(0); writeBytes("vide"); write(ByteArray(12)); writeBytes("MilkDrop"); writeByte(0)
        }), minf)
        val trak = box("trak", full("tkhd", 7, data {
            writeInt(0); writeInt(0); writeInt(1); writeInt(0); writeInt(0); write(ByteArray(8))
            writeShort(0); writeShort(0); writeShort(0); writeShort(0); matrix()
            writeInt(width shl 16); writeInt(height shl 16)
        }), mdia)
        val moov = box("moov", full("mvhd", 0, data {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0)
            writeInt(0x10000); writeShort(0x100); write(ByteArray(10)); matrix(); write(ByteArray(24)); writeInt(2)
        }), trak, box("mvex", full("trex", 0, data {
            writeInt(1); writeInt(1); writeInt(0); writeInt(0); writeInt(0x01010000)
        })))
        initialization = box("ftyp", data { writeBytes("isom"); writeInt(0x200); writeBytes("isomiso6avc1mp41") }) + moov
    }

    fun fragment(number: Long, annexB: ByteArray, timeUs: Long, durationUs: Long, key: Boolean): ByteArray {
        require(timeUs >= 0 && durationUs > 0)
        val sample = data {
            for (nal in units(annexB)) {
                if (nal[0].toInt() and 31 !in listOf(7, 8, 9)) { writeInt(nal.size); write(nal) }
            }
        }
        require(sample.isNotEmpty())
        fun moof(offset: Int) = box("moof", full("mfhd", 0, data { writeInt(number.toInt()) }),
            box("traf", full("tfhd", 0x020000, data { writeInt(1) }),
                full("tfdt", 0x01000000, data { writeLong(timeUs * TIMESCALE / 1_000_000) }),
                full("trun", 0x701, data {
                    writeInt(1); writeInt(offset)
                    writeInt((durationUs * TIMESCALE / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                    writeInt(sample.size); writeInt(if (key) 0x02000000 else 0x01010000)
                })))
        val offset = moof(0).size + 8
        return moof(offset) + box("mdat", sample)
    }

    private fun DataOutputStream.matrix() {
        intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(::writeInt)
    }

    private fun data(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().let { buffer ->
        DataOutputStream(buffer).use { it.write() }; buffer.toByteArray()
    }
    private fun box(type: String, vararg payload: ByteArray): ByteArray = data {
        val size = 8L + payload.sumOf { it.size.toLong() }
        require(type.length == 4 && size <= Int.MAX_VALUE)
        writeInt(size.toInt()); writeBytes(type); payload.forEach(::write)
    }
    private fun full(type: String, flags: Int, payload: ByteArray = byteArrayOf()) =
        box(type, data { writeInt(flags); write(payload) })

    private fun units(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < bytes.size) {
            val prefix = if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) when {
                bytes[i + 2] == 1.toByte() -> 3
                i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                else -> 0
            } else 0
            if (prefix > 0) { starts.add(i to prefix); i += prefix } else i++
        }
        require(starts.isNotEmpty()) { "AVC access unit has no Annex B prefix" }
        return starts.mapIndexedNotNull { index, (start, prefix) ->
            val end = starts.getOrNull(index + 1)?.first ?: bytes.size
            if (start + prefix < end) bytes.copyOfRange(start + prefix, end) else null
        }
    }

    private companion object { const val TIMESCALE = 90_000 }
}
