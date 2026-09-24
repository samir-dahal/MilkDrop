package com.milkdrop.visualizer.render

/**
 * Thin JNI surface over libprojectM + projectm-playlist (native/milkdrop_jni.cpp).
 * Every call after [nativeCreate] takes the handle it returned; native calls that
 * touch the GL context or the preset playlist must run on the GLSurfaceView's GL thread.
 */
object ProjectMBridge {

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("projectM-4")
        System.loadLibrary("projectM_playlist")
        System.loadLibrary("milkdropjni")
    }

    const val CHANNELS_MONO = 1
    const val CHANNELS_STEREO = 2

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSetWindowSize(handle: Long, width: Int, height: Int)
    external fun nativeRenderFrame(handle: Long)
    external fun nativeSetTextureSearchPaths(handle: Long, paths: Array<String>)
    external fun nativeSetPresetDuration(handle: Long, seconds: Double)
    external fun nativeSetSoftCutDuration(handle: Long, seconds: Double)
    external fun nativeAddPlaylistPath(handle: Long, path: String, recurse: Boolean, allowDuplicates: Boolean): Int
    external fun nativeSetShuffle(handle: Long, shuffle: Boolean)
    external fun nativePlayNext(handle: Long, hardCut: Boolean): Int
    external fun nativePlayPrevious(handle: Long, hardCut: Boolean): Int
    external fun nativeFeedPcmInt16(handle: Long, samples: ShortArray, frameCount: Int, channels: Int)
    external fun nativeGetPlaylistPosition(handle: Long): Int
    external fun nativeSetPlaylistPosition(handle: Long, position: Int, hardCut: Boolean): Int
    external fun nativeGetPlaylistItems(handle: Long): Array<String>
}
