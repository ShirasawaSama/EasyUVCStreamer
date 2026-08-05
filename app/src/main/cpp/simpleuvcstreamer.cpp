#include <jni.h>
#include <string>
#include <android/log.h>
#include <libuvc/libuvc.h>

#define TAG "UVCStreamer-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct {
    uvc_context_t *ctx;
    uvc_device_t *dev;
    uvc_device_handle_t *devh;
    uvc_stream_ctrl_t ctrl;
} g_uvc_state = {nullptr, nullptr, nullptr};

// 回调函数：处理接收到的原始帧（目前直接丢弃以保证性能）
void cb(uvc_frame_t *frame, void *ptr) {
    // 这里是性能关键点。目前我们只记录接收到的 MJPEG 帧数量
    // 以后可以在这里将数据推送到 HTTP 服务器
    static int frame_count = 0;
    if (++frame_count % 30 == 0) {
        LOGI("Received 30 frames, size: %zu bytes, format: %d", frame->data_bytes, frame->frame_format);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeInit(JNIEnv *env, jobject thiz) {
    uvc_error_t res = uvc_init(&g_uvc_state.ctx, nullptr);
    if (res < 0) {
        LOGE("uvc_init error");
        return res;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeOpenDevice(JNIEnv *env, jobject thiz, jint fd) {
    // 注意：在 Android 上，我们通常需要 libusb 支持以 FD 方式打开设备
    // 这里假设 vcpkg 安装的 libusb/libuvc 环境支持基本操作
    // 实际上更稳健的做法是使用 libusb_wrap_sys_device
    LOGI("Opening device with FD: %d (not fully implemented with generic libuvc)", fd);
    return -1; // 占位，稍后在完整逻辑中处理
}

extern "C" JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeClose(JNIEnv *env, jobject thiz) {
    if (g_uvc_state.devh) {
        uvc_stop_streaming(g_uvc_state.devh);
        uvc_close(g_uvc_state.devh);
        g_uvc_state.devh = nullptr;
    }
    if (g_uvc_state.dev) {
        uvc_unref_device(g_uvc_state.dev);
        g_uvc_state.dev = nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeStartStream(JNIEnv *env, jobject thiz, jint width, jint height, jint fps) {
    if (!g_uvc_state.devh) return -1;

    uvc_error_t res = uvc_get_stream_ctrl_format_size(
        g_uvc_state.devh, &g_uvc_state.ctrl,
        UVC_FRAME_FORMAT_MJPEG, width, height, fps
    );

    if (res < 0) {
        LOGE("get_stream_ctrl_format_size error: %d", res);
        return res;
    }

    res = uvc_start_streaming(g_uvc_state.devh, &g_uvc_state.ctrl, cb, nullptr, 0);
    if (res < 0) {
        LOGE("start_streaming error: %d", res);
        return res;
    }
    LOGI("Streaming started: %dx%d@%d", width, height, fps);
    return 0;
}
