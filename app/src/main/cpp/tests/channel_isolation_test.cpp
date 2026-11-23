#include "frame_pipeline.h"
#include "jpeg_encode.h"
#include <cstdio>
#include <thread>
#include <vector>
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)

static void send(int channel, std::vector<uint8_t> &jpeg) {
    uvc_frame_t frame{};
    frame.data = jpeg.data(); frame.data_bytes = jpeg.size();
    frame.width = 16; frame.height = 16; frame.frame_format = UVC_FRAME_FORMAT_MJPEG;
    frame_pipeline::on_frame(channel, &frame);
}
int main() {
    std::vector<uint8_t> yuv(16 * 16 * 2, 128);
    for (size_t i = 0; i < yuv.size(); i += 2) yuv[i] = 32;
    auto dark = jpeg_encode::yuyv_to_jpeg(yuv.data(), yuv.size(), 16, 16, 32);
    for (size_t i = 0; i < yuv.size(); i += 2) yuv[i] = 224;
    auto light = jpeg_encode::yuyv_to_jpeg(yuv.data(), yuv.size(), 16, 16, 32);
    CHECK(!dark.empty() && !light.empty() && dark != light);
    for (size_t i = 0; i < yuv.size(); i += 2) yuv[i] = 128;
    auto middle = jpeg_encode::yuyv_to_jpeg(yuv.data(), yuv.size(), 16, 16, 32);
    CHECK(!middle.empty() && middle != dark && middle != light);
    frame_pipeline::http_subscriber_add(2);
    send(2, middle);
    uint64_t seq2 = 0;
    std::vector<uint8_t> third;
    CHECK(frame_pipeline::copy_http_frame_if_newer(2, seq2, third) && third == middle);
    CHECK(frame_pipeline::exchange_frame_count(2) == 1);

    frame_pipeline::http_subscriber_add(0); frame_pipeline::http_subscriber_add(1);
    frame_pipeline::set_preview_enabled(0, true);
    CHECK(frame_pipeline::preview_enabled(0) && !frame_pipeline::preview_enabled(1));
    send(0, dark); send(1, light);
    uint64_t seq0 = 0, seq1 = 0;
    std::vector<uint8_t> out;
    CHECK(frame_pipeline::copy_http_frame_if_newer(0, seq0, out) && out == dark);
    CHECK(frame_pipeline::copy_http_frame_if_newer(1, seq1, out) && out == light);
    CHECK(!frame_pipeline::copy_http_frame_if_newer(0, seq0, out));
    CHECK(frame_pipeline::exchange_frame_count(0) == 1);
    CHECK(frame_pipeline::exchange_frame_count(0) == 0);
    CHECK(frame_pipeline::exchange_frame_count(1) == 1);
    frame_pipeline::clear(0);
    seq0 = seq1 = 0;
    CHECK(!frame_pipeline::copy_http_frame_if_newer(0, seq0, out));
    CHECK(frame_pipeline::copy_http_frame_if_newer(1, seq1, out) && out == light);
    seq2 = 0;
    CHECK(frame_pipeline::copy_http_frame_if_newer(2, seq2, third) && third == middle);
    frame_pipeline::clear(1);
    seq2 = 0;
    CHECK(frame_pipeline::copy_http_frame_if_newer(2, seq2, third) && third == middle);
    CHECK(frame_pipeline::http_subscriber_count(2) == 1);
    frame_pipeline::http_subscriber_remove(2);
    CHECK(frame_pipeline::http_subscriber_count(2) == 0);
    frame_pipeline::http_subscriber_remove(0);
    CHECK(frame_pipeline::http_subscriber_count(0) == 0);
    CHECK(frame_pipeline::http_subscriber_count(1) == 1);
    // Concurrent consumers arriving/leaving must not lose the retained subscriber.
    std::vector<std::thread> threads;
    for (int i = 0; i < 8; ++i) threads.emplace_back([] {
        for (int n = 0; n < 1000; ++n) {
            frame_pipeline::http_subscriber_add(1);
            frame_pipeline::http_subscriber_remove(1);
        }
    });
    for (auto &t : threads) t.join();
    CHECK(frame_pipeline::http_subscriber_count(1) == 1);
    frame_pipeline::http_subscriber_remove(1);
    std::puts("PASS: channel isolation, frame counts, clear, preview, subscriber concurrency");
    return 0;
}
