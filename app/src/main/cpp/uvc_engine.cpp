#include "uvc_engine.h"

#include "log.h"

// libusb must be included before libuvc so LIBUSB_API_VERSION enables uvc_wrap().
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

#include "frame_pipeline.h"
#include "stream_negotiate.h"

#include <atomic>
#include <chrono>
#include <set>
#include <sstream>
#include <thread>

namespace uvc_engine {
namespace {

std::atomic<bool> g_is_streaming{false};
std::atomic<bool> g_stop_event_thread{false};

libusb_context *g_usb_ctx = nullptr;
uvc_context_t *g_uvc_ctx = nullptr;
uvc_device_handle_t *g_devh = nullptr;
std::thread g_event_thread;
bool g_event_thread_started = false;

void uvc_event_thread_func() {
    LOGI("uvc_event_thread: Started");
    struct timeval tv = {0, 100000};
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
    if (!g_is_streaming.load(std::memory_order_relaxed)) return;
    frame_pipeline::on_frame(frame);
}

void log_usb_interfaces(libusb_device_handle *usb_devh) {
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

}  // namespace

int init() {
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
        return (int) ures;
    }

    if (!g_event_thread_started) {
        g_stop_event_thread.store(false);
        g_event_thread = std::thread(uvc_event_thread_func);
        g_event_thread.detach();
        g_event_thread_started = true;
    }

    LOGI("nativeInit: Success");
    return 0;
}

int open_device(int fd) {
    if (!g_uvc_ctx) {
        int ir = init();
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
        return (int) res;
    }

    libusb_device_handle *usb = uvc_get_libusb_handle(g_devh);
    if (usb) log_usb_interfaces(usb);

    LOGI("nativeOpenDevice: Success");
    return 0;
}

void stop_stream() {
    g_is_streaming.store(false);
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        LOGI("nativeStopStream: Streaming stopped (device kept open)");
    }
}

void close_device() {
    g_is_streaming.store(false);
    frame_pipeline::clear();
    if (g_devh) {
        uvc_stop_streaming(g_devh);
        uvc_device_handle_t *tmp = g_devh;
        g_devh = nullptr;
        uvc_close(tmp);
        LOGI("nativeClose: Device handle closed");
    }
}

int start_stream(int width, int height, int fps) {
    if (!g_devh) return -1;

    g_is_streaming.store(false);
    uvc_stop_streaming(g_devh);

    uvc_stream_ctrl_t ctrl{};
    uvc_error_t res = get_mjpeg_stream_ctrl(g_devh, &ctrl, width, height, fps);
    if (res < 0) {
        LOGE("Failed to find MJPEG format/size: %d", res);
        return res;
    }

    frame_pipeline::reset_counters();
    g_is_streaming.store(true);

    res = uvc_start_streaming(g_devh, &ctrl, uvc_frame_callback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        g_is_streaming.store(false);
    } else {
        LOGI("nativeStartStream: Streaming %dx%d if=%u interval=%u maxPayload=%u",
             width, height, ctrl.bInterfaceNumber, ctrl.dwFrameInterval, ctrl.dwMaxPayloadTransferSize);
    }
    return (int) res;
}

int get_frame_count() {
    return frame_pipeline::exchange_frame_count();
}

std::string get_resolutions() {
    if (!g_devh) return "";

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
    return ss.str();
}

void set_preview_enabled(bool enabled) {
    frame_pipeline::set_preview_enabled(enabled);
}

jbyteArray take_latest_frame(JNIEnv *env) {
    return frame_pipeline::take_latest_frame(env);
}

}  // namespace uvc_engine
