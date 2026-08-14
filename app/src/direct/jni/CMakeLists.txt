# Adapted from RikkaApps/Shizuku (manager/src/main/jni/CMakeLists.txt), Apache License 2.0.
# See THIRD_PARTY_NOTICES.md. Only the `adb` target (SPAKE2 pairing JNI shim) is needed here —
# unlike Shizuku's own manager app, mTTD's `direct` flavor doesn't spawn a starter process, so
# the `libshizuku.so` starter target (misc/selinux/cgroup helpers) is intentionally not ported.
cmake_minimum_required(VERSION 3.22)

project("mttd_direct")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    add_definitions(-DDEBUG)
endif ()

find_package(boringssl REQUIRED CONFIG)

add_library(adb SHARED adb_pairing.cpp)

# cxx::cxx(libc++) 의도적으로 안 씀 — adb_pairing.cpp 는 STL 컨테이너를 전혀 안 쓰고
# (malloc/free, C 스타일 struct 뿐) libc++ 프리팹 헤더의 stdint.h 가 이 NDK/Windows 조합에서
# -isystem 으로 주입될 때 include_next 체인이 깨져 uint8_t 등이 안 잡히는 문제가 있었다.
target_link_libraries(adb log boringssl::crypto_static)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET adb POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libadb.so")
endif ()
