#pragma once

namespace http_mjpeg_server {

/** Start (or restart) HTTP MJPEG server on 0.0.0.0:port. 0 = ok. */
int start(int port);

void set_channel_order(const int *slots, int count);

void stop();

bool is_running();

int port();

int client_count();

}  // namespace http_mjpeg_server
