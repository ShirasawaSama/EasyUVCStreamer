vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO libuvc/libuvc
    REF "v${VERSION}"
    SHA512 3d0834c250f2e33f0eaedb6738631fd646a6b61e3195c472c904569f7b1369944e048165acccae90c145032abc3610d8f4331d23e83bcb8626c60c40324cdf89
    HEAD_REF master
    PATCHES
        build_fix.patch
        android_iso_fix.patch
        android_hotunplug_fix.patch
)

if (VCPKG_LIBRARY_LINKAGE STREQUAL "dynamic")
    set(BUILD_TARGET "Shared")
else()
    set(BUILD_TARGET "Static")
endif()

set(PLATFORM_OPTIONS)
if(VCPKG_TARGET_IS_ANDROID)
    list(APPEND PLATFORM_OPTIONS
        -DLIBUVC_NUM_TRANSFER_BUFS=4
        -DLIBUVC_PACKETS_PER_TRANSFER_MAX=8
    )
endif()

vcpkg_find_acquire_program(PKGCONFIG)
vcpkg_cmake_configure(
    SOURCE_PATH ${SOURCE_PATH}
    OPTIONS
        -DPKG_CONFIG_EXECUTABLE="${PKGCONFIG}"
        -DCMAKE_BUILD_TARGET=${BUILD_TARGET}
        -DBUILD_EXAMPLE=OFF
        -DBUILD_TEST=OFF
        ${PLATFORM_OPTIONS}
)
vcpkg_cmake_install()

vcpkg_cmake_config_fixup(CONFIG_PATH lib/cmake/${PORT})
vcpkg_fixup_pkgconfig()

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")

vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE.txt")

file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/usage" DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")
