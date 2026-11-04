#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <cerrno>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include <projectM-4/projectM.h>
#include <projectM-4/logging.h>
#include <projectM-4/playlist_callbacks.h>
#include <projectM-4/playlist_core.h>
#include <projectM-4/playlist_items.h>
#include <projectM-4/playlist_memory.h>
#include <projectM-4/playlist_playback.h>

#include "projectm/ShaderAudioApi.hpp"
#include "projectm/ShaderInputs.hpp"

namespace {

struct BridgeHandle {
    projectm_handle projectM = nullptr;
    projectm_playlist_handle playlist = nullptr;
    // Written only by successful playlist switch callbacks on the GL thread.
    std::string activePresetPath;
    std::vector<float> pcmScratch;
    int sampleRateHz = 0;
    bool audioSnapshot = false;

    ~BridgeHandle() {
        if (playlist != nullptr) projectm_playlist_destroy(playlist);
        if (projectM != nullptr) projectm_destroy(projectM);
    }
};

BridgeHandle* AsHandle(jlong handle) {
    return reinterpret_cast<BridgeHandle*>(handle);
}

void OnProjectMLog(const char* message, projectm_log_level level, void*) {
    int priority = ANDROID_LOG_INFO;
    if (level >= PROJECTM_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == PROJECTM_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    __android_log_print(priority, "projectM", "%s", message != nullptr ? message : "");
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

void OnPresetSwitched(bool isHardCut, unsigned int index, void* userData) {
    auto* handle = static_cast<BridgeHandle*>(userData);
    std::unique_ptr<char, decltype(&projectm_playlist_free_string)> path(
        projectm_playlist_item(handle->playlist, index), &projectm_playlist_free_string);
    if (path) handle->activePresetPath = path.get();
    __android_log_print(ANDROID_LOG_DEBUG, "MilkDropJNI", "Switched to #%u (%s)", index,
                        isHardCut ? "hard cut" : "soft cut");
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeCreate(JNIEnv*, jobject) {
    return SafeCallOr<jlong>(0, [] {
        // Master reports loader/context failures through its log callback and may return null.
        projectm_set_log_callback(&OnProjectMLog, false, nullptr);
        projectm_set_log_level(PROJECTM_LOG_LEVEL_INFO, false);
        auto handle = std::make_unique<BridgeHandle>();
        handle->projectM = projectm_create();
        if (handle->projectM == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, "MilkDropJNI", "projectM initialization failed; see projectM logs");
            return jlong{0};
        }
        handle->playlist = projectm_playlist_create(handle->projectM);
        if (handle->playlist == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, "MilkDropJNI", "projectM playlist initialization failed");
            return jlong{0};
        }
        projectm_playlist_set_preset_switch_failed_event_callback(handle->playlist, &OnPresetSwitchFailed, nullptr);
        projectm_playlist_set_preset_switched_event_callback(handle->playlist, &OnPresetSwitched, handle.get());
        return reinterpret_cast<jlong>(handle.release());
    });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeDestroy(JNIEnv*, jobject, jlong handlePtr) {
    BridgeHandle* handle = AsHandle(handlePtr);
    if (handle == nullptr) {
        return;
    }
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
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetScreenDependentRendering(
        JNIEnv*, jobject, jlong handlePtr, jboolean enabled) {
    // The build patch makes projectM's existing per-instance aspect flag effective.
    projectm_set_aspect_correction(AsHandle(handlePtr)->projectM, enabled != JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeRenderFrame(JNIEnv*, jobject, jlong handlePtr) {
    projectm_handle projectM = AsHandle(handlePtr)->projectM;
    SafeCall([&] { projectm_opengl_render_frame(projectM); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetShaderAudioSettings(
        JNIEnv*, jobject, jlong handlePtr, jfloat scaling, jfloat noiseFloor) {
    SafeCall([&] { milkdrop_set_shader_audio_settings(AsHandle(handlePtr)->projectM, scaling, noiseFloor); });
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetPointer(
        JNIEnv*, jobject, jlong handlePtr, jfloat x, jfloat y, jboolean down, jboolean pressed) {
    SafeCall([&] { milkdrop_set_pointer(AsHandle(handlePtr)->projectM, x, y,
                                       down == JNI_TRUE, pressed == JNI_TRUE); });
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
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetHardCutEnabled(
        JNIEnv*, jobject, jlong handlePtr, jboolean enabled) {
    projectm_set_hard_cut_enabled(AsHandle(handlePtr)->projectM, enabled);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetAdditionalRandomTime(
        JNIEnv*, jobject, jlong handlePtr, jdouble seconds) {
    // The Android TimeKeeper patch interprets this as a uniform, nonnegative addition.
    projectm_set_easter_egg(AsHandle(handlePtr)->projectM, static_cast<float>(seconds));
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetHardCutDuration(
        JNIEnv*, jobject, jlong handlePtr, jdouble seconds) {
    projectm_set_hard_cut_duration(AsHandle(handlePtr)->projectM, seconds);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetFps(
        JNIEnv*, jobject, jlong handlePtr, jint fps) {
    projectm_set_fps(AsHandle(handlePtr)->projectM, fps);
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

JNIEXPORT jstring JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeGetCurrentPresetPath(
        JNIEnv* env, jobject, jlong handlePtr) {
    const auto& path = AsHandle(handlePtr)->activePresetPath;
    return path.empty() ? nullptr : env->NewStringUTF(path.c_str());
}

// Kotlin's list is stable, but projectM removes failed presets from its own playlist. Resolve
// selections by filename at the time of the request instead of reusing an obsolete native index.
JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayPresetPath(
        JNIEnv* env, jobject, jlong handlePtr, jstring path, jboolean hardCut) {
    const char* target = env->GetStringUTFChars(path, nullptr);
    if (target == nullptr) return -1;
    const jint position = SafeCallOr<jint>(-1, [&] {
        const auto playlist = AsHandle(handlePtr)->playlist;
        std::unique_ptr<char*, decltype(&projectm_playlist_free_string_array)> items(
            projectm_playlist_items(playlist, 0, projectm_playlist_size(playlist)),
            &projectm_playlist_free_string_array);
        if (!items) return jint{-1};
        for (uint32_t index = 0; items.get()[index] != nullptr; ++index) {
            if (std::strcmp(items.get()[index], target) == 0) {
                return static_cast<jint>(projectm_playlist_set_position(playlist, index, hardCut == JNI_TRUE));
            }
        }
        // A previously failed file stays selectable in the UI. Reinsert it for an explicit retry.
        const uint32_t index = projectm_playlist_size(playlist);
        if (!projectm_playlist_add_preset(playlist, target, true)) return jint{-1};
        return static_cast<jint>(projectm_playlist_set_position(playlist, index, hardCut == JNI_TRUE));
    });
    env->ReleaseStringUTFChars(path, target);
    return position;
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
        JNIEnv* env, jobject, jlong handlePtr, jshortArray samples, jint frameCount, jint channels,
        jint sampleRateHz, jboolean snapshot, jfloat sensitivity) {
    if (!samples || frameCount <= 0 || (channels != 1 && channels != 2)) return;
    auto* handle = AsHandle(handlePtr);
    if (!handle) return;
    const int frames = std::min(frameCount, env->GetArrayLength(samples) / channels);
    if (frames <= 0) return;
    SafeCall([&] {
        const int rate = sampleRateHz > 0 ? sampleRateHz : 44100;
        const bool isSnapshot = snapshot == JNI_TRUE;
        if (rate != handle->sampleRateHz || isSnapshot != handle->audioSnapshot) {
            milkdrop_set_audio_input_format(handle->projectM, static_cast<uint32_t>(rate), isSnapshot);
            handle->sampleRateHz = rate;
            handle->audioSnapshot = isSnapshot;
        }
        const size_t sampleCount = static_cast<size_t>(frames) * channels;
        handle->pcmScratch.resize(sampleCount);
        jshort* data = env->GetShortArrayElements(samples, nullptr);
        if (!data) return;
        const float gain = std::isfinite(sensitivity) ? std::clamp(sensitivity, 0.0f, 25.0f) : 1.0f;
        // Float input preserves boosted detail; converting back to PCM16 would clip it first.
        for (size_t i = 0; i < sampleCount; ++i) {
            handle->pcmScratch[i] = static_cast<float>(data[i]) * (gain / 32768.0f);
        }
        env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
        projectm_pcm_add_float(handle->projectM, handle->pcmScratch.data(),
                              static_cast<unsigned int>(frames),
                              channels == 2 ? PROJECTM_STEREO : PROJECTM_MONO);
    });
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
