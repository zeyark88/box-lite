# box（Jade box）源码分析报告

> 分析对象：`https://github.com/mlabalabala/box`（TVBox 多源 fork，横屏 TV 版，目标支持 Android 4.4）
> 分析日期：2026-10-31
> 定位：作为「自定义发行版」的前置分析。先改 bug、再谈语言优化与低版本适配。

---

## 0. 一句话结论

这是一个 **Java 写的 Android TV 应用**，588 个文件（330 Java），核心卖点是「多仓/多源/多线路」订阅 + `quickjs`（C 原生 JS 引擎）跑爬虫。它**已经天然支持 Android 4.1（minSdk 16）**，比"支持 4.4"还低一档，所以"低版本"不是它的问题，**真正的痛点是：代码里有若干真实 bug + 签名密钥裸提交的安全问题 + 依赖偏重导致老机内存压力大**。

---

## 1. 项目结构

```
app/       应用主模块（Java，多源解析/直播/点播/播放/字幕）
player/    播放器模块（ExoPlayer / IJK 封装）
quickjs/   C 原生 JS 引擎（跑爬虫脚本），已 native 化
Release/   预置 app.apk 产物
.github/   CI 构建工作流（assembleRelease）
```

- 语言：Java（+ `quickjs` 的 C 原生层）。
- `minSdkVersion 16` / `targetSdkVersion 26` / `compileSdk 30`，`multiDexEnabled true`。
- 多源解析链路：`StoreApiConfig`（多仓订阅）→ `ApiConfig`（线路配置）→ `SuperParse`/`JsonParallel`（并行解析）→ `JsSpider`（quickjs）→ `M3u8`（去广告）。

---

## 2. 发现的 Bug（按严重度）

### 🔴 高危

| # | 文件:行 | 问题 | 后果 | 建议修复 |
|---|---------|------|------|----------|
| H1 | `app/build.gradle` | **签名 keystore 与密码提交进仓库**：`BUNNY.jks` + `storePassword "000624"` / `keyPassword "426000"` 明文写死 | 任何人可打出"同签名"假包做更新劫持；对自定义发行版是致命的 | 移除 keystore/密码，改为从 `local.properties` 读（本项目已改） |
| H2 | `util/MD5.java:31,54,61` | **静态共享 `MessageDigest sDigest` 线程不安全**：`update()`+`digest()` 在一个实例上被多线程并发调用会交错，算出错误哈希 | 并发解析时校验/摘要结果随机出错 | 每次 `MessageDigest.getInstance("MD5")` 新建，或用 ThreadLocal |
| H3 | `util/AES.java:16-17` | **判空顺序错误**：`key.trim().length()` 在 `key == null` 检查**之前**执行 | 传 null key 直接 NPE（应先判空再 `trim()`） | 把 null 判断提到最前 |

### 🟠 中危（多源/订阅逻辑）

| # | 文件:行 | 问题 | 影响 |
|---|---------|------|------|
| M1 | `api/StoreApiConfig.java:157-188` | `MutiUrl()` 新建 `history/map` 局部变量，**每次订阅都会覆盖**已持久化的 `API_MAP/API_NAME_HISTORY`，丢历史线路 | 用户已加的多线路被一次订阅清掉 |
| M2 | `api/StoreApiConfig.java:92-105` | 注释称"默认添加 30 条、多出放弃"，但循环**没有 break**，仓库里多少条就加多少条，`storeNameHistory` 无上限增长 | Hawk 存储无限膨胀 |
| M3 | `util/parser/SuperParse.java:20,27-64` | `configs/jsonJx/webJx` 都是类级 **static 可变状态**：`configs` 只由**首次调用**的第一个 `jx` 构建、永不重建；`doJsonJx(url)` 读共享 `jsonJx` | 并发解析多个视频时，`jsonJx` 被互相覆盖；切源后配置不更新 |
| M4 | `util/parser/SuperParse.java:114-116` | `flagWebJx.put(flag, webJx)` 只在非空时写入，若之前非空、本次变空，旧的还留在里面 | 残留脏数据 |
| M5 | `update/component/CustomUpdateParser.java:29` | `String.valueOf(getExternalFilesDir(...))` 为 null 时变成字符串 `"null"`，绕过 `CustomUpdateChecker:72` 的 `TextUtils.isEmpty` 判空 | 更新缓存目录写到 `"null"` 路径 |

### 🟡 边界 / 健壮性

| # | 文件:行 | 问题 |
|---|---------|------|
| L1 | `util/M3u8.java:155` | `keyUrl.charAt(0)` 在 keyUrl 可能为空字符串时越界抛 `StringIndexOutOfBoundsException`（畸形 m3u8 的 `URI=""`） |
| L2 | `util/M3u8.java:78-82` | `ilast-4` 硬编码假定扩展名恒 4 位，非 `.ts`（如 `.m2ts`/`.m4s`）分组出错 |
| L3 | `util/M3u8.java:102,155,172,188,196` | `indexOf('/', 9)` 硬编码跳 9 个字符来"跳过协议"，对 `https://`（8 位）实际是跳过第 1 个域名字符，属 hack 式处理 |
| L4 | `AppUtils.java.bak` | 把 `.bak` 备份文件提交进源码，应删除 |

