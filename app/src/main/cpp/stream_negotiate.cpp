#include "stream_negotiate.h"

#include "log.h"

#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <set>
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
    return static_cast<int>((10000000 + interval_100ns / 2) / interval_100ns);
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

std::vector<uint32_t> collect_intervals(const uvc_frame_desc_t *frame_desc) {
    std::vector<uint32_t> out;
    if (frame_desc->intervals) {
        for (uint32_t *interval = frame_desc->intervals; *interval; ++interval) {
            out.push_back(*interval);
        }
    }
    if (out.empty() && frame_desc->dwDefaultFrameInterval != 0) {
        out.push_back(frame_desc->dwDefaultFrameInterval);
    }
    if (out.empty() && frame_desc->dwMinFrameInterval != 0) {
        out.push_back(frame_desc->dwMinFrameInterval);
    }
    if (out.empty() && frame_desc->dwMaxFrameInterval != 0) {
        out.push_back(frame_desc->dwMaxFrameInterval);
    }
    if (out.empty()) {
        out.push_back(333333);  // ~30fps
    }
    // Unique keep order
    std::vector<uint32_t> unique;
    std::set<uint32_t> seen;
    for (uint32_t v : out) {
        if (seen.insert(v).second) unique.push_back(v);
    }
    return unique;
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
                    for (uint32_t interval : collect_intervals(frame_desc)) {
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
                }
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }

    // Prefer BULK; among same transport prefer closer fps; on tie prefer lower fps
    // (often more likely to pass bandwidth probe).
    std::sort(out.begin(), out.end(), [prefer_fps](const StreamCandidate &a, const StreamCandidate &b) {
        if (a.bulk != b.bulk) return a.bulk && !b.bulk;
        int da = std::abs(a.fps - prefer_fps);
        int db = std::abs(b.fps - prefer_fps);
        if (da != db) return da < db;
        if (a.fps != b.fps) return a.fps < b.fps;
        return a.interval < b.interval;
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
        LOGI("probe failed if=%u fmt=%u frame=%u @%dfps: %d",
             c.ifnum, c.format_index, c.frame_index, c.fps, res);
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

    LOGE("get_mjpeg_stream_ctrl: no mode for %dx%d @~%dfps", width, height, prefer_fps);
    return res < 0 ? res : UVC_ERROR_INVALID_MODE;
}
