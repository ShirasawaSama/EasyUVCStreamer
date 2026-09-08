#pragma once

#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

enum class StreamPixelFormat {
    Mjpeg,
    Yuyv,
    Uyvy,
};

/** Negotiate stream ctrl for the requested pixel format. */
uvc_error_t get_stream_ctrl(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        StreamPixelFormat format,
        int width,
        int height,
        int prefer_fps);
