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
std::atomic<int> g_http_subscribers{0};

std::mutex g_preview_mu;
std::vector<uint8_t> g_latest_jpeg;
bool g_has_latest = false;

std::mutex g_http_mu;
std::vector<uint8_t> g_http_jpeg;
uint64_t g_http_seq = 0;
bool g_http_has = false;

void replace_slot(std::mutex &mu,
                  std::vector<uint8_t> &buf,
                  bool &has,
                  uint64_t *seq,
                  const void *data,
                  size_t len) {
    std::lock_guard<std::mutex> lock(mu);
    buf.resize(len);
    std::memcpy(buf.data(), data, len);
    has = true;
    if (seq) {
        ++(*seq);
    }
}

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

    const bool want_http = g_http_subscribers.load(std::memory_order_relaxed) > 0;
    const bool want_preview = g_preview_enabled.load(std::memory_order_relaxed);
    if (!want_http && !want_preview) {
        return;
    }
    if (!frame || !frame->data || frame->data_bytes == 0) {
        return;
    }
    if (frame->frame_format != UVC_FRAME_FORMAT_MJPEG) {
        return;
    }

    if (want_http) {
        replace_slot(g_http_mu, g_http_jpeg, g_http_has, &g_http_seq,
                     frame->data, frame->data_bytes);
    }
    if (want_preview) {
        replace_slot(g_preview_mu, g_latest_jpeg, g_has_latest, nullptr,
                     frame->data, frame->data_bytes);
    }
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
    return arr;
}

void http_subscriber_add() {
    g_http_subscribers.fetch_add(1, std::memory_order_relaxed);
}

void http_subscriber_remove() {
    int prev = g_http_subscribers.fetch_sub(1, std::memory_order_relaxed);
    if (prev <= 1) {
        g_http_subscribers.store(0, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(g_http_mu);
        g_http_jpeg.clear();
        g_http_has = false;
        // keep seq monotonic
    }
}

int http_subscriber_count() {
    return g_http_subscribers.load(std::memory_order_relaxed);
}

bool copy_http_frame_if_newer(uint64_t &last_seq, std::vector<uint8_t> &out) {
    std::lock_guard<std::mutex> lock(g_http_mu);
    if (!g_http_has || g_http_jpeg.empty() || g_http_seq == last_seq) {
        return false;
    }
    out = g_http_jpeg;
    last_seq = g_http_seq;
    return true;
}

void clear() {
    {
        std::lock_guard<std::mutex> lock(g_preview_mu);
        g_latest_jpeg.clear();
        g_has_latest = false;
    }
    {
        std::lock_guard<std::mutex> lock(g_http_mu);
        g_http_jpeg.clear();
        g_http_has = false;
    }
}

void reset_counters() {
    g_frame_count.store(0, std::memory_order_relaxed);
    g_callback_seq.store(0, std::memory_order_relaxed);
}

int exchange_frame_count() {
    return g_frame_count.exchange(0);
}

}  // namespace frame_pipeline
