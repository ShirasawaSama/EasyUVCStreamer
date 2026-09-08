#include "jpeg_encode.h"

#include "log.h"

#include <turbojpeg.h>

#include <algorithm>
#include <mutex>
#include <vector>

namespace jpeg_encode {
namespace {

std::mutex g_tj_mu;
tjhandle g_tj = nullptr;
// Planar YUV 4:2:2: Y (w*h) + U ((w/2)*h) + V ((w/2)*h), contiguous for tjCompressFromYUV.
std::vector<uint8_t> g_yuv_planar;

bool ensure_compressor() {
    if (g_tj) return true;
    g_tj = tjInitCompress();
    if (!g_tj) {
        LOGE("tjInitCompress failed: %s", tjGetErrorStr());
        return false;
    }
    return true;
}

size_t planar422_bytes(int width, int height) {
    const int w = width & ~1;
    return static_cast<size_t>(w) * static_cast<size_t>(height) * 2u;
}

/**
 * Deinterleave packed YUYV (Y0 U Y1 V) into contiguous planar YUV422:
 * [Y plane w*h][U plane (w/2)*h][V plane (w/2)*h]
 */
void yuyv_to_planar422(const uint8_t *src, size_t step, uint8_t *dst, int width, int height) {
    const int w = width & ~1;
    const size_t y_size = static_cast<size_t>(w) * static_cast<size_t>(height);
    const size_t uv_size = y_size / 2u;
    uint8_t *y_plane = dst;
    uint8_t *u_plane = dst + y_size;
    uint8_t *v_plane = u_plane + uv_size;

    for (int row = 0; row < height; ++row) {
        const uint8_t *in = src + static_cast<size_t>(row) * step;
        uint8_t *y_out = y_plane + static_cast<size_t>(row) * static_cast<size_t>(w);
        uint8_t *u_out = u_plane + static_cast<size_t>(row) * static_cast<size_t>(w / 2);
        uint8_t *v_out = v_plane + static_cast<size_t>(row) * static_cast<size_t>(w / 2);
        for (int x = 0; x < w; x += 2) {
            const int i = x * 2;
            y_out[x] = in[i];
            u_out[x / 2] = in[i + 1];
            y_out[x + 1] = in[i + 2];
            v_out[x / 2] = in[i + 3];
        }
    }
}

/** Packed UYVY (U Y0 V Y1) → planar YUV422. */
void uyvy_to_planar422(const uint8_t *src, size_t step, uint8_t *dst, int width, int height) {
    const int w = width & ~1;
    const size_t y_size = static_cast<size_t>(w) * static_cast<size_t>(height);
    const size_t uv_size = y_size / 2u;
    uint8_t *y_plane = dst;
    uint8_t *u_plane = dst + y_size;
    uint8_t *v_plane = u_plane + uv_size;

    for (int row = 0; row < height; ++row) {
        const uint8_t *in = src + static_cast<size_t>(row) * step;
        uint8_t *y_out = y_plane + static_cast<size_t>(row) * static_cast<size_t>(w);
        uint8_t *u_out = u_plane + static_cast<size_t>(row) * static_cast<size_t>(w / 2);
        uint8_t *v_out = v_plane + static_cast<size_t>(row) * static_cast<size_t>(w / 2);
        for (int x = 0; x < w; x += 2) {
            const int i = x * 2;
            u_out[x / 2] = in[i];
            y_out[x] = in[i + 1];
            v_out[x / 2] = in[i + 2];
            y_out[x + 1] = in[i + 3];
        }
    }
}

std::vector<uint8_t> planar422_to_jpeg_locked(const uint8_t *planar, int width, int height, int quality) {
    if (!ensure_compressor()) return {};
    const int w = width & ~1;
    if (w <= 0 || height <= 0) return {};

    unsigned char *jpeg_buf = nullptr;
    unsigned long jpeg_size = 0;
    // pad = 1: tightly packed planes (no extra row alignment).
    const int rc = tjCompressFromYUV(
            g_tj,
            planar,
            w,
            1,
            height,
            TJSAMP_422,
            &jpeg_buf,
            &jpeg_size,
            quality,
            TJFLAG_FASTDCT);
    std::vector<uint8_t> out;
    if (rc == 0 && jpeg_buf && jpeg_size > 0) {
        out.assign(jpeg_buf, jpeg_buf + jpeg_size);
    } else {
        LOGE("tjCompressFromYUV failed: %s", tjGetErrorStr2(g_tj));
    }
    if (jpeg_buf) {
        tjFree(jpeg_buf);
    }
    return out;
}

std::vector<uint8_t> encode_packed(
        const uint8_t *src,
        size_t bytes,
        int width,
        int height,
        size_t step,
        int quality,
        bool uyvy) {
    if (!src || width <= 0 || height <= 0) return {};
    const int w = width & ~1;
    if (w <= 0) return {};
    if (step == 0) {
        step = static_cast<size_t>(width) * 2u;
    }
    if (step < static_cast<size_t>(w) * 2u) {
        LOGE("%s_to_jpeg: step %zu too small for width %d", uyvy ? "uyvy" : "yuyv", step, w);
        return {};
    }
    const size_t need = step * static_cast<size_t>(height);
    if (bytes < need) {
        LOGE("%s_to_jpeg: short frame %zu < %zu (step=%zu)",
             uyvy ? "uyvy" : "yuyv", bytes, need, step);
        return {};
    }

    std::lock_guard<std::mutex> lock(g_tj_mu);
    g_yuv_planar.resize(planar422_bytes(w, height));
    if (uyvy) {
        uyvy_to_planar422(src, step, g_yuv_planar.data(), w, height);
    } else {
        yuyv_to_planar422(src, step, g_yuv_planar.data(), w, height);
    }
    return planar422_to_jpeg_locked(g_yuv_planar.data(), w, height, quality);
}

}  // namespace

std::vector<uint8_t> yuyv_to_jpeg(
        const uint8_t *yuyv,
        size_t bytes,
        int width,
        int height,
        size_t step,
        int quality) {
    return encode_packed(yuyv, bytes, width, height, step, quality, false);
}

std::vector<uint8_t> uyvy_to_jpeg(
        const uint8_t *uyvy,
        size_t bytes,
        int width,
        int height,
        size_t step,
        int quality) {
    return encode_packed(uyvy, bytes, width, height, step, quality, true);
}

}  // namespace jpeg_encode
