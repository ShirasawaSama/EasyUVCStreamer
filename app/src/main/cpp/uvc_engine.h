#pragma once

#include <jni.h>
#include <string>

namespace uvc_engine {

int init();
int open_device(int fd);
void stop_stream();
void close_device();
int start_stream(int width, int height, int fps, const char *format);
int get_frame_count();
std::string get_resolutions();

void set_preview_enabled(bool enabled);
jbyteArray take_latest_frame(JNIEnv *env);

}  // namespace uvc_engine
