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

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("simpleuvcstreamer")

find_package(libuvc CONFIG REQUIRED)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    simpleuvcstreamer.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    android
    log
    $<IF:$<TARGET_EXISTS:LibUVC::UVCStatic>,LibUVC::UVCStatic,LibUVC::UVCShared>
)
