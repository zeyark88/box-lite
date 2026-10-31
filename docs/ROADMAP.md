# 改造路线（Roadmap）——更高效 + 低版本

目标约束（两个**不可妥协**的硬条件）：
1. `minSdkVersion 16`（Android 4.1 起，覆盖 4.4 与绝大多数老盒子/平板）；
2. **兼容市面 TVBox 数据源**（蜘蛛脚本、quickjs 爬虫、jsx 配置、m3u8 去广告、多仓订阅）。

由于 Flutter（minSdk 21）不满足条件 1、整栈换语言会破坏条件 2，**推荐路线 = 保留 Java 壳 + 热路径 JNI/C++ 化**。

## 阶段总览

| 阶段 | 内容 | 验收标准 | 可回滚 |
|------|------|----------|--------|
| P0 | 修复 H1-H3、M1-M5、L1-L3 全部 bug | 单测/手工验证通过，无 NPE、无串数据 | 单项回滚 |
| P1 | `M3u8` 去广告过滤 → JNI/C | 播放广告拦截耗时显著下降、内存下降 | 是 |
| P2 | `JsonParallel`/`SuperParse` 解析 → JNI/C++ | 解析并发更稳、更快 | 是 |
| P3 | `RSA/AES/MD5` 改用 NDK 内置加密（BoringSSL） | 解密提速、消除线程安全风险 | 是 |
| P4 | 削依赖：去 `util/urlhttp` 自研栈统一 `okhttp`；按需裁剪 `cronet`/`brotli` | dex/包体积减小、老内存稳定 | 是 |

## P1 进展（已落地部分）
- 原生核心 `app/src/main/jni/m3u8_core.c`：忠实移植 Java `removeMinorityUrl`/`maxPercent`，
  纯 C 无依赖，已在宿主机用 `cc` 编译并通过样例单测（广告段被移除、多数占比<0.8 回退）。
- JNI 桥接 `app/src/main/jni/jni_m3u8.c` + `CMakeLists.txt` + Java 入口 `util/M3u8Native.java`。
- `M3u8.purify()` 已接原生路径，但 **默认关闭**（`M3u8Native.useNative()` 返回 false），
  需真机回归（对比 Java 去广告结果一致）后再放开，避免影响播放。
- 待办：完整源码补全后接入 NDK 构建、跑全量回归、再放开 `useNative()`。

## P0 修复清单（改前先备份，逐条改、逐条编译）

1. **H1** 签名：已在 `app/build.gradle` 改为 `local.properties` 读取，删除 `BUNNY.jks` 与明文密码。
2. **H2** `MD5.java`：改为 `MessageDigest.getInstance("MD5")` 每次新建，或 `ThreadLocal<MessageDigest>`。
3. **H3** `AES.java`：把 `key == null` 判断提到 `key.trim()` 之前。
4. **M1** `StoreApiConfig.MutiUrl`：改为读取既有 `API_MAP/API_NAME_HISTORY` 合并，而不是覆盖。
5. **M2** `StoreApiConfig.Subscribe`：给 `storeNameHistory` 设上限（如 30），超出截断。
6. **M3** `SuperParse`：去掉类级 static 可变状态，改为每次 `parse()` 用局部对象 + 显式传参，或加 synchronized。
7. **M5** `CustomUpdateParser`：判空 `getExternalFilesDir` 后再 `setApkCacheDir`。
8. **L1** `M3u8`：`keyUrl.charAt(0)` 前先判空。

## P1-P3 说明（native 化的位置与做法）

- `app/src/main/jni/` 新增 C 模块，`player`/`quickjs` 已走 NDK，直接复用同一 `CMake`/`ndk-build` 环境。
- JNI 只暴露纯函数接口（输入字符串/字节，输出结果），不碰 UI/线程/网络，做到"换掉实现、接口不变"。
- `abiFilters` 已放开 `armeabi-v7a, arm64-v8a, x86`，native 库同步编译三 ABI。

## 依赖裁剪建议（P4）

- 删除 `com.google.net.cronet` + `cronet-embedded`（对 4.x 与低内存盒收益小、体积大）。
- 删除 `util/urlhttp/*` 自研栈，统一走 `okhttp`（只保留必要封装）。
- 评估 `org.brotli:dec` 是否需要（多数源走 gzip，可裁剪）。

> 每一阶段合入前：`./gradlew assembleRelease` 编译通过 + 手工开一个源、播一段片、测一次多仓订阅。