#include "stream_negotiate.h"

#include "log.h"

#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <vector>

namespace {

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

int interval_to_fps(uint32_t interval_100ns) {
    if (interval_100ns == 0) return 0;
    return static_cast<int>(10000000 / interval_100ns);
}

uint8_t format_interface_number(const uvc_format_desc_t *format_desc) {
    if (!format_desc || !format_desc->parent) return 0;
    return reinterpret_cast<const StreamingIfLayout *>(format_desc->parent)->bInterfaceNumber;
}

bool interface_is_bulk(libusb_device_handle *usb_devh, uint8_t ifnum) {
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

uint32_t pick_interval(const uvc_frame_desc_t *frame_desc, int prefer_fps) {
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
    return 333333;
}

std::vector<StreamCandidate> collect_mjpeg_candidates(
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

    std::sort(out.begin(), out.end(), [prefer_fps](const StreamCandidate &a, const StreamCandidate &b) {
        if (a.bulk != b.bulk) return a.bulk && !b.bulk;
        return std::abs(a.fps - prefer_fps) < std::abs(b.fps - prefer_fps);
    });
    return out;
}

uvc_error_t probe_candidate(uvc_device_handle_t *devh, const StreamCandidate &c, uvc_stream_ctrl_t *ctrl) {
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

}  // namespace

uvc_error_t get_mjpeg_stream_ctrl(
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
