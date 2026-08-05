#include "frame_pipeline.h"

#include "log.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <vector>

namespace frame_pipeline {
namespace {

std::atomic<int> g_frame_count{0};
std::atomic<int> g_callback_seq{0};
std::atomic<bool> g_preview_enabled{false};

std::mutex g_preview_mu;
std::vector<uint8_t> g_latest_jpeg;
bool g_has_latest = false;

}  // namespace

void on_frame(uvc_frame_t *frame) {
    g_frame_count.fetch_add(1, std::memory_order_relaxed);
    int n = g_callback_seq.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1 || n % 30 == 0) {
        LOGI("frame #%d size=%zu fmt=%d",
             n,
             frame ? frame->data_bytes : 0,
             frame ? (int) frame->frame_format : -1);
    }

    // Preview is opt-in: skip JPEG copy entirely when off (main-line = zero extra copy).
    if (!g_preview_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    if (!frame || !frame->data || frame->data_bytes == 0) {
        return;
    }
    // Only stash MJPEG; other formats would need decode — not on main path.
    if (frame->frame_format != UVC_FRAME_FORMAT_MJPEG) {
        return;
    }

    std::lock_guard<std::mutex> lock(g_preview_mu);
    g_latest_jpeg.resize(frame->data_bytes);
    std::memcpy(g_latest_jpeg.data(), frame->data, frame->data_bytes);
    g_has_latest = true;
}

void set_preview_enabled(bool enabled) {
    g_preview_enabled.store(enabled, std::memory_order_relaxed);
    if (!enabled) {
        std::lock_guard<std::mutex> lock(g_preview_mu);
        g_latest_jpeg.clear();
        g_has_latest = false;
    }
}

bool preview_enabled() {
    return g_preview_enabled.load(std::memory_order_relaxed);
}

jbyteArray take_latest_frame(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(g_preview_mu);
    if (!g_has_latest || g_latest_jpeg.empty()) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(g_latest_jpeg.size()));
    if (!arr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            arr, 0, static_cast<jsize>(g_latest_jpeg.size()),
            reinterpret_cast<const jbyte *>(g_latest_jpeg.data()));
    g_has_latest = false;
    // Keep capacity; clear logical content for next producer write.
    return arr;
}

void clear() {
    g_preview_enabled.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_preview_mu);
    g_latest_jpeg.clear();
    g_has_latest = false;
}

void reset_counters() {
    g_frame_count.store(0, std::memory_order_relaxed);
    g_callback_seq.store(0, std::memory_order_relaxed);
}

int exchange_frame_count() {
    return g_frame_count.exchange(0);
}

}  // namespace frame_pipeline
