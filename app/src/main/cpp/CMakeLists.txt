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

add_library(${CMAKE_PROJECT_NAME} SHARED
    jni_bridge.cpp
    uvc_engine.cpp
    stream_negotiate.cpp
    frame_pipeline.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    android
    log
    PkgConfig::libusb
    $<IF:$<TARGET_EXISTS:LibUVC::UVCStatic>,LibUVC::UVCStatic,LibUVC::UVCShared>
)
