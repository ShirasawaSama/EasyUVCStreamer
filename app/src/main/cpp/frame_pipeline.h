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

void on_frame(int channel, uvc_frame_t *frame);

void set_preview_enabled(int channel, bool enabled);
bool preview_enabled(int channel);

/** Take-and-clear latest JPEG for preview. Returns null jbyteArray if none. */
jbyteArray take_latest_frame(int channel, JNIEnv *env);

void http_subscriber_add(int channel);
void http_subscriber_remove(int channel);
int http_subscriber_count(int channel);

/**
 * Copy latest HTTP JPEG if seq is newer than last_seq.
 * On success updates last_seq and fills out; returns true.
 */
bool copy_http_frame_if_newer(int channel, uint64_t &last_seq, std::vector<uint8_t> &out);

void clear(int channel);
void reset_counters(int channel);
int exchange_frame_count(int channel);

}  // namespace frame_pipeline
