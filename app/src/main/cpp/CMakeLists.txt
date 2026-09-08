cmake_minimum_required(VERSION 3.22.1)

# [VCPKG] Map Android ABI to vcpkg Triplet
if(ANDROID_ABI AND NOT VCPKG_TARGET_TRIPLET)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(VCPKG_TARGET_TRIPLET "arm-android" CACHE STRING "")
    elseif(ANDROID_ABI STREQUAL "x86")
        set(VCPKG_TARGET_TRIPLET "x86-android" CACHE STRING "")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "")
    endif()
endif()

# [VCPKG] Help vcpkg find the NDK by setting the environment variable
if(ANDROID_NDK AND NOT DEFINED ENV{ANDROID_NDK_HOME})
    set(ENV{ANDROID_NDK_HOME} "${ANDROID_NDK}")
endif()

project(simpleuvcstreamer)

find_package(libuvc CONFIG REQUIRED)
find_package(libhv CONFIG REQUIRED)
find_package(libjpeg-turbo CONFIG REQUIRED)

# vcpkg Android builds libhv as static → imported target is hv_static (not hv).
if(TARGET hv_static)
    set(HV_TARGET hv_static)
elseif(TARGET hv)
    set(HV_TARGET hv)
elseif(TARGET libhv::hv)
    set(HV_TARGET libhv::hv)
else()
    message(FATAL_ERROR "libhv CMake target not found (expected hv_static or hv)")
endif()

if(TARGET libjpeg-turbo::turbojpeg-static)
    set(TURBOJPEG_TARGET libjpeg-turbo::turbojpeg-static)
elseif(TARGET libjpeg-turbo::turbojpeg)
    set(TURBOJPEG_TARGET libjpeg-turbo::turbojpeg)
else()
    message(FATAL_ERROR "libjpeg-turbo turbojpeg target not found")
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
    jni_bridge.cpp
    uvc_engine.cpp
    stream_negotiate.cpp
    frame_pipeline.cpp
    http_mjpeg_server.cpp
    jpeg_encode.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    android
    log
    PkgConfig::libusb
    $<IF:$<TARGET_EXISTS:LibUVC::UVCStatic>,LibUVC::UVCStatic,LibUVC::UVCShared>
    ${HV_TARGET}
    ${TURBOJPEG_TARGET}
)
