#include "channel_config.h"
#include "uvc_engine.h"
#include "http_mjpeg_server.h"

#include <jni.h>
#include <string>

extern "C" {

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return uvc_engine::init();
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeOpenDevice(JNIEnv *env, jobject thiz, jint channel, jint fd) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return -1;
    }
    (void) env;
    (void) thiz;
    return uvc_engine::open_device(channel, fd);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStopStream(JNIEnv *env, jobject thiz, jint channel) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return;
    }
    (void) env;
    (void) thiz;
    uvc_engine::stop_stream(channel);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeClose(JNIEnv *env, jobject thiz, jint channel) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return;
    }
    (void) env;
    (void) thiz;
    uvc_engine::close_device(channel);
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStartStream(
        JNIEnv *env, jobject thiz, jint channel, jint width, jint height, jint fps, jstring format) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return -1;
    }
    (void) thiz;
    const char *fmt = "mjpeg";
    const char *utf = nullptr;
    if (format) {
        utf = env->GetStringUTFChars(format, nullptr);
        if (utf) fmt = utf;
    }
    const int res = uvc_engine::start_stream(channel, width, height, fps, fmt);
    if (utf) {
        env->ReleaseStringUTFChars(format, utf);
    }
    return res;
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetFrameCount(JNIEnv *env, jobject thiz, jint channel) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return -1;
    }
    (void) env;
    (void) thiz;
    return uvc_engine::get_frame_count(channel);
}

JNIEXPORT jstring JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetResolutions(JNIEnv *env, jobject thiz, jint channel) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return nullptr;
    }
    (void) thiz;
    std::string s = uvc_engine::get_resolutions(channel);
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeSetPreviewEnabled(
        JNIEnv *env, jobject thiz, jint channel, jboolean enabled) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return;
    }
    (void) env;
    (void) thiz;
    uvc_engine::set_preview_enabled(channel, enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeTakeLatestFrame(JNIEnv *env, jobject thiz, jint channel) {
    if (channel < 0 || channel >= kCameraChannelCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid camera channel");
        return nullptr;
    }
    (void) thiz;
    return uvc_engine::take_latest_frame(channel, env);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeSetChannelOrder(JNIEnv *env, jobject, jintArray slots) {
    jint order[kCameraChannelCount];
    const int count = env->GetArrayLength(slots);
    if (count < 1 || count > kCameraChannelCount) return;
    env->GetIntArrayRegion(slots, 0, count, order);
    http_mjpeg_server::set_channel_order(order, count);
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStartHttpServer(
        JNIEnv *env, jobject thiz, jint port) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::start(port);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStopHttpServer(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    http_mjpeg_server::stop();
}

JNIEXPORT jboolean JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeIsHttpServerRunning(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::is_running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetHttpServerPort(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::port();
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetHttpClientCount(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::client_count();
}

}  // extern "C"
