#include "http_mjpeg_server.h"

#include "frame_pipeline.h"
#include "log.h"

#include "hv/HttpServer.h"
#include "hv/hthread.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace http_mjpeg_server {
namespace {

constexpr const char *kBoundary = "frame";
constexpr const char *kStreamPath = "/stream.mjpg";

std::mutex g_mu;
std::unique_ptr<hv::HttpService> g_service;
std::unique_ptr<hv::HttpServer> g_server;
std::atomic<bool> g_running{false};
std::atomic<int> g_port{0};
std::atomic<int> g_clients{0};

struct SubscriberGuard {
    SubscriberGuard() {
        frame_pipeline::http_subscriber_add();
        g_clients.fetch_add(1, std::memory_order_relaxed);
    }
    ~SubscriberGuard() {
        frame_pipeline::http_subscriber_remove();
        g_clients.fetch_sub(1, std::memory_order_relaxed);
    }
};

void write_mjpeg_stream(const HttpContextPtr &ctx) {
    auto guard = std::make_shared<SubscriberGuard>();
    auto writer = ctx->writer;

    writer->Begin();
    writer->WriteStatus(HTTP_STATUS_OK);
    writer->WriteHeader("Content-Type",
                        "multipart/x-mixed-replace; boundary=frame");
    writer->WriteHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    writer->WriteHeader("Pragma", "no-cache");
    writer->WriteHeader("Access-Control-Allow-Origin", "*");
    writer->EndHeaders();

    uint64_t last_seq = 0;
    std::vector<uint8_t> jpeg;
    jpeg.reserve(128 * 1024);

    while (writer->isConnected()) {
        if (!writer->isWriteComplete()) {
            hv_delay(1);
            continue;
        }
        if (!frame_pipeline::copy_http_frame_if_newer(last_seq, jpeg)) {
            hv_delay(5);
            continue;
        }

        std::ostringstream hdr;
        hdr << "--" << kBoundary << "\r\n"
            << "Content-Type: image/jpeg\r\n"
            << "Content-Length: " << jpeg.size() << "\r\n"
            << "\r\n";
        const std::string header = hdr.str();

        if (writer->WriteBody(header) < 0) break;
        if (writer->WriteBody(reinterpret_cast<const char *>(jpeg.data()),
                              static_cast<int>(jpeg.size())) < 0) {
            break;
        }
        if (writer->WriteBody("\r\n", 2) < 0) break;
    }

    guard.reset();
    if (writer->isConnected()) {
        writer->close();
    }
}

void setup_routes(hv::HttpService &service) {
    service.GET("/", [](HttpRequest *req, HttpResponse *resp) {
        (void) req;
        resp->content_type = TEXT_HTML;
        resp->body =
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                "<title>UVC MJPEG</title></head><body style=\"margin:0;background:#111;color:#eee;"
                "font-family:sans-serif;text-align:center\">"
                "<h1 style=\"margin:12px\">SimpleUVCStreamer</h1>"
                "<img src=\"/stream.mjpg\" style=\"max-width:100%;height:auto\" alt=\"stream\">"
                "</body></html>";
        return 200;
    });

    service.GET("/status", [](HttpRequest *req, HttpResponse *resp) {
        (void) req;
        resp->content_type = APPLICATION_JSON;
        std::ostringstream ss;
        ss << "{\"running\":" << (g_running.load() ? "true" : "false")
           << ",\"port\":" << g_port.load()
           << ",\"clients\":" << g_clients.load()
           << ",\"subscribers\":" << frame_pipeline::http_subscriber_count()
           << ",\"stream\":\"" << kStreamPath << "\"}";
        resp->body = ss.str();
        return 200;
    });

    service.GET(kStreamPath, [](const HttpContextPtr &ctx) {
        std::thread([ctx]() {
            write_mjpeg_stream(ctx);
        }).detach();
        return HTTP_STATUS_UNFINISHED;
    });
}

}  // namespace

int start(int port) {
    if (port <= 0 || port > 65535) {
        LOGE("http_mjpeg_server: invalid port %d", port);
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_mu);
    if (g_running.load() && g_port.load() == port && g_server) {
        LOGI("http_mjpeg_server: already running on %d", port);
        return 0;
    }

    if (g_server) {
        g_server->stop();
        g_server.reset();
        g_service.reset();
        g_running.store(false);
    }

    auto service = std::make_unique<hv::HttpService>();
    setup_routes(*service);

    auto server = std::make_unique<hv::HttpServer>();
    server->registerHttpService(service.get());
    server->setHost("0.0.0.0");
    server->setPort(port);
    server->setThreadNum(1);

    int ret = server->start();
    if (ret != 0) {
        LOGE("http_mjpeg_server: start failed port=%d ret=%d", port, ret);
        return ret != 0 ? ret : -2;
    }

    g_service = std::move(service);
    g_server = std::move(server);
    g_port.store(port);
    g_running.store(true);
    LOGI("http_mjpeg_server: listening on 0.0.0.0:%d", port);
    return 0;
}

void stop() {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_server) {
        g_server->stop();
        g_server.reset();
    }
    g_service.reset();
    g_running.store(false);
    g_port.store(0);
    LOGI("http_mjpeg_server: stopped");
}

bool is_running() {
    return g_running.load(std::memory_order_relaxed);
}

int port() {
    return g_port.load(std::memory_order_relaxed);
}

int client_count() {
    return g_clients.load(std::memory_order_relaxed);
}

}  // namespace http_mjpeg_server
