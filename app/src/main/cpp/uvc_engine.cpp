#include "uvc_engine.h"

#include "log.h"

// libusb must be included before libuvc so LIBUSB_API_VERSION enables uvc_wrap().
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

#include "frame_pipeline.h"
#include "stream_negotiate.h"

#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
#include <algorithm>

namespace uvc_engine {
namespace {

std::atomic<bool> g_is_streaming{false};
std::atomic<bool> g_stop_event_thread{false};
std::atomic<int> g_event_errors{0};

std::mutex g_dev_mu;
libusb_context *g_usb_ctx = nullptr;
uvc_context_t *g_uvc_ctx = nullptr;
uvc_device_handle_t *g_devh = nullptr;
std::thread g_event_thread;
bool g_event_thread_started = false;

void stop_streaming_unlocked() {
    g_is_streaming.store(false, std::memory_order_release);
    if (!g_devh) return;
    // May block briefly; Android overlay uses timed wait on hot-unplug.
    uvc_stop_streaming(g_devh);
}

void close_handle_unlocked() {
    g_is_streaming.store(false, std::memory_order_release);
    frame_pipeline::clear();
    if (!g_devh) return;
    uvc_device_handle_t *tmp = g_devh;
    g_devh = nullptr;
    // Stop before close; ignore failures when the USB device is already gone.
    uvc_stop_streaming(tmp);
    uvc_close(tmp);
    g_event_errors.store(0, std::memory_order_relaxed);
    LOGI("nativeClose: Device handle closed");
}

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
            int n = g_event_errors.fetch_add(1, std::memory_order_relaxed) + 1;
            LOGE("libusb_handle_events failed: %d (n=%d)", res, n);
            // Device likely gone — stop delivering frames; Java will close on DETACH.
            g_is_streaming.store(false, std::memory_order_release);
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        } else {
            g_event_errors.store(0, std::memory_order_relaxed);
        }
    }
    LOGI("uvc_event_thread: Stopped");
}

void uvc_frame_callback(uvc_frame_t *frame, void *ptr) {
    (void) ptr;
    if (!g_is_streaming.load(std::memory_order_acquire)) return;
    if (!frame) return;
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
    std::lock_guard<std::mutex> lock(g_dev_mu);
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

    std::lock_guard<std::mutex> lock(g_dev_mu);
    if (!g_uvc_ctx) return -1;

    if (g_devh) {
        close_handle_unlocked();
    }

    LOGI("nativeOpenDevice: Wrapping FD %d", fd);
    uvc_error_t res = uvc_wrap(fd, g_uvc_ctx, &g_devh);
    if (res < 0) {
        LOGE("uvc_wrap failed: %d", res);
        g_devh = nullptr;
        return (int) res;
    }

    libusb_device_handle *usb = uvc_get_libusb_handle(g_devh);
    if (usb) log_usb_interfaces(usb);

    g_event_errors.store(0, std::memory_order_relaxed);
    LOGI("nativeOpenDevice: Success");
    return 0;
}

void stop_stream() {
    std::lock_guard<std::mutex> lock(g_dev_mu);
    stop_streaming_unlocked();
    LOGI("nativeStopStream: Streaming stopped (device kept open)");
}

void close_device() {
    std::lock_guard<std::mutex> lock(g_dev_mu);
    close_handle_unlocked();
}

int start_stream(int width, int height, int fps, const char *format) {
    std::lock_guard<std::mutex> lock(g_dev_mu);
    if (!g_devh) return -1;

    stop_streaming_unlocked();

    StreamPixelFormat pixel = StreamPixelFormat::Mjpeg;
    if (format) {
        if (std::strcmp(format, "yuyv") == 0) {
            pixel = StreamPixelFormat::Yuyv;
        } else if (std::strcmp(format, "uyvy") == 0) {
            pixel = StreamPixelFormat::Uyvy;
        } else {
            pixel = StreamPixelFormat::Mjpeg;
        }
    }

    uvc_stream_ctrl_t ctrl{};
    uvc_error_t res = get_stream_ctrl(g_devh, &ctrl, pixel, width, height, fps);
    if (res < 0) {
        LOGE("Failed to find stream format/size fmt=%s: %d", format ? format : "mjpeg", res);
        return res;
    }

    frame_pipeline::reset_counters();
    g_is_streaming.store(true, std::memory_order_release);

    res = uvc_start_streaming(g_devh, &ctrl, uvc_frame_callback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        g_is_streaming.store(false, std::memory_order_release);
    } else {
        LOGI("nativeStartStream: Streaming %s %dx%d if=%u interval=%u maxPayload=%u",
             format ? format : "mjpeg",
             width, height, ctrl.bInterfaceNumber, ctrl.dwFrameInterval, ctrl.dwMaxPayloadTransferSize);
    }
    return (int) res;
}

int get_frame_count() {
    return frame_pipeline::exchange_frame_count();
}

