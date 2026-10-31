# 原生模块：m3u8 去广告过滤热路径（P1）
cmake_minimum_required(VERSION 3.10)
project(m3u8_native)

add_library(m3u8native SHARED
        jni_m3u8.c
        m3u8_core.c)

# 便于 native 层打日志
find_library(log-lib log)
target_link_libraries(m3u8native ${log-lib})

# 三 ABI（与 app/build.gradle 的 abiFilters 保持一致）
set(ANDROID_ABI armeabi-v7a arm64-v8a x86)