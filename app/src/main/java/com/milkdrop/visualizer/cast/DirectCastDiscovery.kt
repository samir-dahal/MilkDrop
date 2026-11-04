package com.milkdrop.visualizer.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.util.UUID

internal data class DirectCastDevice(val id: String, val name: String, val host: InetAddress,
    val port: Int, val token: String)

internal fun directCastIdentity(context: Context): String {
    val prefs = context.getSharedPreferences("cast_identity", Context.MODE_PRIVATE)
    return prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("id", it).apply()
    }
}

/** Legacy NSD is retained for Android 9. Resolve sequentially: old NSD allows one request. */
@Suppress("DEPRECATION")
internal class DirectCastDiscovery(context: Context, private val changed: (List<DirectCastDevice>) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val ownId = directCastIdentity(context)
    private val main = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, DirectCastDevice>()
    private val present = mutableSetOf<String>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var generation = 0

    fun start() {
        if (listener != null) return
        val id = ++generation
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                main.post { if (id == generation) stop() }
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (id != generation || present.size >= 64 || !present.add(info.serviceName)) return@post
                pending.addLast(info); resolveNext()
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                if (id != generation) return@post
                present.remove(info.serviceName); devices.remove(info.serviceName)
                pending.removeAll { it.serviceName == info.serviceName }; report()
            } }
        }
        listener = callback
        try { nsd.discoverServices(DirectCastProtocol.SERVICE, NsdManager.PROTOCOL_DNS_SD, callback) }
        catch (_: RuntimeException) { stop() }
    }

    private fun resolveNext() {
        if (listener == null || resolving || pending.isEmpty()) return
        val info = pending.removeFirst(); val id = generation
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { main.post {
                resolving = false
                if (id == generation) present.remove(service.serviceName)
                resolveNext()
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if (id == generation && service.serviceName in present) {
                    fun attribute(key: String) = service.attributes[key]?.toString(Charsets.UTF_8)
                    val identity = attribute("id"); val token = attribute("token"); val host = service.host
                    if (attribute("version") == "1" && identity != null && identity != ownId &&
                        token != null && token.length <= 64 && host != null && service.port in 1..65535) {
                        devices[service.serviceName] = DirectCastDevice(identity,
                            attribute("name")?.take(80) ?: service.serviceName, host, service.port, token)
                        report()
                    }
                }
                resolveNext()
            } }
        }
        try { nsd.resolveService(info, callback) }
        catch (_: RuntimeException) { resolving = false; present.remove(info.serviceName); resolveNext() }
    }

    private fun report() = changed(devices.values.distinctBy { it.id }.sortedBy { it.name })

    fun stop() {
        generation++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        pending.clear(); present.clear(); devices.clear(); report()
    }
}
