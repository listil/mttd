# Adapted from RikkaApps/Shizuku (manager/src/main/jni/CMakeLists.txt), Apache License 2.0.
# See THIRD_PARTY_NOTICES.md. Only the `adb` target (SPAKE2 pairing JNI shim) is needed here —
# unlike Shizuku's own manager app, mTTD's `direct` flavor doesn't spawn a starter process, so
# the `libshizuku.so` starter target (misc/selinux/cgroup helpers) is intentionally not ported.
cmake_minimum_required(VERSION 3.22)

project("mttd_direct")

set(CMAKE_CXX_STANDARD 17)

# 안드로이드 15+ "16KB 페이지 크기" 요구사항 — ELF LOAD 세그먼트가 16KB 경계로 정렬 안 되면
# 실기기(디버그 빌드)에서 "Android 앱 호환성" 경고 배너가 뜬다(developer.android.com/16kb-page-size).
# 이 프로젝트가 직접 컴파일하는 두 타겟(adb, libmttd_starter.so)에만 적용 가능 — boringssl/conscrypt
# 등 프리빌드 AAR로 받는 나머지 .so는 그 라이브러리가 자체적으로 이 플래그를 달고 나와야 한다.
add_link_options(-Wl,-z,max-page-size=16384)

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

# 실행 파일인데 이름을 lib*.so 로 짓는다 — Shizuku 의 실제 CMakeLists.txt(libshizuku.so 타겟)와
# 동일한 트릭. AGP 의 네이티브 라이브러리 패키징이 lib*.so 패턴만 앱의 nativeLibraryDir 로 담아주기
# 때문에, 진짜 공유 라이브러리가 아니라 standalone 실행 파일이어도 이렇게 이름 붙여야 APK 안에
# 실행 가능한 형태로 들어간다. mttd_starter.cpp 는 std::string 을 쓰므로(adb 타겟과 달리) NDK
# 기본 libc++(런타임 링크, org.lsposed.libcxx 프리팹 아님 — 그 프리팹만 stdint.h 문제가 있었다)
# 로 링크된다.
add_executable(libmttd_starter.so mttd_starter.cpp)
target_link_libraries(libmttd_starter.so log)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET libmttd_starter.so POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libmttd_starter.so")
endif ()
