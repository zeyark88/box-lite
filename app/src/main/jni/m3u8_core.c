/*
 * m3u8_core.c — 纯 C 实现的 m3u8 "去少数线路/去广告" 过滤热路径。
 *
 * 忠实移植自 box(mlabalabala/box) 的 Java 实现
 *   app/src/main/java/.../util/M3u8.java 的 removeMinorityUrl / maxPercent。
 * 仅做纯字符串处理，不依赖 JNI / Android，可在宿主机用 cc 编译并单测。
 *
 * 接口：m3u8_remove_minority(content, tsUrlPre, out, outCap, *adCount)
 *   - 返回 0：无需/无法处理（调用方回退 Java 的 get() 正则去广告）。
 *   - 返回 1：out 中为过滤后的完整内容，*adCount 为被移除的切块数。
 *   - 语义与 Java 保持一(致)（含 substring(0, lastDot-4)、indexOf('/',9)、max<0.8 等）。
 *
 * 编译与自测：cc -DM3U8_TEST m3u8_core.c -o m3u8_core && ./m3u8_core
 */
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#if defined(_MSC_VER)
#define strdup _strdup
#endif

/* ---------- string -> int 计数表（复刻 Java HashMap<String,Integer>） ---------- */
typedef struct { char **keys; int *counts; int size; int cap; } StrIntMap;

static void map_init(StrIntMap *m) {
    m->cap = 8; m->size = 0;
    m->keys = (char **)malloc(sizeof(char *) * m->cap);
    m->counts = (int *)malloc(sizeof(int) * m->cap);
}
static void map_free(StrIntMap *m) {
    if (!m->keys) return;
    for (int i = 0; i < m->size; i++) free(m->keys[i]);
    free(m->keys); free(m->counts);
    m->keys = NULL; m->counts = NULL; m->size = 0;
}
static int map_get(const StrIntMap *m, const char *k) {
    for (int i = 0; i < m->size; i++) if (strcmp(m->keys[i], k) == 0) return m->counts[i];
    return 0;
}
static void map_inc(StrIntMap *m, const char *k) {
    for (int i = 0; i < m->size; i++) if (strcmp(m->keys[i], k) == 0) { m->counts[i]++; return; }
    if (m->size == m->cap) {
        m->cap *= 2;
        m->keys = (char **)realloc(m->keys, sizeof(char *) * m->cap);
        m->counts = (int *)realloc(m->counts, sizeof(int) * m->cap);
    }
    m->keys[m->size] = (char *)strdup(k);
    m->counts[m->size] = 1;
    m->size++;
}
static double map_max_percent(const StrIntMap *m) {
    int maxTimes = 0, total = 0;
    for (int i = 0; i < m->size; i++) { if (m->counts[i] > maxTimes) maxTimes = m->counts[i]; total += m->counts[i]; }
    /* 复刻 Java：total==0 时 0.0/0.0 = NaN，NaN<0.8 为 false */
    return total == 0 ? (0.0 / 0.0) : ((double)maxTimes / (double)total);
}
static void map_find_max(const StrIntMap *m, const char **outKey, int *outTimes) {
    int maxTimes = 0; const char *maxKey = "";
    for (int i = 0; i < m->size; i++) if (m->counts[i] > maxTimes) { maxTimes = m->counts[i]; maxKey = m->keys[i]; }
    *outKey = maxKey; *outTimes = maxTimes;
}

