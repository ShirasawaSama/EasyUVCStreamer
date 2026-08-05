#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <vector>

// libusb before libuvc — required for LIBUSB_API_VERSION / uvc_wrap visibility.
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

/**
 * Frame path for the capture callback.
 *
 * Main-line (no HTTP subscribers, preview off): only counters — zero JPEG copy.
 * HTTP subscribers > 0: replace a dedicated latest-JPEG slot (drop-old).
 * Preview on: replace a separate preview slot for UI BitmapFactory.
 */
namespace frame_pipeline {

void on_frame(uvc_frame_t *frame);

void set_preview_enabled(bool enabled);
bool preview_enabled();

/** Take-and-clear latest JPEG for preview. Returns null jbyteArray if none. */
jbyteArray take_latest_frame(JNIEnv *env);

void http_subscriber_add();
void http_subscriber_remove();
int http_subscriber_count();

/**
 * Copy latest HTTP JPEG if seq is newer than last_seq.
 * On success updates last_seq and fills out; returns true.
 */
bool copy_http_frame_if_newer(uint64_t &last_seq, std::vector<uint8_t> &out);

void clear();
void reset_counters();
int exchange_frame_count();

}  // namespace frame_pipeline