std::string get_resolutions() {
    // Wire: WxH|fps1,fps2|defaultFps|isDeviceDefault|format;
    // format = mjpeg | yuyv | uyvy. If any MJPEG exists, only MJPEG modes are returned.
    std::lock_guard<std::mutex> lock(g_dev_mu);
    if (!g_devh) return "";

    auto interval_to_fps = [](uint32_t interval_100ns) -> int {
        if (interval_100ns == 0) return 0;
        return static_cast<int>((10000000 + interval_100ns / 2) / interval_100ns);
    };

    struct ModeAgg {
        std::set<int> fps;
        int default_fps = 0;
        bool device_default = false;
        const char *format = "mjpeg";
    };

    auto fourcc_tag = [](const uvc_format_desc_t *format_desc) -> const char * {
        const char a = static_cast<char>(format_desc->fourccFormat[0]);
        const char b = static_cast<char>(format_desc->fourccFormat[1]);
        const char c = static_cast<char>(format_desc->fourccFormat[2]);
        const char d = static_cast<char>(format_desc->fourccFormat[3]);
        if ((a == 'Y' && b == 'U' && c == 'Y' && d == '2') ||
            (a == 'Y' && b == 'U' && c == 'Y' && d == 'V')) {
            return "yuyv";
        }
        if (a == 'U' && b == 'Y' && c == 'V' && d == 'Y') {
            return "uyvy";
        }
        return nullptr;
    };

    using Key = std::tuple<int, int, std::string>;
    std::map<Key, ModeAgg> modes;

    const uvc_format_desc_t *format_desc = uvc_get_format_descs(g_devh);
    while (format_desc) {
        const char *fmt = nullptr;
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            fmt = "mjpeg";
        } else if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED) {
            fmt = fourcc_tag(format_desc);
        }
        if (!fmt) {
            format_desc = format_desc->next;
            continue;
        }

        const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
        while (frame_desc) {
            Key key{
                    static_cast<int>(frame_desc->wWidth),
                    static_cast<int>(frame_desc->wHeight),
                    std::string(fmt)};
            ModeAgg &agg = modes[key];
            agg.format = fmt;

            if (frame_desc->intervals) {
                for (uint32_t *interval = frame_desc->intervals; *interval; ++interval) {
                    int fps = interval_to_fps(*interval);
                    if (fps > 0) agg.fps.insert(fps);
                }
            } else {
                auto add_fps = [&](uint32_t interval) {
                    int fps = interval_to_fps(interval);
                    if (fps > 0) agg.fps.insert(fps);
                };
                add_fps(frame_desc->dwDefaultFrameInterval);
                add_fps(frame_desc->dwMinFrameInterval);
                add_fps(frame_desc->dwMaxFrameInterval);
            }

            const int advertised_default =
                    interval_to_fps(frame_desc->dwDefaultFrameInterval);
            if (advertised_default > 0 && agg.fps.count(advertised_default) &&
                agg.default_fps == 0) {
                agg.default_fps = advertised_default;
            }

            const bool is_default_frame =
                    frame_desc->bFrameIndex == format_desc->bDefaultFrameIndex;
            if (is_default_frame) {
                agg.device_default = true;
                if (advertised_default > 0 && agg.fps.count(advertised_default)) {
                    agg.default_fps = advertised_default;
                }
            }

            frame_desc = frame_desc->next;
        }
        format_desc = format_desc->next;
    }

    bool has_mjpeg = false;
    for (const auto &entry : modes) {
        if (std::get<2>(entry.first) == "mjpeg") {
            has_mjpeg = true;
            break;
        }
    }

    std::vector<std::pair<Key, ModeAgg>> ordered;
    ordered.reserve(modes.size());
    for (const auto &entry : modes) {
        if (has_mjpeg && std::get<2>(entry.first) != "mjpeg") {
            continue;
        }
        ordered.push_back(entry);
    }

    std::sort(ordered.begin(), ordered.end(), [](const auto &a, const auto &b) {
        const int pa = std::get<0>(a.first) * std::get<1>(a.first);
        const int pb = std::get<0>(b.first) * std::get<1>(b.first);
        if (pa != pb) return pa > pb;
        if (std::get<0>(a.first) != std::get<0>(b.first)) {
            return std::get<0>(a.first) > std::get<0>(b.first);
        }
        return std::get<2>(a.first) < std::get<2>(b.first);
    });

    std::stringstream ss;
    for (auto &entry : ordered) {
        const int w = std::get<0>(entry.first);
        const int h = std::get<1>(entry.first);
        ModeAgg &agg = entry.second;
        if (agg.fps.empty()) {
            agg.fps.insert(30);
        }
        if (agg.default_fps == 0 || agg.fps.count(agg.default_fps) == 0) {
            agg.default_fps = *agg.fps.begin();
        }

        ss << w << "x" << h << "|";
        bool first = true;
        for (auto it = agg.fps.rbegin(); it != agg.fps.rend(); ++it) {
            if (!first) ss << ",";
            ss << *it;
            first = false;
        }
        ss << "|" << agg.default_fps << "|" << (agg.device_default ? 1 : 0)
           << "|" << agg.format << ";";
        LOGI("mode %dx%d fmt=%s fps={default=%d deviceDefault=%d}",
             w, h, agg.format, agg.default_fps, agg.device_default ? 1 : 0);
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