/* ---------- 基础字符串工具（复刻 Java String 常用操作） ---------- */
static int starts_with(const char *s, const char *pre) {
    return strncmp(s, pre, strlen(pre)) == 0;
}
/* Java line.lastIndexOf('.')，找不到返回 -1 */
static int last_index_of_dot(const char *s) {
    const char *p = strrchr(s, '.');
    return p ? (int)(p - s) : -1;
}
/* Java line.indexOf(c, from)，从 from 起找第一个 c，找不到返回 -1 */
static int index_of_from(const char *s, char c, int from) {
    int n = (int)strlen(s);
    for (int i = from; i < n; i++) if (s[i] == c) return i;
    return -1;
}
/* 分配 s[0,end) 的子串 */
static char *substr(const char *s, int end) {
    char *r = (char *)malloc((size_t)(end + 1));
    memcpy(r, s, (size_t)end);
    r[end] = '\0';
    return r;
}
/* 拼接 a+b（替换用） */
static char *concat(const char *a, const char *b) {
    size_t la = strlen(a), lb = strlen(b);
    char *r = (char *)malloc(la + lb + 1);
    memcpy(r, a, la); memcpy(r + la, b, lb + 1);
    return r;
}
/* 拼接 a[0,end) + b */
static char *concat_sub(const char *a, int end, const char *b) {
    size_t la = (size_t)end, lb = strlen(b);
    char *r = (char *)malloc(la + lb + 1);
    memcpy(r, a, la); memcpy(r + la, b, lb + 1);
    return r;
}
/* 把 line 中 "URI=\"old\"" 整段替换为 "URI=\"new\""（复刻 Java line.replace） */
static char *replace_uri_tag(char *line, const char *old, const char *newv) {
    const char *needle = "URI=\"";
    const char *p = strstr(line, needle);
    if (!p) return line;
    char *r = (char *)malloc(strlen(line) + strlen(newv) + 16);
    size_t hlen = strlen(needle), olen = strlen(old), nlen = strlen(newv);
    /* 复制 needle 前 */
    memcpy(r, line, (size_t)(p - line));
    char *w = r + (p - line);
    memcpy(w, needle, hlen); w += hlen;
    memcpy(w, newv, nlen); w += nlen;
    /* 跳过旧值及其右引号 */
    const char *after = p + hlen + olen;
    if (*after == '"') after++;
    strcpy(w, after);
    free(line);
    return r;
}

/* ---------- 行切分（按 "\n" 或 "\r\n"） ---------- */
typedef struct { char **lines; int size; } LineArr;
static void split_lines(const char *content, const char *sep, LineArr *out) {
    size_t seplen = strlen(sep);
    int n = 1; const char *q = content;
    while ((q = strstr(q, sep))) { n++; q += seplen; }
    out->lines = (char **)malloc(sizeof(char *) * (size_t)n);
    out->size = 0;
    const char *cur = content;
    for (;;) {
        const char *next = strstr(cur, sep);
        size_t len = next ? (size_t)(next - cur) : strlen(cur);
        out->lines[out->size++] = substr(cur, (int)len);
        if (!next) break;
        cur = next + seplen;
    }
}
static void free_lines(LineArr *a) {
    for (int i = 0; i < a->size; i++) free(a->lines[i]);
    free(a->lines);
}

/* =====================================================================
 * 主逻辑：复刻 Java removeMinorityUrl
 * =================================================================== */
