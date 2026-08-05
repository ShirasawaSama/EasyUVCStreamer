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

# Declares the project name.
project(simpleuvcstreamer)

# Find dependencies using vcpkg
find_package(libuvc CONFIG REQUIRED)

# libuvcConfig.cmake internally uses pkg_check_modules to find libusb-1.0
# and defines PkgConfig::libusb as an imported target.
# We can link to it directly if we need libusb symbols.

add_library(${CMAKE_PROJECT_NAME} SHARED
    simpleuvcstreamer.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    android
    log
    PkgConfig::libusb
    $<IF:$<TARGET_EXISTS:LibUVC::UVCStatic>,LibUVC::UVCStatic,LibUVC::UVCShared>
)
