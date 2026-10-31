#ifndef M3U8_CORE_H
#define M3U8_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 复刻 Java M3u8.removeMinorityUrl。
 * 返回 1：成功，out 为过滤后的完整 m3u8 内容，*adCount 为移除的切块数。
 * 返回 0：无需/无法处理（调用方应回退到 Java 的正则去广告 get()）。
 */
int m3u8_remove_minority(const char *content, const char *tsUrlPre,
                         char *out, size_t outCap, int *adCount);

#ifdef __cplusplus
}
#endif

#endif /* M3U8_CORE_H */