int m3u8_remove_minority(const char *content, const char *tsUrlPre,
                         char *out, size_t outCap, int *adCount) {
    *adCount = 0;
    if (content == NULL || content[0] == '\0' || !starts_with(content, "#EXTM3U")) return 0;

    const char *sep = strstr(content, "\r\n") ? "\r\n" : "\n";
    LineArr arr; split_lines(content, sep, &arr);
    if (arr.size == 0) { free_lines(&arr); return 0; }

    StrIntMap m; map_init(&m);
    /* 阶段1：按"去文件后缀前缀"计数 substring(0, lastIndexOf('.')-4) */
    for (int i = 0; i < arr.size; i++) {
        const char *line = arr.lines[i];
        if (line[0] == '\0' || line[0] == '#') continue;
        int ilast = last_index_of_dot(line);
        if (ilast <= 4) continue;
        char *pre = substr(line, ilast - 4);
        map_inc(&m, pre); free(pre);
    }
    if (m.size <= 1) { map_free(&m); free_lines(&arr); return 0; }

    int domainFiltering = 0;
    if (map_max_percent(&m) < 0.8) {
        map_free(&m); map_init(&m);
        for (int i = 0; i < arr.size; i++) {
            const char *line = arr.lines[i];
            if (line[0] == '\0' || line[0] == '#') continue;
            if (!starts_with(line, "http://") && !starts_with(line, "https://")) {
                map_free(&m); free_lines(&arr); return 0;
            }
            int ifirst = index_of_from(line, '/', 9);
            if (ifirst <= 0) continue;
            char *pre = substr(line, ifirst);
            map_inc(&m, pre); free(pre);
        }
        if (m.size <= 1) { map_free(&m); free_lines(&arr); return 0; }
        if (map_max_percent(&m) < 0.8) { map_free(&m); free_lines(&arr); return 0; }
        int allDomainsExceedThreshold = 1;
        for (int i = 0; i < m.size; i++) if (m.counts[i] <= 15) { allDomainsExceedThreshold = 0; break; }
        if (allDomainsExceedThreshold) { map_free(&m); free_lines(&arr); return 0; }
        domainFiltering = 1;
    }

    const char *maxKey; int maxTimes;
    map_find_max(&m, &maxKey, &maxTimes);
    if (maxTimes == 0) { map_free(&m); free_lines(&arr); return 0; }

    int tsHostIdx = index_of_from(tsUrlPre, '/', 9); /* 复刻 indexOf('/',9) */

    int dealtExtXKey = 0;
    for (int i = 0; i < arr.size; i++) {
        char *line = arr.lines[i];
        if (!dealtExtXKey && starts_with(line, "#EXT-X-KEY")) {
            const char *needle = "URI=\"";
            int start = (int)(strstr(line, needle) ? strstr(line, needle) - line : -1);
            if (start != -1) {
                int s2 = start + (int)strlen(needle);
                int end = index_of_from(line, '"', s2);
                char *keyUrl = (end != -1) ? substr(line + s2, end - s2) : NULL;
                if (keyUrl && keyUrl[0] &&
                    !starts_with(keyUrl, "http://") && !starts_with(keyUrl, "https://")) {
                    char *newKey = NULL;
                    if (keyUrl[0] == '/') {
                        if (tsHostIdx > 0) newKey = concat_sub(tsUrlPre, tsHostIdx, keyUrl);
                    } else {
                        newKey = concat(tsUrlPre, keyUrl);
                    }
                    if (newKey) {
                        arr.lines[i] = replace_uri_tag(line, keyUrl, newKey);
                        free(newKey);
                    }
                }
                free(keyUrl);
            }
            dealtExtXKey = 1;
        }

        /* 用当前（可能已替换的）行重新判断 */
        line = arr.lines[i];
        if (line[0] == '\0' || line[0] == '#') continue;

        if (!domainFiltering) {
            if (starts_with(line, maxKey)) {
                if (!starts_with(line, "http://") && !starts_with(line, "https://")) {
                    char *newLine = NULL;
                    if (line[0] == '/') {
                        if (tsHostIdx > 0) newLine = concat_sub(tsUrlPre, tsHostIdx, line);
                    } else {
                        newLine = concat(tsUrlPre, line);
                    }
                    if (newLine) { arr.lines[i] = newLine; free(line); }
                }
            } else {
                if (i > 0 && arr.lines[i - 1][0] != '\0' && arr.lines[i - 1][0] == '#') {
                    free(arr.lines[i - 1]); arr.lines[i - 1] = strdup("");
                }
                free(arr.lines[i]); arr.lines[i] = strdup("");
                (*adCount)++;
            }
        } else {
            const char *absLine = line;
            int absOwned = 0;
            if (!starts_with(absLine, "http://") && !starts_with(absLine, "https://")) {
                if (absLine[0] == '/') {
                    if (tsHostIdx > 0) { absLine = concat_sub(tsUrlPre, tsHostIdx, absLine); absOwned = 1; }
                } else {
                    absLine = concat(tsUrlPre, absLine); absOwned = 1;
                }
            }
            if (absLine) {
                int ifirst = index_of_from(absLine, '/', 9);
                char *domain = (ifirst > 0) ? substr(absLine, ifirst) : strdup(absLine);
                int cnt = map_get(&m, domain);
                if (strcmp(domain, maxKey) == 0 || cnt > 15) {
                    free(arr.lines[i]); arr.lines[i] = strdup(absLine);
                } else {
                    if (i > 0 && arr.lines[i - 1][0] != '\0' && arr.lines[i - 1][0] == '#') {
                        free(arr.lines[i - 1]); arr.lines[i - 1] = strdup("");
                    }
                    free(arr.lines[i]); arr.lines[i] = strdup("");
                    (*adCount)++;
                }
                free(domain);
            }
            if (absOwned) free((char *)absLine);
        }
    }

    /* 拼接（复刻 TextUtils.join(sep, lines)） */
    size_t need = 1;
    for (int i = 0; i < arr.size; i++) need += strlen(arr.lines[i]) + (i > 0 ? strlen(sep) : 0);
    if (need > outCap) { map_free(&m); free_lines(&arr); return 0; }
    char *w = out; *w = '\0';
    for (int i = 0; i < arr.size; i++) {
        if (i > 0) { memcpy(w, sep, strlen(sep)); w += strlen(sep); }
        size_t len = strlen(arr.lines[i]); memcpy(w, arr.lines[i], len); w += len;
    }
    *w = '\0';

    map_free(&m); free_lines(&arr);
    return 1;
}

