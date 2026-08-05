#pragma once

#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

/** Negotiate MJPEG stream ctrl; prefer BULK VS interfaces on Android. */
uvc_error_t get_mjpeg_stream_ctrl(
        uvc_device_handle_t *devh,
        uvc_stream_ctrl_t *ctrl,
        int width,
        int height,
        int prefer_fps);
