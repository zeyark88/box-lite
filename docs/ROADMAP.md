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

## P1 进展（1.0.14 已全链路打通）
- 原生核心 `app/src/main/jni/m3u8_core.c`：忠实移植 Java `removeMinorityUrl`/`maxPercent`，
  纯 C 无依赖，已在宿主机用 `cc` 编译并通过样例单测（广告段被移除、多数占比<0.8 回退）。
- JNI 桥接 `app/src/main/jni/jni_m3u8.c` + `CMakeLists.txt` + Java 入口 `util/M3u8Native.java`。
- **1.0.14 打通三处断点**（此前原生路径从未真正生效）：
  1. `M3u8.purify()` 调用 `M3u8Native.removeMinority` 参数顺序颠倒（content/tsUrlPre 对调）——已修复；
  2. `app/build.gradle` 未接 `externalNativeBuild`，`libm3u8native.so` 从未编译进 APK——已接入
     （新增构建前置：NDK + CMake，如 `sdkmanager "ndk;21.4.7075529" "cmake;3.10.2"`）；
  3. `CMakeLists.txt` 的 `set(ANDROID_ABI ...)` 是无效死代码——已移除（ABI 由 Gradle 控制）。
- `jni_m3u8.c` 输出缓冲由 64KB 固定栈改为按需 malloc（上界=原文长+行数×(前缀+2)+4KB），大清单不再回退。
- `M3u8Native.useNative()` 已放开（true）：任何失败路径（未加载/异常/无法处理）自动回退 Java，
  最坏情况等于回到纯 Java 行为。
- 待办（真机回归后关闭）：同一批真实 m3u8 分别开关 useNative，比对过滤结果与 currentAdCount 一致；
  回归通过后此条可删除。

## 1.0.14 顺带落地（P2 前置的 Java 层热路径优化）
- `JsonParallel`：OkHttpClient/线程池改共享常驻单例（原每次 parse 新建，连接与线程零复用）；
  futures/calls 局部化消除并发竞态；take() 无限等待改 15s 整体超时；按 Call 精确取消本批；
  Response 显式 close；修复 `cat_ext` 为末位参数时的 substring 越界。
- `SuperParse`：`doJsonJx(url)` 的 jsonJx null NPE 修复 + 快照拷贝；`flagWebJx` 改 `ConcurrentHashMap`。
  （jsonJx 静态竞态的彻底修复仍留给 P2 重构：需把 jsonJx 作为参数贯穿调用链。）
- `StoreApiConfig`：订阅返回非 JSON 时崩溃兜底；线路名不在 storeMap 时 null URL 回退默认仓库。

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