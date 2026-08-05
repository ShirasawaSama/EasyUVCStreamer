#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>
#include <sstream>
#include <atomic>
#include <thread>
#include <chrono>
#include <set>
#include <vector>
#include <algorithm>
#include <cstdlib>
#include <climits>
#include <cstdint>

#define TAG "UVCStreamer-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<int> g_frame_count{0};
static std::atomic<bool> g_is_streaming{false};
static std::atomic<bool> g_stop_event_thread{false};
static std::atomic<int> g_callback_seq{0};

static libusb_context *g_usb_ctx = nullptr;
static uvc_context_t *g_uvc_ctx = nullptr;
static uvc_device_handle_t *g_devh = nullptr;
static std::thread g_event_thread;

/** Matches libuvc's uvc_streaming_interface layout (public format_desc->parent). */
struct StreamingIfLayout {
    void *device_info_parent;
    void *prev;
    void *next;
    uint8_t bInterfaceNumber;
};

struct StreamCandidate {
    uint8_t ifnum;
    uint8_t format_index;
    uint8_t frame_index;
    uint32_t interval;
    int fps;
    bool bulk;
};

void uvc_event_thread_func() {
    LOGI("uvc_event_thread: Started");
    struct timeval tv = {0, 100000}; // 100ms
    while (!g_stop_event_thread.load()) {
        if (!g_usb_ctx) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }
        int res = libusb_handle_events_timeout_completed(g_usb_ctx, &tv, nullptr);
        if (res < 0) {
            LOGE("libusb_handle_events failed: %d", res);
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
    }
    LOGI("uvc_event_thread: Stopped");
}

void uvc_frame_callback(uvc_frame_t *frame, void *ptr) {
    (void) ptr;
    if (!g_is_streaming.load()) return;
    g_frame_count.fetch_add(1, std::memory_order_relaxed);
    int n = g_callback_seq.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1 || n % 30 == 0) {
        LOGI("uvc_frame_callback: frame #%d size=%zu fmt=%d",
             n,
             frame ? frame->data_bytes : 0,
             frame ? (int) frame->frame_format : -1);
    }
}

static int interval_to_fps(uint32_t interval_100ns) {
    if (interval_100ns == 0) return 0;
    return static_cast<int>(10000000 / interval_100ns);
}

static uint8_t format_interface_number(const uvc_format_desc_t *format_desc) {
    if (!format_desc || !format_desc->parent) return 0;
    return reinterpret_cast<const StreamingIfLayout *>(format_desc->parent)->bInterfaceNumber;
}

static bool interface_is_bulk(libusb_device_handle *usb_devh, uint8_t ifnum) {
    libusb_device *dev = libusb_get_device(usb_devh);
    if (!dev) return false;

    libusb_config_descriptor *config = nullptr;
    if (libusb_get_active_config_descriptor(dev, &config) != 0 || !config) {
        return false;
    }

    bool bulk = false;
    bool found = false;
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const libusb_interface &intf = config->interface[i];
        if (intf.num_altsetting <= 0) continue;
        if (intf.altsetting[0].bInterfaceNumber != ifnum) continue;
        found = true;
        // UVC: isochronous VS interfaces have multiple altsettings; bulk has one.
        bulk = intf.num_altsetting <= 1;
        LOGI("VS if=%u altsettings=%d class=%u subclass=%u -> %s",
             ifnum,
             intf.num_altsetting,
             intf.altsetting[0].bInterfaceClass,
             intf.altsetting[0].bInterfaceSubClass,
             bulk ? "BULK" : "ISOCH");
        break;
    }
    libusb_free_config_descriptor(config);
    return found && bulk;
}

static void log_usb_interfaces(libusb_device_handle *usb_devh) {
    libusb_device *dev = libusb_get_device(usb_devh);
    if (!dev) return;
    libusb_config_descriptor *config = nullptr;
    if (libusb_get_active_config_descriptor(dev, &config) != 0 || !config) return;
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const libusb_interface &intf = config->interface[i];
        if (intf.num_altsetting <= 0) continue;
        const auto &alt0 = intf.altsetting[0];
        LOGI("USB if[%d]=%u alts=%d class=%u/%u eps=%u",
             i, alt0.bInterfaceNumber, intf.num_altsetting,
             alt0.bInterfaceClass, alt0.bInterfaceSubClass, alt0.bNumEndpoints);
    }
    libusb_free_config_descriptor(config);
}

static uint32_t pick_interval(const uvc_frame_desc_t *frame_desc, int prefer_fps) {
    if (frame_desc->intervals) {
        uint32_t best = 0;
        int best_delta = INT_MAX;
        for (uint32_t *interval = frame_desc->intervals; *interval; ++interval) {
            int fps = interval_to_fps(*interval);
            if (fps <= 0) continue;
            int delta = std::abs(fps - prefer_fps);
            if (delta < best_delta) {
                best_delta = delta;
                best = *interval;
            }
        }
        if (best != 0) return best;
    }
    if (frame_desc->dwDefaultFrameInterval != 0) {
        return frame_desc->dwDefaultFrameInterval;
    }
    if (frame_desc->dwMinFrameInterval != 0) {
        return frame_desc->dwMinFrameInterval;
    }
    // 30fps fallback in 100ns units
    return 333333;
}

