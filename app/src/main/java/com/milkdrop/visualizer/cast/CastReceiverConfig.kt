package com.milkdrop.visualizer.cast

import com.google.android.gms.cast.CastMediaControlIntent
import com.milkdrop.visualizer.BuildConfig

internal object CastReceiverConfig {
    const val NAMESPACE = "urn:x-cast:com.milkdrop.visualizer.live"
    val custom: Boolean get() = BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()
    val applicationId: String get() = BuildConfig.CAST_RECEIVER_APP_ID.ifBlank {
        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
