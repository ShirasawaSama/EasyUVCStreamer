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
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeOpenDevice(JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;
    return uvc_engine::open_device(fd);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStopStream(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    uvc_engine::stop_stream();
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeClose(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    uvc_engine::close_device();
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStartStream(
        JNIEnv *env, jobject thiz, jint width, jint height, jint fps, jstring format) {
    (void) thiz;
    const char *fmt = "mjpeg";
    const char *utf = nullptr;
    if (format) {
        utf = env->GetStringUTFChars(format, nullptr);
        if (utf) fmt = utf;
    }
    const int res = uvc_engine::start_stream(width, height, fps, fmt);
    if (utf) {
        env->ReleaseStringUTFChars(format, utf);
    }
    return res;
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetFrameCount(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return uvc_engine::get_frame_count();
}

JNIEXPORT jstring JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetResolutions(JNIEnv *env, jobject thiz) {
    (void) thiz;
    std::string s = uvc_engine::get_resolutions();
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeSetPreviewEnabled(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    (void) env;
    (void) thiz;
    uvc_engine::set_preview_enabled(enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeTakeLatestFrame(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return uvc_engine::take_latest_frame(env);
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
