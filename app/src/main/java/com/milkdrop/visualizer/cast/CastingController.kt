package com.milkdrop.visualizer.cast

import androidx.core.content.ContextCompat
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.render.MilkDropSurfaceView
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.Executors
import org.json.JSONObject

/** One foreground-only cast session. All session/UI mutations run on the main thread. */
class CastingController(
    private val context: Context,
    private val renderer: MilkDropSurfaceView,
    private val onState: (State) -> Unit,
    private val onError: () -> Unit,
    private val onNamePermissionNeeded: (Array<String>) -> Unit = {},
) : Closeable {
    data class State(val available: Boolean, val device: String? = null,
        val connecting: Boolean = false, val casting: Boolean = false,
        val disconnecting: Boolean = false, val wireless: Boolean = false, val buffered: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val router = MediaRouter.getInstance(context)
    private val displays = requireNotNull(ContextCompat.getSystemService(context, DisplayManager::class.java))
    private val systemRoutes = SystemDisplayRoutes(context)
    private val connectedPeer = ConnectedDisplayPeer(context)
    private var peerName: Pair<Int, String>? = null
    private var peerQueryActive = false
    private var peerQueryAttempts = 0
    private var namePermissionRequested = false
    private val retryPeerName = Runnable { resolvePeerName() }
    private data class PendingDisconnect(val displayId: Int, val name: String, var prompted: Boolean = false)
    private var pendingDisconnect: PendingDisconnect? = null
    private val checkDisconnect = Runnable { verifyDisconnect(prompt = true) }
    private val suppressedDisplays = mutableSetOf<Int>()
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastReceiverConfig.applicationId))
        .build()
    private var castContext: CastContext? = null
    private var started = false
    private var closed = false
    private var generation = 0
    private var device: String? = null
    private var connecting = false
    private var casting = false
    private var output: VisualOutput? = null
    private var presentation: ReceiverPresentation? = null
    private var encoder: VisualStreamEncoder? = null
    private var server: LiveStreamServer? = null
    private var liveServer: LiveCastServer? = null
    private var customProbeTimeout: Runnable? = null
    private var streamStarting = false
    private var session: CastSession? = null
    private var mediaClient: RemoteMediaClient? = null
    private var lastLiveSeek = 0L
    private val checkLivePosition = object : Runnable {
        override fun run() {
            val client = mediaClient ?: return
            val status = client.mediaStatus
            val now = SystemClock.elapsedRealtime()
            // Leave the player's normal safety buffer alone; only recover excessive drift.
            if (client.playerState == MediaStatus.PLAYER_STATE_PLAYING && status?.liveSeekableRange != null &&
                client.approximateLiveSeekableRangeEnd - client.approximateStreamPosition > 8_000 &&
                now - lastLiveSeek >= 30_000) {
                lastLiveSeek = now
                client.seek(MediaSeekOptions.Builder().setIsSeekToInfinite(true).build())
            }
            main.postDelayed(this, 2_000)
        }
    }
    private var chooser: AlertDialog? = null
    private var chooserAdapter: ArrayAdapter<String>? = null
    private sealed class Choice(val name: String) {
        class Direct(val device: DirectCastDevice, name: String) : Choice(name)
        class Route(val route: MediaRouter.RouteInfo) : Choice(route.name)
        class Connected(val display: Display, name: String) : Choice(name)
        class WirelessSettings(name: String) : Choice(name)
    }
    private var choices: List<Choice> = emptyList()
    private var directDevices: List<DirectCastDevice> = emptyList()
    private val directDiscovery = DirectCastDiscovery(context) { devices -> directDevices = devices; refresh() }
    private var directSender: DirectVideoSender? = null
    private val timeout = Runnable { if (connecting) fail(generation, IllegalStateException("Casting timed out")) }

    private val displayEvents = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { refresh(); adoptConnectedDisplay() }
        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId && displays.getDisplay(displayId)?.isValid != true) {
                stop(selectLocal = false, suppressDisplay = false)
            }
            refresh(); adoptConnectedDisplay()
        }
        override fun onDisplayRemoved(displayId: Int) {
            suppressedDisplays.remove(displayId)
            if (pendingDisconnect?.displayId == displayId) { pendingDisconnect = null; main.removeCallbacks(checkDisconnect) }
            if (presentation?.display?.displayId == displayId) stop(selectLocal = false, suppressDisplay = false)
            refresh()
        }
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) {
            if (directSender == null && device != null && route.isSelected) stop() else refresh()
        }
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) {
            if (directSender == null && device != null && route.isSelected && !route.isEnabled) stop() else refresh()
        }
        override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo) {
            if (!started || pendingDisconnect != null || directSender != null || route.isDefault || route.isBluetooth) return
            if (device == null) begin(route.name)
            if (CastDevice.getFromBundle(route.extras) == null) showPresentation(route)
        }
        override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo) {
            if (!route.isDefault && device != null && directSender == null) stop(selectLocal = false)
        }
        override fun onRoutePresentationDisplayChanged(r: MediaRouter, route: MediaRouter.RouteInfo) {
            if (directSender == null && route.isSelected && device != null && CastDevice.getFromBundle(route.extras) == null) {
                val current = presentation?.display
                if (route.presentationDisplay != null) showPresentation(route)
                else if (current != null && displays.getDisplay(current.displayId)?.isValid != true) {
                    stop(selectLocal = false, suppressDisplay = false)
                }
            }
            refresh()
        }
    }

    private val mediaEvents = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = mediaClient ?: return
            when (client.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> {
                    connecting = false; casting = true; main.removeCallbacks(timeout); refresh()
                }
                MediaStatus.PLAYER_STATE_IDLE -> if (casting || client.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    if (client.idleReason == MediaStatus.IDLE_REASON_ERROR) fail(generation, IllegalStateException("Receiver playback failed"))
                    else stop()
                }
            }
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = startGoogleStream(s)
        override fun onSessionResumed(s: CastSession, suspended: Boolean) = startGoogleStream(s)
        override fun onSessionEnded(s: CastSession, error: Int) { if (session === s) stop(selectLocal = false) }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            if (directSender == null) fail(generation, IllegalStateException("Cast connection failed: $error"))
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) {
            if (directSender == null) fail(generation, IllegalStateException("Cast resume failed: $error"))
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) { if (session === s) stop() }
        override fun onSessionStarting(s: CastSession) = Unit
        override fun onSessionEnding(s: CastSession) = Unit
        override fun onSessionResuming(s: CastSession, id: String) = Unit
    }

    init {
        // Play Services is optional: initialization failure must not affect local/Miracast rendering.
        try {
            CastContext.getSharedInstance(context, worker).addOnSuccessListener { value ->
                if (!closed) {
                    castContext = value
                    if (started) value.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
                    refresh()
                }
            }.addOnFailureListener { Log.i(TAG, "Google Cast unavailable on this device", it) }
        } catch (e: Exception) { Log.i(TAG, "Google Cast unavailable on this device", e) }
    }

    fun start() {
        if (closed || started) return
        started = true
        displays.registerDisplayListener(displayEvents, main)
        directDiscovery.start()
        discover(active = false)
        castContext?.sessionManager?.addSessionManagerListener(sessions, CastSession::class.java)
        refresh()
        if (pendingDisconnect != null) verifyDisconnect(prompt = false) else adoptConnectedDisplay()
    }

    fun pause() {
        if (!started) return
        // Pairing/permissions may take the phone into system UI. Leave that system-owned
        // wireless link intact so its presentation display can be adopted on return.
        stop(selectLocal = false, suppressDisplay = false)
        started = false
        main.removeCallbacks(checkDisconnect)
        displays.unregisterDisplayListener(displayEvents)
        directDiscovery.stop()
        chooser?.dismiss(); chooser = null
        router.removeCallback(routes)
        castContext?.sessionManager?.removeSessionManagerListener(sessions, CastSession::class.java)
    }

    fun chooseOrStop() {
        if (device != null || pendingDisconnect != null) { requestStop(); return }
        refresh()
        if (choices.isEmpty()) return
        chooserAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, choices.map { it.name })
        chooser = AlertDialog.Builder(context).setTitle(R.string.cast_choose_device)
            .setAdapter(chooserAdapter) { _, index ->
                when (val choice = choices.getOrNull(index)) {
                    is Choice.Direct -> startDirectStream(choice.device)
                    is Choice.Route -> if (choice.route.isEnabled) {
                        begin(choice.name)
                        router.selectRoute(choice.route)
                    }
                    is Choice.Connected -> if (choice.display.isValid) {
                        suppressedDisplays.remove(choice.display.displayId)
                        begin(choice.name); showPresentation(choice.display)
                    }
                    is Choice.WirelessSettings -> if (!openWirelessDisplaySettings()) {
                        Toast.makeText(context, R.string.cast_wireless_unavailable, Toast.LENGTH_LONG).show()
                    }
                    null -> Unit
                }
            }.setNegativeButton(android.R.string.cancel, null).create().also { dialog ->
                dialog.setOnDismissListener {
                    chooser = null; chooserAdapter = null
                    if (started) discover(active = false)
                }
                dialog.show()
            }
        discover(active = true)
    }

    fun openWirelessDisplaySettings(): Boolean {
        if (presentation != null || pendingDisconnect != null) { requestStop(); return true }
        val opened = WirelessDisplaySettings.open(context)
        if (opened) suppressedDisplays.clear()
        return opened
    }

    private fun discover(active: Boolean) {
        router.removeCallback(routes)
        if (started) router.addCallback(selector, routes, if (active)
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    }

    private fun begin(name: String) {
        device = name; connecting = true; casting = false
        main.removeCallbacks(timeout); main.postDelayed(timeout, 25_000)
        refresh()
    }

    private fun compatible(route: MediaRouter.RouteInfo): Boolean {
        if (!route.isEnabled || route.isDefault || route.isBluetooth) return false
        val cast = CastDevice.getFromBundle(route.extras)
        return if (cast != null) castContext != null && cast.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)
        else route.supportsControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO) || route.presentationDisplay != null
    }

    private fun refresh() {
        if (closed) return
        presentation?.display?.let { device = displayName(it) }
        val routes = router.routes.filter(::compatible)
        val routedDisplays = routes.mapNotNull { it.presentationDisplay?.displayId }.toSet()
        val receivers = directDevices.map { Choice.Direct(it, context.getString(R.string.cast_live_device, it.name)) } +
            routes.map { Choice.Route(it) } + connectedDisplays().filter { it.displayId !in routedDisplays }.map { Choice.Connected(it, displayName(it)) }
        choices = receivers + if (WirelessDisplaySettings.available(context))
            listOf(Choice.WirelessSettings(context.getString(R.string.cast_wireless_settings))) else emptyList()
        chooserAdapter?.apply { clear(); addAll(choices.map { it.name }); notifyDataSetChanged() }
        // A settings shortcut isn't a discovered receiver: keep the floating button honest.
        val ending = pendingDisconnect
        onState(State(started && receivers.isNotEmpty(), ending?.name ?: device,
            connecting || ending?.let { !it.prompted } == true, casting || ending != null, ending != null,
            presentation != null || ending != null, session != null && !CastReceiverConfig.custom))
        resolvePeerName()
    }

    /** Retry after an optional Nearby devices / Location permission result. */
    fun refreshReceiverName() {
        peerQueryAttempts = 0
        refresh()
    }

    private fun resolvePeerName() {
        val display = presentation?.display ?: return
        if (!started || pendingDisconnect != null || peerQueryActive || peerQueryAttempts >= 3 ||
            displayName(display) != context.getString(R.string.cast_wireless_device)) return
        val missing = connectedPeer.missingPermissions()
        if (missing.isNotEmpty()) {
            if (!namePermissionRequested) {
                namePermissionRequested = true
                main.post {
                    if (started && presentation != null) onNamePermissionNeeded(missing)
                    else namePermissionRequested = false
                }
            }
            return
        }
        peerQueryActive = true
        peerQueryAttempts++
        val id = generation
        connectedPeer.query { name ->
            if (id != generation || presentation?.display?.displayId != display.displayId) return@query
            peerQueryActive = false
            if (name != null) {
                peerName = display.displayId to name
                refresh()
            } else if (peerQueryAttempts < 3) main.postDelayed(retryPeerName, 2_000)
        }
    }

    private fun displayName(display: Display): String {
        val routeNames = router.routes.filter { !it.isDefault && !it.isBluetooth &&
            it.presentationDisplay?.displayId == display.displayId }.map { it.name }
        val localNames = systemRoutes.localNames() + router.routes.filter { it.isDefault }.map { it.name } +
            listOf(displays.getDisplay(Display.DEFAULT_DISPLAY)?.name)
        return ReceiverNames.choose(routeNames + systemRoutes.names(display) +
            listOf(peerName?.takeIf { it.first == display.displayId }?.second, display.name, device),
            context.getString(R.string.cast_wireless_device), localNames)
    }

    private fun connectedDisplays() = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .filter { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }

    private fun adoptConnectedDisplay() {
        if (!started || closed || device != null || pendingDisconnect != null) return
        val connected = connectedDisplays()
        suppressedDisplays.retainAll(connected.map { it.displayId }.toSet())
        val display = connected.firstOrNull { it.displayId !in suppressedDisplays } ?: return
        begin(displayName(display)); showPresentation(display)
    }

    /** Keep reporting the connection until Android confirms the display was removed. */
    private fun requestStop() {
        if (pendingDisconnect != null) { verifyDisconnect(prompt = true, force = true); return }
        val display = presentation?.display
        if (display == null) { stop(); return }
        pendingDisconnect = PendingDisconnect(display.displayId, device ?: displayName(display))
        stop()
        systemRoutes.disconnect(display)
        main.postDelayed(checkDisconnect, 1500)
    }

    private fun verifyDisconnect(prompt: Boolean, force: Boolean = false) {
        val ending = pendingDisconnect ?: return
        if (displays.getDisplay(ending.displayId)?.isValid != true) {
            pendingDisconnect = null; refresh(); return
        }
        if (started && prompt && (!ending.prompted || force)) {
            ending.prompted = true
            Toast.makeText(context, R.string.cast_finish_disconnect, Toast.LENGTH_LONG).show()
            WirelessDisplaySettings.open(context)
        }
        refresh()
    }

    private fun showPresentation(route: MediaRouter.RouteInfo) {
        val display = route.presentationDisplay ?: return
        showPresentation(display)
    }

    private fun showPresentation(display: Display) {
        if (presentation?.display?.displayId == display.displayId) return
        if (presentation != null) { stop(); return }
        val id = generation
        var outputSize: Pair<Int, Int>? = null
        try {
            presentation = ReceiverPresentation(context, display, { holder, w, h ->
                if (id != generation || !started) return@ReceiverPresentation
                if (output != null) {
                    if (outputSize != (w to h)) {
                        outputSize = w to h
                        output?.resize(w, h); renderer.setCastSize(w to h)
                    }
                    return@ReceiverPresentation
                }
                outputSize = w to h
                val size = w to h
                val destination = VisualOutput(holder.surface, w, h, false, { error ->
                    main.post { fail(id, error) }
                }, onStarted = {
                    main.post { if (id == generation && device != null) {
                        connecting = false; casting = true; main.removeCallbacks(timeout); refresh()
                    } }
                })
                installOutput(destination, size)
            }, { main.post { if (id == generation && device != null) stop() } }).also { it.show() }
        } catch (e: WindowManager.InvalidDisplayException) { fail(id, e) }
    }

    private fun startGoogleStream(s: CastSession) {
        if (!started || closed || directSender != null || streamStarting || session === s) return
        if (device == null) { castContext?.sessionManager?.endCurrentSession(true); return }
        s.castDevice?.friendlyName?.takeIf { it.isNotBlank() }?.let { device = it }
        session = s; streamStarting = true
        val id = generation
        if (CastReceiverConfig.custom) {
            startCustomGoogleStream(s, id)
            return
        }
        // Standard Cast video receivers support up to 60 fps; the codec can negotiate lower.
        val frameRate = encodingFrameRate().coerceAtMost(60)
        worker.execute {
            var pendingServer: LiveStreamServer? = null
            var pendingEncoder: VisualStreamEncoder? = null
            try {
                val address = lanAddress()
                pendingServer = LiveStreamServer()
                val stream = pendingServer
                pendingEncoder = VisualStreamEncoder(stream, onReady = {
                    main.post { if (id == generation && session === s && started) loadStream(s, stream.url(address), id) }
                }, onError = { error -> main.post { fail(id, error) } },
                    requestedFramesPerSecond = frameRate, fullHdSupported = true)
                val video = pendingEncoder
                main.post {
                    if (id != generation || !started || closed) {
                        cleanup(null, video, stream)
                    } else {
                        encoder = video; server = stream; streamStarting = false
                        installOutput(VisualOutput(video.surface, video.width, video.height, true,
                            { error -> main.post { fail(id, error) } }).apply { maxFramesPerSecond = video.framesPerSecond },
                            video.width to video.height)
                    }
                }
            } catch (e: Exception) {
                pendingEncoder?.close(); pendingServer?.close()
                main.post { fail(id, e) }
            }
        }
    }

    /** A registered web receiver consumes fMP4 immediately instead of waiting for an HLS playlist. */
    private fun startCustomGoogleStream(s: CastSession, id: Int) {
        var encodingStarted = false
        fun negotiated(fullHd: Boolean) {
            if (encodingStarted || id != generation || session !== s || !started || closed) return
            encodingStarted = true
            customProbeTimeout?.let(main::removeCallbacks); customProbeTimeout = null
            encodeCustomGoogleStream(s, id, fullHd)
        }
        try {
            s.setMessageReceivedCallbacks(CastReceiverConfig.NAMESPACE) { _, _, message ->
                main.post {
                    if (id != generation || session !== s) return@post
                    val status = runCatching { JSONObject(message) }.getOrNull() ?: return@post
                    when (status.optString("type")) {
                        "CAPABILITIES" -> negotiated(status.optBoolean("fullHd60", false))
                        "PLAYING" -> {
                            connecting = false; casting = true; main.removeCallbacks(timeout); refresh()
                        }
                        "ERROR" -> fail(id, IllegalStateException("Live receiver: ${status.optString("message")}"))
                        "STATS" -> Log.d(TAG, "Live receiver: $status")
                        "STOPPED" -> stop()
                    }
                }
            }
            // Older hosted receivers do not answer this probe; keep their 720p fallback.
            customProbeTimeout = Runnable { negotiated(false) }.also { main.postDelayed(it, 2000) }
            s.sendMessage(CastReceiverConfig.NAMESPACE, "{\"type\":\"HELLO\"}").setResultCallback { result ->
                if (!result.isSuccess) negotiated(false)
            }
        } catch (e: Exception) { fail(id, e); return }
    }

    private fun encodeCustomGoogleStream(s: CastSession, id: Int, fullHd: Boolean) {
        val frameRate = encodingFrameRate().coerceAtMost(60)
        worker.execute {
            var stream: LiveCastServer? = null
            var video: VisualStreamEncoder? = null
            try {
                val address = lanAddress()
                val live = LiveCastServer().also { stream = it }
                video = VisualStreamEncoder(onReady = {
                    main.post {
                        if (id == generation && session === s && started) {
                            val start = JSONObject().put("type", "START").put("version", 1)
                                .put("url", live.url(address)).put("mime", live.mime)
                                .put("maxRecordBytes", live.recordLimit)
                            try {
                                s.sendMessage(CastReceiverConfig.NAMESPACE, start.toString()).setResultCallback { result ->
                                    if (!result.isSuccess) fail(id, IllegalStateException("Custom receiver refused live stream"))
                                }
                            } catch (error: Exception) { fail(id, error) }
                        }
                    }
                }, onError = { error -> main.post { fail(id, error) } },
                    requestedFramesPerSecond = frameRate, live = live, fullHdSupported = fullHd)
                val pending = video
                main.post {
                    if (id != generation || !started || closed) cleanup(null, pending, null, live)
                    else {
                        encoder = pending; liveServer = live; streamStarting = false
                        installOutput(VisualOutput(pending.surface, pending.width, pending.height, true,
                            { error -> main.post { fail(id, error) } }).apply { maxFramesPerSecond = pending.framesPerSecond },
                            pending.width to pending.height)
                    }
                }
            } catch (e: Exception) {
                video?.close(); stream?.close()
                main.post { fail(id, e) }
            }
        }
    }

    private fun startDirectStream(target: DirectCastDevice) {
        begin(target.name)
        val id = generation
        val frameRate = encodingFrameRate()
        val sender = DirectVideoSender(onPresented = {
            main.post { if (id == generation && started && device != null) {
                connecting = false; casting = true; main.removeCallbacks(timeout); refresh()
            } }
        }, onStopped = { main.post { if (id == generation) stop() } },
            onError = { error -> main.post { fail(id, error) } })
        directSender = sender // Stop can interrupt a pending socket connection immediately.
        worker.execute {
            var video: VisualStreamEncoder? = null
            try {
                sender.connect(target, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                video = VisualStreamEncoder(onReady = {},
                    onError = { error -> main.post { fail(id, error) } }, direct = sender,
                    requestedFramesPerSecond = frameRate, fullHdSupported = true)
                val pending = video
                main.post {
                    if (id != generation || !started || closed) {
                        sender.close(); cleanup(null, pending, null)
                    } else {
                        encoder = pending
                        installOutput(VisualOutput(pending.surface, pending.width, pending.height, true,
                            { error -> main.post { fail(id, error) } }).apply { maxFramesPerSecond = pending.framesPerSecond },
                            pending.width to pending.height)
                    }
                }
            } catch (e: Exception) {
                sender.close(); video?.close()
                main.post { fail(id, e) }
            }
        }
    }

    private fun loadStream(s: CastSession, url: String, id: Int) {
        val client = s.remoteMediaClient ?: return fail(id, IllegalStateException("No receiver player"))
        mediaClient = client; client.registerCallback(mediaEvents)
        lastLiveSeek = SystemClock.elapsedRealtime() - 30_000
        main.removeCallbacks(checkLivePosition)
        main.postDelayed(checkLivePosition, 2_000)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, context.getString(R.string.app_name))
        }
        val info = MediaInfo.Builder(url).setContentType("application/x-mpegURL")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE).setMetadata(metadata).build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { result ->
                if (!result.status.isSuccess) fail(id, IllegalStateException("Receiver rejected stream: ${result.status.statusCode}"))
            }
    }

    private fun installOutput(destination: VisualOutput, size: Pair<Int, Int>) {
        val old = output
        output = destination; renderer.milkDropRenderer.visualOutput = destination
        renderer.setCastSize(size, destination.maxFramesPerSecond)
        if (old != null) cleanup(old, null, null)
    }

    private fun encodingFrameRate(): Int {
        val display = renderer.display
        val refresh = if (Build.VERSION.SDK_INT >= 23) {
            display?.supportedModes?.maxOfOrNull { it.refreshRate }
        } else display?.refreshRate
        return maxOf(renderer.targetFps, refresh?.let { kotlin.math.ceil(it.toDouble()).toInt() } ?: 60)
    }

    fun stop(selectLocal: Boolean = true, suppressDisplay: Boolean = true) {
        generation++
        customProbeTimeout?.let(main::removeCallbacks); customProbeTimeout = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(checkLivePosition)
        main.removeCallbacks(retryPeerName)
        connectedPeer.close()
        peerName = null; peerQueryActive = false; peerQueryAttempts = 0
        val oldOutput = output; val oldEncoder = encoder; val oldServer = server
        val oldLive = liveServer
        renderer.milkDropRenderer.visualOutput = null; renderer.setCastSize(null)
        output = null; encoder = null; server = null; liveServer = null; streamStarting = false
        directSender?.close(); directSender = null
        mediaClient?.unregisterCallback(mediaEvents); mediaClient = null
        val hadSession = session != null
        session?.let { s ->
            if (CastReceiverConfig.custom) {
                runCatching { s.sendMessage(CastReceiverConfig.NAMESPACE, "{\"type\":\"STOP\"}") }
                runCatching { s.removeMessageReceivedCallbacks(CastReceiverConfig.NAMESPACE) }
            }
        }
        session = null
        val oldPresentation = presentation; presentation = null
        if (suppressDisplay && oldPresentation != null) suppressedDisplays.add(oldPresentation.display.displayId)
        device = null; connecting = false; casting = false
        cleanup(oldOutput, oldEncoder, oldServer, oldLive)
        oldPresentation?.dismiss()
        if (hadSession) castContext?.sessionManager?.endCurrentSession(true)
        if (selectLocal) runCatching { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
            .onFailure { Log.i(TAG, "System route needs manual disconnection", it) }
        refresh()
    }

    private fun fail(id: Int, error: Exception) {
        if (id != generation || closed || device == null) return
        Log.w(TAG, "Casting stopped", error)
        stop(); onError()
    }

    private fun cleanup(destination: VisualOutput?, video: VisualStreamEncoder?, stream: LiveStreamServer?, live: LiveCastServer? = null) {
        if (destination == null && video == null && stream == null && live == null) return
        val release = Runnable {
            runCatching { destination?.close() }
            runCatching { video?.close() }
            runCatching { stream?.close() }
            runCatching { live?.close() }
        }
        try { worker.execute(release) } catch (_: java.util.concurrent.RejectedExecutionException) {
            Thread(release, "MilkDrop-cast-cleanup").apply { isDaemon = true; start() }
        }
    }

    @Suppress("DEPRECATION") // API 28 needs the existing LAN networks, not only the internet default.
    private fun lanAddress(): String {
        val connectivity = requireNotNull(ContextCompat.getSystemService(context, ConnectivityManager::class.java))
        val defaultNetwork = if (Build.VERSION.SDK_INT >= 23) connectivity.activeNetwork else null
        for (network in (listOfNotNull(defaultNetwork) + connectivity.allNetworks).distinct()) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val ip = connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
            }?.address?.hostAddress
            if (ip != null) return ip
        }
        error("No local Wi-Fi or Ethernet address")
    }

    override fun close() {
        if (closed) return
        pause(); closed = true
        // Pending install callbacks own encoders/sockets. Let their generation guards release
        // those resources instead of discarding the callbacks when the Activity is destroyed.
        main.removeCallbacks(timeout)
        main.removeCallbacks(checkDisconnect)
        worker.shutdown() // Drain resource cleanup already queued; never interrupt codec release.
    }

    private class ReceiverPresentation(context: Context, display: Display,
        val onSurface: (SurfaceHolder, Int, Int) -> Unit, val onLost: () -> Unit,
    ) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { receiverWindow ->
                WindowCompat.setDecorFitsSystemWindows(receiverWindow, false)
                WindowCompat.getInsetsController(receiverWindow, receiverWindow.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            setContentView(SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width > 0 && height > 0) onSurface(holder, width, height)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = onLost()
                })
            })
            setOnDismissListener { onLost() }
        }
    }

    private companion object {
        const val TAG = "MilkDropCast"
    }
}