static std::vector<StreamCandidate> collect_mjpeg_candidates(
        uvc_device_handle_t *devh,
        int width,
        int height,
        int prefer_fps) {
    std::vector<StreamCandidate> out;
    libusb_device_handle *usb = uvc_get_libusb_handle(devh);
    if (!usb) return out;

    const uvc_format_desc_t *format_desc = uvc_get_format_descs(devh);
    while (format_desc) {
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            uint8_t ifnum = format_interface_number(format_desc);
            bool bulk = interface_is_bulk(usb, ifnum);
            const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
            while (frame_desc) {
                if (frame_desc->wWidth == width && frame_desc->wHeight == height) {
                    uint32_t interval = pick_interval(frame_desc, prefer_fps);
                    StreamCandidate c{};
                    c.ifnum = ifnum;
                    c.format_index = format_desc->bFormatIndex;
                    c.frame_index = frame_desc->bFrameIndex;
                    c.interval = interval;
                    c.fps = interval_to_fps(interval);
                    c.bulk = bulk;
                    out.push_back(c);
                    LOGI("candidate: if=%u fmt=%u frame=%u %dx%d @%dfps (%s)",
                         c.ifnum, c.format_index, c.frame_index,
                         width, height, c.fps, c.bulk ? "BULK" : "ISOCH");
                }
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }

    // Prefer BULK (Android isoch host support is often broken), then closer fps.
    std::sort(out.begin(), out.end(), [prefer_fps](const StreamCandidate &a, const StreamCandidate &b) {
        if (a.bulk != b.bulk) return a.bulk && !b.bulk;
        return std::abs(a.fps - prefer_fps) < std::abs(b.fps - prefer_fps);
    });
    return out;
}

static uvc_error_t probe_candidate(uvc_device_handle_t *devh, const StreamCandidate &c, uvc_stream_ctrl_t *ctrl) {
    libusb_device_handle *usb = uvc_get_libusb_handle(devh);
    if (!usb) return UVC_ERROR_INVALID_DEVICE;

    int claim = libusb_claim_interface(usb, c.ifnum);
    if (claim != 0 && claim != LIBUSB_ERROR_BUSY) {
        LOGE("libusb_claim_interface(%u) failed: %d", c.ifnum, claim);
        return static_cast<uvc_error_t>(claim);
    }

    uvc_stream_ctrl_t local{};
    local.bInterfaceNumber = c.ifnum;
    local.bmHint = 1;
    local.bFormatIndex = c.format_index;
    local.bFrameIndex = c.frame_index;
    local.dwFrameInterval = c.interval;

    uvc_error_t res = uvc_probe_stream_ctrl(devh, &local);
    if (res == UVC_SUCCESS) {
        *ctrl = local;
        LOGI("probe OK: if=%u fmt=%u frame=%u interval=%u maxFrame=%u maxPayload=%u (%s)",
             ctrl->bInterfaceNumber, ctrl->bFormatIndex, ctrl->bFrameIndex,
             ctrl->dwFrameInterval, ctrl->dwMaxVideoFrameSize, ctrl->dwMaxPayloadTransferSize,
             c.bulk ? "BULK" : "ISOCH");
    } else {
        LOGI("probe failed if=%u fmt=%u frame=%u: %d", c.ifnum, c.format_index, c.frame_index, res);
    }
    return res;
}

/** Negotiate MJPEG ctrl, preferring BULK VS interfaces for Android. */
static uvc_error_t get_mjpeg_stream_ctrl(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        int width,
        int height,
        int prefer_fps) {
    auto candidates = collect_mjpeg_candidates(devh, width, height, prefer_fps);
    for (const auto &c : candidates) {
        if (probe_candidate(devh, c, ctrl) == UVC_SUCCESS) {
            return UVC_SUCCESS;
        }
    }

    // Fallback to libuvc helper (first match — may be isoch).
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, prefer_fps);
    if (res == UVC_SUCCESS) {
        LOGI("fallback get_stream_ctrl_format_size OK if=%u %dx%d",
             ctrl->bInterfaceNumber, width, height);
        return res;
    }

    LOGE("get_mjpeg_stream_ctrl: no mode for %dx%d", width, height);
    return res < 0 ? res : UVC_ERROR_INVALID_MODE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (g_uvc_ctx) return 0;

    LOGI("nativeInit: Start");
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    int res = libusb_init(&g_usb_ctx);
    if (res < 0) {
        LOGE("libusb_init failed: %d", res);
        return res;
    }
    libusb_set_option(g_usb_ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

    uvc_error_t ures = uvc_init(&g_uvc_ctx, g_usb_ctx);
    if (ures < 0) {
        LOGE("uvc_init failed: %d", ures);
        return (jint) ures;
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
        return (jint) res;
    }

    libusb_device_handle *usb = uvc_get_libusb_handle(g_devh);
    if (usb) log_usb_interfaces(usb);

    LOGI("nativeOpenDevice: Success");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeStopStream(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    g_is_streaming.store(false);
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        LOGI("nativeStopStream: Streaming stopped (device kept open)");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeClose(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
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
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeStartStream(
        JNIEnv *env, jobject thiz, jint width, jint height, jint fps) {
    (void) env;
    (void) thiz;
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
    g_callback_seq.store(0);
    g_is_streaming.store(true);

    res = uvc_start_streaming(g_devh, &ctrl, uvc_frame_callback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        g_is_streaming.store(false);
    } else {
        LOGI("nativeStartStream: Streaming %dx%d if=%u interval=%u maxPayload=%u",
             width, height, ctrl.bInterfaceNumber, ctrl.dwFrameInterval, ctrl.dwMaxPayloadTransferSize);
    }
    return (jint) res;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeGetFrameCount(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return g_frame_count.exchange(0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omoai_simpleuvcstreamer_MainActivity_nativeGetResolutions(JNIEnv *env, jobject thiz) {
    (void) thiz;
    if (!g_devh) return env->NewStringUTF("");

    struct Res {
        int w;
        int h;
        bool operator<(const Res &o) const {
            if (w * h != o.w * o.h) return w * h > o.w * o.h;
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
