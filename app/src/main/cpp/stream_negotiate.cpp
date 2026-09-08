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

uvc_frame_format to_uvc_format(StreamPixelFormat format) {
    switch (format) {
        case StreamPixelFormat::Yuyv:
            return UVC_FRAME_FORMAT_YUYV;
        case StreamPixelFormat::Uyvy:
            return UVC_FRAME_FORMAT_UYVY;
        case StreamPixelFormat::Mjpeg:
        default:
            return UVC_FRAME_FORMAT_MJPEG;
    }
}

bool format_matches_desc(StreamPixelFormat format, const uvc_format_desc_t *format_desc) {
    if (format == StreamPixelFormat::Mjpeg) {
        return format_desc->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG;
    }
    if (format_desc->bDescriptorSubtype != UVC_VS_FORMAT_UNCOMPRESSED) {
        return false;
    }
    const char a = static_cast<char>(format_desc->fourccFormat[0]);
    const char b = static_cast<char>(format_desc->fourccFormat[1]);
    const char c = static_cast<char>(format_desc->fourccFormat[2]);
    const char d = static_cast<char>(format_desc->fourccFormat[3]);
    const bool yuy2 =
            (a == 'Y' && b == 'U' && c == 'Y' && d == '2') ||
            (a == 'Y' && b == 'U' && c == 'Y' && d == 'V');
    const bool uyvy = (a == 'U' && b == 'Y' && c == 'V' && d == 'Y');
    if (format == StreamPixelFormat::Yuyv) return yuy2;
    if (format == StreamPixelFormat::Uyvy) return uyvy;
    return false;
}

std::vector<int> fps_for_size(
        uvc_device_handle_t *devh,
        StreamPixelFormat format,
        int width,
        int height) {
    std::set<int> seen;
    std::vector<int> out;
    const uvc_format_desc_t *format_desc = uvc_get_format_descs(devh);
    while (format_desc) {
        if (format_matches_desc(format, format_desc)) {
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
        StreamPixelFormat format,
        int width,
        int height,
        int fps) {
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            devh, ctrl, to_uvc_format(format), width, height, fps);
    if (res == UVC_SUCCESS) {
        LOGI("negotiate OK fmt=%d %dx%d @%dfps if=%u interval=%u maxFrame=%u maxPayload=%u",
             static_cast<int>(format), width, height, fps,
             ctrl->bInterfaceNumber, ctrl->dwFrameInterval,
             ctrl->dwMaxVideoFrameSize, ctrl->dwMaxPayloadTransferSize);
    } else {
        LOGI("negotiate fmt=%d %dx%d @%dfps failed: %d",
             static_cast<int>(format), width, height, fps, res);
    }
    return res;
}

}  // namespace

uvc_error_t get_stream_ctrl(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        StreamPixelFormat format,
        int width,
        int height,
        int prefer_fps) {
    if (try_format_size(devh, ctrl, format, width, height, prefer_fps) == UVC_SUCCESS) {
        return UVC_SUCCESS;
    }

    auto fps_list = fps_for_size(devh, format, width, height);
    std::sort(fps_list.begin(), fps_list.end());
    uvc_error_t last = UVC_ERROR_INVALID_MODE;
    for (int fps : fps_list) {
        if (fps == prefer_fps) continue;
        last = try_format_size(devh, ctrl, format, width, height, fps);
        if (last == UVC_SUCCESS) return UVC_SUCCESS;
    }

    last = try_format_size(devh, ctrl, format, width, height, 0);
    if (last == UVC_SUCCESS) return UVC_SUCCESS;

    LOGE("get_stream_ctrl: no mode for fmt=%d %dx%d @~%dfps",
         static_cast<int>(format), width, height, prefer_fps);
    return last < 0 ? last : UVC_ERROR_INVALID_MODE;
}
