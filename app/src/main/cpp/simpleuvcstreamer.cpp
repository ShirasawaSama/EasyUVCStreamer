#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>
#include <sstream>
#include <atomic>
#include <thread>
#include <set>

#define TAG "UVCStreamer-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<int> g_frame_count{0};
static std::atomic<bool> g_is_streaming{false};
static std::atomic<bool> g_stop_event_thread{false};

static libusb_context *g_usb_ctx = nullptr;
static uvc_context_t *g_uvc_ctx = nullptr;
static uvc_device_handle_t *g_devh = nullptr;
static std::thread g_event_thread;

void uvc_event_thread_func() {
    LOGI("uvc_event_thread: Started");
    struct timeval tv = {0, 100000}; // 100ms
    while (!g_stop_event_thread.load()) {
        int res = libusb_handle_events_timeout_completed(g_usb_ctx, &tv, nullptr);
        if (res < 0) {
            LOGE("libusb_handle_events failed: %d", res);
            if (res == LIBUSB_ERROR_INTERRUPTED) continue;
            break;
        }
    }
    LOGI("uvc_event_thread: Stopped");
}

void uvc_frame_callback(uvc_frame_t *frame, void *ptr) {
    if (frame && frame->data) {
        if (g_is_streaming.load()) {
            g_frame_count.fetch_add(1, std::memory_order_relaxed);
            static int internal_count = 0;
            if (++internal_count % 30 == 0) {
                LOGI("uvc_frame_callback: Received frame %d", internal_count);
            }
        }
    }
}

static int interval_to_fps(uint32_t interval_100ns) {
    if (interval_100ns == 0) return 0;
    return static_cast<int>(10000000 / interval_100ns);
}

/** Negotiate MJPEG ctrl: prefer requested fps, else best available for that size. */
static uvc_error_t get_mjpeg_stream_ctrl(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        int width,
        int height,
        int prefer_fps) {
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, prefer_fps);
    if (res == UVC_SUCCESS) {
        LOGI("get_mjpeg_stream_ctrl: %dx%d @ %dfps OK", width, height, prefer_fps);
        return res;
    }
    LOGI("get_mjpeg_stream_ctrl: %dx%d @ %dfps failed (%d), probing intervals",
         width, height, prefer_fps, res);

    const uvc_format_desc_t *format_desc = uvc_get_format_descs(devh);
    while (format_desc) {
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
            while (frame_desc) {
                if (frame_desc->wWidth == width && frame_desc->wHeight == height) {
                    if (frame_desc->intervals) {
                        // Prefer highest fps (smallest interval)
                        uint32_t best_interval = 0;
                        for (uint32_t *interval = frame_desc->intervals; *interval; ++interval) {
                            if (best_interval == 0 || *interval < best_interval) {
                                best_interval = *interval;
                            }
                        }
                        if (best_interval != 0) {
                            int fps = interval_to_fps(best_interval);
                            res = uvc_get_stream_ctrl_format_size(
                                    devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, fps);
                            if (res == UVC_SUCCESS) {
                                LOGI("get_mjpeg_stream_ctrl: fallback %dx%d @ %dfps OK",
                                     width, height, fps);
                                return res;
                            }
                        }
                    } else if (frame_desc->dwDefaultFrameInterval != 0) {
                        int fps = interval_to_fps(frame_desc->dwDefaultFrameInterval);
                        if (fps < 1) fps = 1;
                        res = uvc_get_stream_ctrl_format_size(
                                devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, fps);
                        if (res == UVC_SUCCESS) {
                            LOGI("get_mjpeg_stream_ctrl: default-interval %dx%d @ %dfps OK",
                                 width, height, fps);
                            return res;
                        }
                    } else if (frame_desc->dwMinFrameInterval != 0) {
                        // Continuous range: try max fps (min interval)
                        int fps = interval_to_fps(frame_desc->dwMinFrameInterval);
                        if (fps < 1) fps = 1;
                        res = uvc_get_stream_ctrl_format_size(
                                devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, fps);
                        if (res == UVC_SUCCESS) {
                            LOGI("get_mjpeg_stream_ctrl: min-interval %dx%d @ %dfps OK",
                                 width, height, fps);
                            return res;
                        }
                    }
                }
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }

    LOGE("get_mjpeg_stream_ctrl: no mode for %dx%d", width, height);
    return res < 0 ? res : UVC_ERROR_INVALID_MODE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeInit(JNIEnv *env, jobject thiz) {
    if (g_uvc_ctx) return 0;

    LOGI("nativeInit: Start");
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
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeStopStream(JNIEnv *env, jobject thiz) {
    g_is_streaming.store(false);
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        LOGI("nativeStopStream: Streaming stopped (device kept open)");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeClose(JNIEnv *env, jobject thiz) {
    g_is_streaming.store(false);
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        uvc_device_handle_t *tmp = g_devh;
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

    uvc_stream_ctrl_t ctrl{};
    uvc_error_t res = get_mjpeg_stream_ctrl(g_devh, &ctrl, width, height, fps);
    if (res < 0) {
        LOGE("Failed to find MJPEG format/size: %d", res);
        return res;
    }

    g_frame_count.store(0);
    g_is_streaming.store(true);

    res = uvc_start_streaming(g_devh, &ctrl, uvc_frame_callback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        g_is_streaming.store(false);
    } else {
        LOGI("nativeStartStream: Streaming %dx%d", width, height);
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

    struct Res {
        int w;
        int h;
        bool operator<(const Res &o) const {
            if (w * h != o.w * o.h) return w * h > o.w * o.h; // larger first
            if (w != o.w) return w > o.w;
            return h > o.h;
        }
    };

    std::set<Res> unique;
    const uvc_format_desc_t *format_desc = uvc_get_format_descs(g_devh);
    while (format_desc) {
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
            while (frame_desc) {
                unique.insert({frame_desc->wWidth, frame_desc->wHeight});
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }

    std::stringstream ss;
    for (const auto &r : unique) {
        ss << r.w << "x" << r.h << ";";
    }
    return env->NewStringUTF(ss.str().c_str());
}
