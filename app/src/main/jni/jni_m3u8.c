/*
 * jni_m3u8.c — JNI 桥接层：把 m3u8_core.c 的过滤函数暴露给 Java。
 * 类：com.github.tvbox.osc.bbox.util.M3u8Native
 * 方法：native String nativeRemoveMinority(String content, String tsUrlPre, int[] adCount)
 *        返回 NULL 表示无法处理（回退 Java）；jad[0] 回填被移除的切块数。
 *
 * 1.0.14 修复：
 *   - 输出缓冲改为按需 malloc（原为 64KB 固定栈缓冲，大清单直接回退 Java）。
 *     上界推导：过滤只置空行不增长；增长仅来自"相对路径补前缀"（域名过滤模式
 *     每行最多补一次 tsUrlPre，#EXT-X-KEY 的 URI 替换同前缀上界），
 *     故 out ≤ len(content) + nLines*(len(ts)+2) + 4KB 余量，分配失败安全回退。
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include "m3u8_core.h"

JNIEXPORT jstring JNICALL
Java_com_github_tvbox_osc_bbox_util_M3u8Native_nativeRemoveMinority(
        JNIEnv *env, jclass clazz, jstring jcontent, jstring jts, jintArray jad) {
    const char *content = jcontent ? (*env)->GetStringUTFChars(env, jcontent, NULL) : NULL;
    const char *ts = jts ? (*env)->GetStringUTFChars(env, jts, NULL) : NULL;
    if (content == NULL || ts == NULL) {
        if (content) (*env)->ReleaseStringUTFChars(env, jcontent, content);
        if (ts) (*env)->ReleaseStringUTFChars(env, jts, ts);
        return NULL;
    }

    /* 计算输出上界：原文长 + 行数×(前缀+引号开销) + 余量 */
    size_t contentLen = strlen(content);
    size_t tsLen = strlen(ts);
    size_t nLines = 1;
    for (const char *p = strchr(content, '\n'); p != NULL; p = strchr(p + 1, '\n')) {
        nLines++;
    }
    size_t cap = contentLen + nLines * (tsLen + 2) + 4096;

    char *buf = (char *) malloc(cap);
    int adc = 0;
    int r = 0;
    if (buf != NULL) {
        r = m3u8_remove_minority(content, ts, buf, cap, &adc);
    }

    (*env)->ReleaseStringUTFChars(env, jcontent, content);
    (*env)->ReleaseStringUTFChars(env, jts, ts);

    if (jad != NULL) {
        jint v = (jint) adc;
        (*env)->SetIntArrayRegion(env, jad, 0, 1, &v);
    }
    if (r == 0 || buf == NULL) {
        free(buf);
        return NULL; /* 回退 Java */
    }
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}