/* =====================================================================
 * 自测入口：cc -DM3U8_TEST m3u8_core.c -o m3u8_core && ./m3u8_core
 * =================================================================== */
#ifdef M3U8_TEST
static int run_case(const char *name, const char *content, const char *ts, const char *expectSub) {
    char out[8192];
    int adc = -1;
    int r = m3u8_remove_minority(content, ts, out, sizeof(out), &adc);
    if (r == 0) {
        printf("[%s] r=0 (跳过/无法处理)  ad=%d\n", name, adc);
        return 0;
    }
    int ok = expectSub == NULL ? 1 : (strstr(out, expectSub) != NULL);
    printf("[%s] r=1 ad=%d len=%zu  %s\n", name, adc, strlen(out), ok ? "OK" : "!!未命中");
    if (!ok) printf("    输出片段: %s\n", out);
    return ok ? 0 : 1;
}

int main(void) {
    /* 样例1：正常多段视频（无广告，全部同一前缀）-> r=0 走 Java */
    run_case("normal-same-prefix",
        "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/v/seg0.ts\nhttp://a.com/v/seg1.ts\n#EXT-X-ENDLIST\n",
        "http://a.com/v/", NULL);

    /* 样例2：1 个少数广告段 + 5 个正片（多数占比 5/6=83% >= 0.8）-> 广告被移除 */
    {
        char out[8192]; int adc = -1;
        const char *c =
            "#EXTM3U\n"
            "#EXTINF:5.0\nhttp://ad.com/x/adv0.ts\n"
            "#EXTINF:10.0\nhttp://a.com/v/seg0.ts\n"
            "#EXTINF:10.0\nhttp://a.com/v/seg1.ts\n"
            "#EXTINF:10.0\nhttp://a.com/v/seg2.ts\n"
            "#EXTINF:10.0\nhttp://a.com/v/seg3.ts\n"
            "#EXTINF:10.0\nhttp://a.com/v/seg4.ts\n"
            "#EXT-X-ENDLIST\n";
        int r = m3u8_remove_minority(c, "http://a.com/v/", out, sizeof(out), &adc);
        int ok = (r == 1) && (adc == 1) && (strstr(out, "seg1.ts") != NULL) && (strstr(out, "adv0.ts") == NULL);
        printf("[ad-injected-5v1] r=%d ad=%d %s\n", r, adc, ok ? "OK (广告已移除)" : "!!不符");
        if (!ok) printf("    输出: %s\n", out);
    }

    /* 样例3：单域直接 m3u8（无深路径，前缀全同）-> 0 走 Java */
    run_case("single-domain-ts",
        "#EXTM3U\n#EXTINF:10.0\nhttp://a.com/seg0.ts\nhttp://a.com/seg1.ts\n#EXT-X-ENDLIST\n",
        "http://a.com/", NULL);

    return 0;
}
#endif