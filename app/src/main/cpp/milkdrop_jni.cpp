#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <cerrno>
#include <exception>
#include <vector>

#include <projectM-4/projectM.h>
#include <projectM-4/playlist_callbacks.h>
#include <projectM-4/playlist_core.h>
#include <projectM-4/playlist_items.h>
#include <projectM-4/playlist_playback.h>

namespace {

struct BridgeHandle {
    projectm_handle projectM = nullptr;
    projectm_playlist_handle playlist = nullptr;
};

BridgeHandle* AsHandle(jlong handle) {
    return reinterpret_cast<BridgeHandle*>(handle);
}

// projectM's C API is not always exception-safe at its own boundary — e.g. loading "idle://"
// while the connected playlist is empty throws libprojectM::Playlist::PlaylistEmptyException
// straight through, which otherwise aborts the whole process. Preset operations run on every
// button tap and off a background scan, so one bad state shouldn't be able to crash the app.
template <typename Func>
void SafeCall(Func&& func) {
    try {
        func();
    } catch (const std::exception&) {
    } catch (...) {
    }
}

template <typename T, typename Func>
T SafeCallOr(T fallback, Func&& func) {
    try {
        return func();
    } catch (const std::exception&) {
        return fallback;
    } catch (...) {
        return fallback;
    }
}

// Surfaces presets the GLES driver can't compile. Without this, a Next/Prev tap that lands on one
// just silently retries (up to projectM's retry count) and looks like the button did nothing.
void OnPresetSwitchFailed(const char* presetFilename, const char* message, void*) {
    __android_log_print(ANDROID_LOG_WARN, "MilkDropJNI", "Preset failed: %s (%s)",
                        presetFilename != nullptr ? presetFilename : "?",
                        message != nullptr ? message : "?");
}

void OnPresetSwitched(bool isHardCut, unsigned int index, void*) {
    __android_log_print(ANDROID_LOG_DEBUG, "MilkDropJNI", "Switched to #%u (%s)", index,
                        isHardCut ? "hard cut" : "soft cut");
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeCreate(JNIEnv*, jobject) {
    auto* handle = new BridgeHandle();
    handle->projectM = projectm_create();
    handle->playlist = projectm_playlist_create(handle->projectM);
    projectm_playlist_set_preset_switch_failed_event_callback(handle->playlist, &OnPresetSwitchFailed, nullptr);
    projectm_playlist_set_preset_switched_event_callback(handle->playlist, &OnPresetSwitched, nullptr);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeDestroy(JNIEnv*, jobject, jlong handlePtr) {
    BridgeHandle* handle = AsHandle(handlePtr);
    if (handle == nullptr) {
        return;
    }
    projectm_playlist_destroy(handle->playlist);
    projectm_destroy(handle->projectM);
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetWindowSize(
        JNIEnv*, jobject, jlong handlePtr, jint width, jint height) {
    projectm_set_window_size(AsHandle(handlePtr)->projectM,
                              static_cast<size_t>(width),
                              static_cast<size_t>(height));
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeRenderFrame(JNIEnv*, jobject, jlong handlePtr) {
    projectm_handle projectM = AsHandle(handlePtr)->projectM;
    SafeCall([&] { projectm_opengl_render_frame(projectM); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetTextureSearchPaths(
        JNIEnv* env, jobject, jlong handlePtr, jobjectArray paths) {
    jsize count = env->GetArrayLength(paths);
    env->EnsureLocalCapacity(count + 16);
    std::vector<jstring> jstrings(count);
    std::vector<const char*> cstrings(count);
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        jstrings[i] = path;
        cstrings[i] = env->GetStringUTFChars(path, nullptr);
    }

    projectm_set_texture_search_paths(AsHandle(handlePtr)->projectM, cstrings.data(),
                                       static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        env->ReleaseStringUTFChars(jstrings[i], cstrings[i]);
        env->DeleteLocalRef(jstrings[i]);
    }
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetPresetDuration(
        JNIEnv*, jobject, jlong handlePtr, jdouble seconds) {
    projectm_set_preset_duration(AsHandle(handlePtr)->projectM, seconds);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetSoftCutDuration(
        JNIEnv*, jobject, jlong handlePtr, jdouble seconds) {
    projectm_set_soft_cut_duration(AsHandle(handlePtr)->projectM, seconds);
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeAddPresets(
        JNIEnv* env, jobject, jlong handlePtr, jobjectArray paths, jboolean allowDuplicates) {
    jsize count = env->GetArrayLength(paths);
    env->EnsureLocalCapacity(count + 16);
    std::vector<jstring> jstrings(count);
    std::vector<const char*> cstrings(count);
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        jstrings[i] = path;
        cstrings[i] = env->GetStringUTFChars(path, nullptr);
    }

    projectm_playlist_handle playlist = AsHandle(handlePtr)->playlist;
    const char** cstringsData = cstrings.data();
    auto presetCount = static_cast<uint32_t>(count);
    bool allowDup = allowDuplicates == JNI_TRUE;
    uint32_t added = SafeCallOr<uint32_t>(0, [&] {
        return projectm_playlist_add_presets(playlist, cstringsData, presetCount, allowDup);
    });

    for (jsize i = 0; i < count; ++i) {
        env->ReleaseStringUTFChars(jstrings[i], cstrings[i]);
        env->DeleteLocalRef(jstrings[i]);
    }

    return static_cast<jint>(added);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetShuffle(
        JNIEnv*, jobject, jlong handlePtr, jboolean shuffle) {
    projectm_playlist_set_shuffle(AsHandle(handlePtr)->playlist, shuffle == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayNext(
        JNIEnv*, jobject, jlong handlePtr, jboolean hardCut) {
    projectm_playlist_handle playlist = AsHandle(handlePtr)->playlist;
    bool hard = hardCut == JNI_TRUE;
    return SafeCallOr<jint>(0, [&] { return static_cast<jint>(projectm_playlist_play_next(playlist, hard)); });
}

// Walks back through the playlist's play history. projectm_playlist_play_previous doesn't: in
// shuffle mode it just picks another random preset rather than the one shown before.
JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayLast(
        JNIEnv*, jobject, jlong handlePtr, jboolean hardCut) {
    projectm_playlist_handle playlist = AsHandle(handlePtr)->playlist;
    bool hard = hardCut == JNI_TRUE;
    return SafeCallOr<jint>(0, [&] { return static_cast<jint>(projectm_playlist_play_last(playlist, hard)); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetPresetLocked(
        JNIEnv*, jobject, jlong handlePtr, jboolean locked) {
    projectm_set_preset_locked(AsHandle(handlePtr)->projectM, locked == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetMeshSize(
        JNIEnv*, jobject, jlong handlePtr, jint width, jint height) {
    projectm_set_mesh_size(AsHandle(handlePtr)->projectM, static_cast<size_t>(width), static_cast<size_t>(height));
}

JNIEXPORT jboolean JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeGetShuffle(JNIEnv*, jobject, jlong handlePtr) {
    return projectm_playlist_get_shuffle(AsHandle(handlePtr)->playlist) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeGetPlaylistPosition(JNIEnv*, jobject, jlong handlePtr) {
    projectm_playlist_handle playlist = AsHandle(handlePtr)->playlist;
    // Throws PlaylistEmptyException on an empty playlist.
    return SafeCallOr<jint>(0, [&] { return static_cast<jint>(projectm_playlist_get_position(playlist)); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeClearPresets(JNIEnv*, jobject, jlong handlePtr) {
    projectm_playlist_clear(AsHandle(handlePtr)->playlist);
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetPlaylistPosition(
        JNIEnv*, jobject, jlong handlePtr, jint position, jboolean hardCut) {
    projectm_playlist_handle playlist = AsHandle(handlePtr)->playlist;
    auto pos = static_cast<uint32_t>(position);
    bool hard = hardCut == JNI_TRUE;
    return SafeCallOr<jint>(0, [&] { return static_cast<jint>(projectm_playlist_set_position(playlist, pos, hard)); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeFeedPcmInt16(
        JNIEnv* env, jobject, jlong handlePtr, jshortArray samples, jint frameCount, jint channels) {
    jshort* data = env->GetShortArrayElements(samples, nullptr);
    projectm_channels channelEnum = (channels == 2) ? PROJECTM_STEREO : PROJECTM_MONO;
    projectm_pcm_add_int16(AsHandle(handlePtr)->projectM,
                            reinterpret_cast<const int16_t*>(data),
                            static_cast<unsigned int>(frameCount),
                            channelEnum);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
}

// Restricts the given threads of this process to the given CPU cores. Java has no API for thread
// affinity; an app may set it on its own threads without any permission. Returns how many threads
// were updated.
JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetThreadAffinity(
        JNIEnv* env, jobject, jintArray tids, jintArray cpus) {
    cpu_set_t cpuSet;
    CPU_ZERO(&cpuSet);
    jsize cpuCount = env->GetArrayLength(cpus);
    std::vector<jint> cpuIds(static_cast<size_t>(cpuCount));
    env->GetIntArrayRegion(cpus, 0, cpuCount, cpuIds.data());
    for (jint cpu : cpuIds) {
        CPU_SET(cpu, &cpuSet);
    }

    jsize tidCount = env->GetArrayLength(tids);
    std::vector<jint> tidIds(static_cast<size_t>(tidCount));
    env->GetIntArrayRegion(tids, 0, tidCount, tidIds.data());
    jint updated = 0;
    for (jint tid : tidIds) {
        if (sched_setaffinity(tid, sizeof(cpuSet), &cpuSet) == 0) {
            ++updated;
        } else {
            __android_log_print(ANDROID_LOG_WARN, "MilkDropJNI", "sched_setaffinity(%d) failed: %d", tid, errno);
        }
    }
    return updated;
}

} // extern "C"