### 🟢 规范性 / 兼容性

| # | 位置 | 问题 | 建议 |
|---|------|------|------|
| G1 | `app/build.gradle` | `versionName '0.0.0'` / `versionCode 27` 硬编码不标准 | 改为发版语义化版本（已改） |
| G2 | `app/build.gradle:DEBUG_APP` | 任务硬编码 `d:/Knowledge/module/adb/adb.exe`（Windows 专属） | 删除/改通用（已删除） |
| G3 | `app/build.gradle` | release 只打 `armeabi-v7a` | 老 x86 盒子装不了；建议 `armeabi-v7a`+`arm64-v8a`+`x86`（已改，视包体积取舍） |
| G4 | `.github/workflows/GEN.yml` | 只有 `workflow_dispatch`，无版本号/Release 发布步骤 | 加版本号、出 Release、产物校验（见骨架） |
| G5 | `targetSdk 26` + `cronet`/`okhttp3.12` 依赖 | 对低内存老设备偏重，多 dex | 见"改造路线"削依赖 |

---

## 3. 架构层面的问题（会影响"更高效语言"决策）

1. **双 HTTP 栈并存**：既有 `com.lzy.net:okgo/okhttp`，又有 `util/urlhttp/*` 自研请求封装。重复、难维护，是改造成本点。
2. **CPU 热路径都是纯 Java**：`M3u8` 去广告正则、`SuperParse`/`JsonParallel` 解析、`RSAEncrypt`/`AES` 加解密。这些都是老设备上最耗时的部分，**是最值得 native 化的目标**。
3. **JS 引擎已是 C 原生**（`quickjs`），这个"高效"已经做了一半——只需把 Java 侧热路径也 native 化即可整体收益。

---

## 4. "更高效的语言"推荐（结合 minSdk16 + 兼容市面源）

**结论：推荐「保留 Java 壳 + 热路径 JNI/C++ 化」，而不是整仓换语言。**

理由（必须卡住两个约束）：

- **必须支持 minSdk 16（Android 4.1/4.4）**：
  - ❌ Flutter/Dart：Flutter 最低 minSdk 21，**直接不满足**，排除。
  - ❌ Kotlin：能跑在 API 16，但它是 JVM 语言，**不降内存/不更快**，只改善可维护性，不解决"老设备卡"。
  - ✅ 只有 **C/C++（经 JNI）** 能：产物仍是 `.apk`，`minSdk` 不变，却把最费 CPU/内存的热路径压到 native，老设备/低内存盒子上收益最直接。
- **必须兼容市面 TVBox 数据源**：市面的源（蜘蛛脚本、`quickjs` 爬虫、`jsx` 配置、m3u8 去广告、多仓订阅）全部绑定 **Java/TVBox 协议生态**。整栈换语言（如 Rust）会把这套生态全部断掉，**源兼容性 = 0**。所以只能"换掉计算密集的爪牙，保住壳与协议层"。

因此推荐路线（渐进、可回退、风险低）：

| 阶段 | 动作 | 目标 | 收益 |
|------|------|------|------|
| P0 | 修 H1-H3 + M1-M5 全部 bug | 先拿到稳定基线 | 消除崩溃/串数据/安全风险 |
| P1 | `M3u8` 去广告 + `JsonParallel` 解析 → JNI/C++（`ndk` 已引入 C） | 播放入口提速、省内存 | 老盒最立竿见影 |
| P2 | `RSAEncrypt`/`AES`/`MD5` → 用 NDK 的 BoringSSL（OpenSSL 系）替代纯 Java 实现 | 解密提速、去线程安全风险 | 秒开+安全 |
| P3 | 减依赖：移除 `util/urlhttp` 自研栈，统一 `okhttp`；按需去掉 `cronet`/`brotli` 等对 4.x 无用的重型依赖 | 减小 dex/包体积、降内存 | 老 1G 内存盒更稳 |

> 每个阶段都保持 `minSdk 16`，均可单独合入、单独回滚；不会破坏任何源协议。

详见 `ROADMAP.md`。

---

## 5. 附带发现：源仓库本身已"可运行低版本"

- minSdk 16 = Android 4.1，**无需改造就支持 4.4**。README 强调"支持安卓4.4"是宣传口径，实际更宽。
- 唯一的低版本风险点：release 只打 `armeabi-v7a`，老 x86 设备（某些国产盒/平板）装不上——已在骨架里改为三 ABI（包体会变大，可再按需回退到 `armeabi-v7a`+`arm64-v8a`）。

---

## 6. 下一步交付物

- [x] `docs/ANALYSIS.md`（本报告）
- [x] `docs/ROADMAP.md`（更高效化改造路线，分阶段可回滚）
- [x] `docs/DISTRIBUTION.md`（发行版签名/版本/发布规范）
- [x] `app/build.gradle`（清签名硬编码、规范版本、三 ABI、删 Windows adb 任务）
- [x] `README.md` / `CHANGELOG.md` / `LICENSE(AGPL-3.0)` / `.gitignore`
- [ ] 代码修复落库（H1-H3、M1-M5、L1-L3）——见 `ROADMAP.md`，待确认后执行