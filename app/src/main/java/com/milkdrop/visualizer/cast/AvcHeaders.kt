package com.milkdrop.visualizer.cast

/** Preserve both parameter sets even when a codec reports them in separate buffers. */
internal class AvcHeaders {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    val complete: Boolean get() = sps != null && pps != null
    val bytes: ByteArray get() = (sps ?: byteArrayOf()) + (pps ?: byteArrayOf())

    fun add(bytes: ByteArray) {
        require(bytes.size <= DirectCastProtocol.MAX_CONFIG)
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < bytes.size) {
            val prefix = when {
                bytes[i] != 0.toByte() || bytes[i + 1] != 0.toByte() -> 0
                bytes[i + 2] == 1.toByte() -> 3
                i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) { starts.add(i to prefix); i += prefix } else i++
        }
        starts.forEachIndexed { index, (start, prefix) ->
            val end = starts.getOrNull(index + 1)?.first ?: bytes.size
            if (start + prefix < end) when (bytes[start + prefix].toInt() and 31) {
                7 -> sps = bytes.copyOfRange(start, end)
                8 -> pps = bytes.copyOfRange(start, end)
            }
        }
    }

    fun sequence() = checkNotNull(sps).copyOf()
    fun picture() = checkNotNull(pps).copyOf()
}
