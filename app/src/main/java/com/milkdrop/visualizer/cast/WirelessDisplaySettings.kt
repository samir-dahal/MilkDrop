package com.milkdrop.visualizer.cast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Miracast discovery/pairing belongs to the phone's system, which may not publish routes. */
object WirelessDisplaySettings {
    private fun intents() = listOf(Settings.ACTION_CAST_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
        .map { Intent(it) }

    fun available(context: Context): Boolean = intents().any {
        it.resolveActivity(context.packageManager) != null
    }

    fun open(context: Context): Boolean {
        for (intent in intents()) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // A vendor may expose a settings action but remove its actual Activity.
            } catch (_: SecurityException) {
                // Try the public connection-settings fallback without private OEM intents.
            }
        }
        return false
    }
}
