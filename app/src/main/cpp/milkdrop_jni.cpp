#include <jni.h>
#include <vector>

#include <projectM-4/projectM.h>
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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeCreate(JNIEnv*, jobject) {
    auto* handle = new BridgeHandle();
    handle->projectM = projectm_create();
    handle->playlist = projectm_playlist_create(handle->projectM);
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
    projectm_opengl_render_frame(AsHandle(handlePtr)->projectM);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetTextureSearchPaths(
        JNIEnv* env, jobject, jlong handlePtr, jobjectArray paths) {
    jsize count = env->GetArrayLength(paths);
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

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeAddPlaylistPath(
        JNIEnv* env, jobject, jlong handlePtr, jstring path, jboolean recurse, jboolean allowDuplicates) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    uint32_t count = projectm_playlist_add_path(AsHandle(handlePtr)->playlist, cpath,
                                                 recurse == JNI_TRUE, allowDuplicates == JNI_TRUE);
    env->ReleaseStringUTFChars(path, cpath);
    return static_cast<jint>(count);
}

JNIEXPORT void JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativeSetShuffle(
        JNIEnv*, jobject, jlong handlePtr, jboolean shuffle) {
    projectm_playlist_set_shuffle(AsHandle(handlePtr)->playlist, shuffle == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayNext(
        JNIEnv*, jobject, jlong handlePtr, jboolean hardCut) {
    return static_cast<jint>(projectm_playlist_play_next(AsHandle(handlePtr)->playlist, hardCut == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayPrevious(
        JNIEnv*, jobject, jlong handlePtr, jboolean hardCut) {
    return static_cast<jint>(projectm_playlist_play_previous(AsHandle(handlePtr)->playlist, hardCut == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_com_milkdrop_visualizer_render_ProjectMBridge_nativePlayLast(
        JNIEnv*, jobject, jlong handlePtr, jboolean hardCut) {
    return static_cast<jint>(projectm_playlist_play_last(AsHandle(handlePtr)->playlist, hardCut == JNI_TRUE));
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

} // extern "C"
