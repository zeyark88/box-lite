/*
 * jni_m3u8.c — JNI 桥接层：把 m3u8_core.c 的过滤函数暴露给 Java。
 * 类：com.github.tvbox.osc.bbox.util.M3u8Native
 * 方法：native int[] removeMinority(String content, String tsUrlPre)
 *        返回 {adCount, 0}；结果为 NULL 时表示无法处理（回退 Java）。
 */
#include <jni.h>
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

    char buf[65536];
    int adc = 0;
    int r = m3u8_remove_minority(content, ts, buf, sizeof(buf), &adc);

    (*env)->ReleaseStringUTFChars(env, jcontent, content);
    (*env)->ReleaseStringUTFChars(env, jts, ts);

    if (jad != NULL) {
        jint v = (jint)adc;
        (*env)->SetIntArrayRegion(env, jad, 0, 1, &v);
    }
    if (r == 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}