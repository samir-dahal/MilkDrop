package com.milkdrop.visualizer.cast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.Handler
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Reads the existing Wi-Fi Direct group; never scans for, pairs, or connects to devices. */
internal class ConnectedDisplayPeer(private val context: Context) : Closeable {
    private val manager = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
        ContextCompat.getSystemService(context, WifiP2pManager::class.java) else null
    private var channel: WifiP2pManager.Channel? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    fun missingPermissions(): Array<String> {
        if (manager == null) return emptyArray()
        val required = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        // Android 12 requires coarse and fine to be requested together, including an upgrade
        // from approximate access. Return the whole pair when either permission is missing.
        return if (required.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
            required else emptyArray()
    }

    @Suppress("MissingPermission") // Checked immediately before the asynchronous public API call.
    fun query(onName: (String?) -> Unit) {
        close()
        val service = manager ?: return onName(null)
        if (missingPermissions().isNotEmpty()) return onName(null)
        val id = generation
        try {
            // Public Channel.close() starts at API 27. On older Android share one
            // application-owned channel across queries and Activity recreations.
            val connection = if (Build.VERSION.SDK_INT >= 27) {
                service.initialize(context.applicationContext, Looper.getMainLooper(), null)
            } else LegacyChannel.get(service, context.applicationContext)
            if (connection == null) return onName(null)
            channel = connection
            timeout = Runnable { if (id == generation) { close(); onName(null) } }
                .also { main.postDelayed(it, 2_000) }
            service.requestGroupInfo(connection) { group ->
                if (id != generation) return@requestGroupInfo
                val name = group?.let {
                    // A group owner is local when isGroupOwner is true. Never display its name.
                    if (!it.isGroupOwner) it.owner?.deviceName
                    else it.clientList.singleOrNull()?.deviceName
                }?.trim()?.takeIf { it.isNotEmpty() }
                close()
                onName(name)
            }
        } catch (_: RuntimeException) {
            // OEM restrictions, disabled Location on older Android, and disconnects are normal.
            close()
            onName(null)
        }
    }

    override fun close() {
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching { channel?.close() }
        }
        channel = null
    }

    private object LegacyChannel {
        private var shared: WifiP2pManager.Channel? = null

        // Queries and disconnect callbacks both run on the main looper. Retain no Activity.
        fun get(manager: WifiP2pManager, context: Context): WifiP2pManager.Channel? =
            shared ?: manager.initialize(context, Looper.getMainLooper()) { shared = null }
                .also { shared = it }
    }
}
