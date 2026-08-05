#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>

// libusb before libuvc — required for LIBUSB_API_VERSION / uvc_wrap visibility.
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

/**
 * Frame path for the capture callback.
 *
 * Main-line (preview off): only increment counters — zero JPEG copy, zero decode.
 * Preview on: replace a single latest-JPEG slot (drop-old) for UI BitmapFactory.
 * Future HTTP push should attach as a separate consumer of raw MJPEG, not via preview.
 */
namespace frame_pipeline {

void on_frame(uvc_frame_t *frame);

void set_preview_enabled(bool enabled);
bool preview_enabled();

/** Take-and-clear latest JPEG. Returns null jbyteArray if none. */
jbyteArray take_latest_frame(JNIEnv *env);

void clear();
void reset_counters();
int exchange_frame_count();

}  // namespace frame_pipeline
