# 原生模块：m3u8 去广告过滤热路径（P1）
# 注意：ABI 由 Gradle 侧控制（app/build.gradle 的 externalNativeBuild.cmake.abiFilters
#       与 buildTypes.ndk.abiFilters，当前仅 armeabi-v7a，与 player/quickjs 预编译 .so 一致），
#       不要在此处 set(ANDROID_ABI ...)——那是 ndk-build 的输入变量，在 CMakeLists 里写无效。
cmake_minimum_required(VERSION 3.10)
project(m3u8_native)

add_library(m3u8native SHARED
        jni_m3u8.c
        m3u8_core.c)

# 便于 native 层打日志
find_library(log-lib log)
target_link_libraries(m3u8native ${log-lib})