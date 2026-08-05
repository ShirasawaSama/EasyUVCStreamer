#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>
#include <vector>
#include <sstream>
#include <atomic>
#include <thread>

#define TAG "UVCStreamer-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 使用最简单的全局原子变量，不依赖复杂的结构体
static std::atomic<int> g_frame_count{0};
static std::atomic<bool> g_is_streaming{false};
static std::atomic<bool> g_stop_event_thread{false};

static libusb_context *g_usb_ctx = nullptr;
static uvc_context_t *g_uvc_ctx = nullptr;
static uvc_device_handle_t *g_devh = nullptr;
static std::thread g_event_thread;

void uvc_event_thread_func() {
    LOGI("uvc_event_thread: Started");
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 100000; // 100ms
    while (!g_stop_event_thread.load()) {
        libusb_handle_events_timeout_completed(g_usb_ctx, &tv, nullptr);
    }
    LOGI("uvc_event_thread: Stopped");
}

// 极简回调，只做计数
void uvc_frame_callback(uvc_frame_t *frame, void *ptr) {
    if (frame && frame->data && g_is_streaming.load()) {
        g_frame_count.fetch_add(1, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeInit(JNIEnv *env, jobject thiz) {
    if (g_uvc_ctx) return 0;

    LOGI("nativeInit: Start");
    // 在 Android 上，必须在 init 之前禁用发现
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    int res = libusb_init(&g_usb_ctx);
    if (res < 0) {
        LOGE("libusb_init failed: %d", res);
        return res;
    }

    uvc_error_t ures = uvc_init(&g_uvc_ctx, g_usb_ctx);
    if (ures < 0) {
        LOGE("uvc_init failed: %d", ures);
        return (jint)ures;
    }

    g_stop_event_thread.store(false);
    g_event_thread = std::thread(uvc_event_thread_func);
    g_event_thread.detach();

    LOGI("nativeInit: Success");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeOpenDevice(JNIEnv *env, jobject thiz, jint fd) {
    // 确保已初始化
    if (!g_uvc_ctx) {
        jint ir = Java_com_omoai_simpleuvcstreamer_MainActivity_nativeInit(env, thiz);
        if (ir < 0) return -100 + ir;
    }

    if (g_devh) {
        g_is_streaming.store(false);
        uvc_stop_streaming(g_devh);
        uvc_close(g_devh);
        g_devh = nullptr;
    }

    LOGI("nativeOpenDevice: Wrapping FD %d", fd);
    uvc_error_t res = uvc_wrap(fd, g_uvc_ctx, &g_devh);
    if (res < 0) {
        LOGE("uvc_wrap failed: %d", res);
        return (jint)res;
    }

    LOGI("nativeOpenDevice: Success");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeClose(JNIEnv *env, jobject thiz) {
    g_is_streaming.store(false);
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        uvc_device_handle_t* tmp = g_devh;
        g_devh = nullptr;
        uvc_close(tmp);
        LOGI("nativeClose: Device handle closed");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeStartStream(JNIEnv *env, jobject thiz, jint width, jint height, jint fps) {
    if (!g_devh) return -1;

    g_is_streaming.store(false);
    uvc_stop_streaming(g_devh);

    uvc_stream_ctrl_t ctrl;
    // 首先尝试 MJPEG
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
        g_devh, &ctrl,
        UVC_FRAME_FORMAT_MJPEG, width, height, fps
    );

    if (res < 0) {
        LOGI("MJPEG not supported, trying YUYV...");
        // 备选尝试 YUYV
        res = uvc_get_stream_ctrl_format_size(
            g_devh, &ctrl,
            UVC_FRAME_FORMAT_YUYV, width, height, fps
        );
    }

    if (res < 0) {
        LOGE("Failed to find compatible format/size: %d", res);
        return res;
    }

    g_frame_count.store(0);
    g_is_streaming.store(true);

    res = uvc_start_streaming(g_devh, &ctrl, uvc_frame_callback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        g_is_streaming.store(false);
    }
    return (jint)res;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeGetFrameCount(JNIEnv *env, jobject thiz) {
    return g_frame_count.exchange(0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeGetResolutions(JNIEnv *env, jobject thiz) {
    if (!g_devh) return env->NewStringUTF("");
    std::stringstream ss;
    const uvc_format_desc_t *format_desc = uvc_get_format_descs(g_devh);
    while (format_desc) {
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG ||
            format_desc->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED ||
            format_desc->bDescriptorSubtype == UVC_VS_FORMAT_FRAME_BASED) {
            const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
            while (frame_desc) {
                ss << frame_desc->wWidth << "x" << frame_desc->wHeight << ";";
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }
    return env->NewStringUTF(ss.str().c_str());
}
