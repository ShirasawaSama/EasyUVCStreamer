#pragma once

#include <jni.h>
#include <string>

namespace uvc_engine {

int init();
int open_device(int channel, int fd);
void stop_stream(int channel);
void close_device(int channel);
int start_stream(int channel, int width, int height, int fps, const char *format);
int get_frame_count(int channel);
std::string get_resolutions(int channel);

void set_preview_enabled(int channel, bool enabled);
jbyteArray take_latest_frame(int channel, JNIEnv *env);

}  // namespace uvc_engine
