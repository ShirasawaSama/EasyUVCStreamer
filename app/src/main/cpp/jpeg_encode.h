#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace jpeg_encode {

/**
 * Encode packed YUYV/YUY2 to JPEG.
 * @param step bytes per row (from uvc_frame_t::step); 0 → width * 2
 */
std::vector<uint8_t> yuyv_to_jpeg(
        const uint8_t *yuyv,
        size_t bytes,
        int width,
        int height,
        size_t step,
        int quality = 80);

/** Encode packed UYVY to JPEG. */
std::vector<uint8_t> uyvy_to_jpeg(
        const uint8_t *uyvy,
        size_t bytes,
        int width,
        int height,
        size_t step,
        int quality = 80);

}  // namespace jpeg_encode
