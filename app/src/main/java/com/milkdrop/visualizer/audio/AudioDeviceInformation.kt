package com.milkdrop.visualizer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.milkdrop.visualizer.R

/** Read-only device diagnostics. Never infer an active format from a device's capabilities. */
internal class AudioDeviceInformation(private val context: Context) {
    private val manager = ContextCompat.getSystemService(context, AudioManager::class.java)
    private val router = ContextCompat.getSystemService(context, MediaRouter::class.java)

    fun text(): String {
        val route = runCatching {
            router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.getName(context)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.engine_info_unavailable)
        val heading = context.getString(R.string.engine_audio_route, route)
        val devices = if (Build.VERSION.SDK_INT >= 23) readDevices()
            else context.getString(R.string.engine_audio_devices_legacy)
        return listOf(heading, devices, context.getString(R.string.engine_audio_active_unknown))
            .joinToString("\n")
    }

    @RequiresApi(23)
    private fun readDevices(): String {
        val audio = manager ?: return context.getString(R.string.engine_info_unavailable)
        return try {
            // API 33 reports the expected media route, not the format playing in another app.
            val mediaDevices = if (Build.VERSION.SDK_INT >= 33) runCatching {
                audio.getAudioDevicesForAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            }.getOrDefault(emptyList()) else emptyList()
            val devices = mediaDevices.ifEmpty { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            val label = context.getString(if (mediaDevices.isNotEmpty())
                R.string.engine_audio_media_devices else R.string.engine_audio_connected_devices)
            val rows = devices.distinctBy { it.id }.joinToString("\n\n") { describe(it) }
            "$label\n${rows.ifBlank { context.getString(R.string.engine_info_unavailable) }}"
        } catch (_: RuntimeException) {
            context.getString(R.string.engine_info_unavailable)
        }
    }

    @RequiresApi(23)
    private fun describe(device: AudioDeviceInfo): String {
        val unknown = context.getString(R.string.engine_info_unavailable)
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: unknown
        val type = when {
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.audio_device_speaker
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.audio_device_earpiece
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.audio_device_bluetooth
            Build.VERSION.SDK_INT >= 31 && (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> R.string.audio_device_bluetooth
            Build.VERSION.SDK_INT >= 33 && device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST -> R.string.audio_device_bluetooth
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.audio_device_wired
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.audio_device_usb
            Build.VERSION.SDK_INT >= 26 && device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.audio_device_usb
            device.type == AudioDeviceInfo.TYPE_HDMI || device.type == AudioDeviceInfo.TYPE_HDMI_ARC -> R.string.audio_device_hdmi
            Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_HDMI_EARC -> R.string.audio_device_hdmi
            else -> R.string.audio_device_other
        }
        fun values(items: IntArray): String = items.filter { it > 0 }.distinct().sorted()
            .joinToString(", ").ifBlank { unknown }
        val bits = device.encodings.asSequence().mapNotNull {
            when {
                it == AudioFormat.ENCODING_PCM_8BIT -> "8"
                it == AudioFormat.ENCODING_PCM_16BIT -> "16"
                it == AudioFormat.ENCODING_PCM_FLOAT -> context.getString(R.string.audio_device_float_bits)
                Build.VERSION.SDK_INT >= 31 && it == AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24"
                Build.VERSION.SDK_INT >= 31 && it == AudioFormat.ENCODING_PCM_32BIT -> "32"
                else -> null
            }
        }.distinct().joinToString(", ").ifBlank { unknown }
        return context.getString(R.string.engine_audio_device_details, name, context.getString(type),
            values(device.sampleRates), bits, values(device.channelCounts))
    }
}
