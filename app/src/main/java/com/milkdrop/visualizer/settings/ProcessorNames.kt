package com.milkdrop.visualizer.settings

/** Format the names reported by the device without inferring a marketing name. */
internal object ProcessorNames {
    fun displayName(manufacturer: String, model: String, hardware: String, unavailable: String): String {
        fun String.reported() = trim().takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        val chip = model.reported()
        val vendor = manufacturer.reported()
        return when {
            chip != null && vendor != null && !chip.contains(vendor, ignoreCase = true) -> "$vendor $chip"
            chip != null -> chip
            else -> hardware.reported() ?: vendor ?: unavailable
        }
    }
}
