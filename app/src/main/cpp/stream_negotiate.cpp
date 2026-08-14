#include "stream_negotiate.h"

#include "log.h"

#include <algorithm>
#include <set>
#include <vector>

namespace {

int interval_to_fps(uint32_t interval_100ns) {
    if (interval_100ns == 0) return 0;
    return static_cast<int>((10000000 + interval_100ns / 2) / interval_100ns);
}

/** Discrete interval list only. Default/min/max are not extra modes. */
std::vector<int> discrete_fps_list(const uvc_frame_desc_t *frame_desc) {
    std::vector<int> out;
    std::set<int> seen;
    auto push = [&](int fps) {
        if (fps > 0 && seen.insert(fps).second) out.push_back(fps);
    };
    if (frame_desc->intervals) {
        for (uint32_t *interval = frame_desc->intervals; *interval; ++interval) {
            push(interval_to_fps(*interval));
        }
    }
    if (out.empty()) {
        push(interval_to_fps(frame_desc->dwDefaultFrameInterval));
        push(interval_to_fps(frame_desc->dwMinFrameInterval));
        push(interval_to_fps(frame_desc->dwMaxFrameInterval));
    }
    return out;
}

std::vector<int> fps_for_size(uvc_device_handle_t *devh, int width, int height) {
    std::set<int> seen;
    std::vector<int> out;
    const uvc_format_desc_t *format_desc = uvc_get_format_descs(devh);
    while (format_desc) {
        if (format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            const uvc_frame_desc_t *frame_desc = format_desc->frame_descs;
            while (frame_desc) {
                if (frame_desc->wWidth == width && frame_desc->wHeight == height) {
                    for (int fps : discrete_fps_list(frame_desc)) {
                        if (seen.insert(fps).second) out.push_back(fps);
                    }
                }
                frame_desc = frame_desc->next;
            }
        }
        format_desc = format_desc->next;
    }
    return out;
}

uvc_error_t try_format_size(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        int width,
        int height,
        int fps) {
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            devh, ctrl, UVC_FRAME_FORMAT_MJPEG, width, height, fps);
    if (res == UVC_SUCCESS) {
        LOGI("negotiate OK %dx%d @%dfps if=%u interval=%u maxFrame=%u maxPayload=%u",
             width, height, fps,
             ctrl->bInterfaceNumber, ctrl->dwFrameInterval,
             ctrl->dwMaxVideoFrameSize, ctrl->dwMaxPayloadTransferSize);
    } else {
        LOGI("negotiate %dx%d @%dfps failed: %d", width, height, fps, res);
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
    // libuvc must fill the probe block (max frame/payload). A hand-rolled
    // uvc_probe_stream_ctrl with only format/frame/interval returns -51.
    if (try_format_size(devh, ctrl, width, height, prefer_fps) == UVC_SUCCESS) {
        return UVC_SUCCESS;
    }

    auto fps_list = fps_for_size(devh, width, height);
    std::sort(fps_list.begin(), fps_list.end());  // lower fps first (USB bandwidth)
    uvc_error_t last = UVC_ERROR_INVALID_MODE;
    for (int fps : fps_list) {
        if (fps == prefer_fps) continue;
        last = try_format_size(devh, ctrl, width, height, fps);
        if (last == UVC_SUCCESS) return UVC_SUCCESS;
    }

    // Some libuvc builds treat fps=0 as "any advertised interval".
    last = try_format_size(devh, ctrl, width, height, 0);
    if (last == UVC_SUCCESS) return UVC_SUCCESS;

    LOGE("get_mjpeg_stream_ctrl: no mode for %dx%d @~%dfps", width, height, prefer_fps);
    return last < 0 ? last : UVC_ERROR_INVALID_MODE;
}
