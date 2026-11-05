# v1.0.21 (2026-11-05)

「星辰影音」投屏全链路升级（涵盖 v1.0.17 ~ v1.0.21 演进，自 v1.0.16 纯净版基线）：

- **品牌更名**：应用更名「星辰盒」→「**星辰影音**」；关于页显示「星辰影音 v<版本号>」
- **投屏发现引擎（根治扫不到设备）**：
  - MulticastLock（Android 组播锁，多数设备收不到 SSDP 的根因）
  - 4 种 M-SEARCH 并发组播 + 每 1.5s 重发（兼容各厂商应答习惯）
  - **本机单播探测**（支持同机装投屏接收 App 自扫）
  - **/24 网段逐 IP 单播直扫**（彻底绕开组播/IGMP/隔离不可靠）
- **投屏入口三端接入**：详情页「投 屏」、全屏播放控制栏「投屏」、预览小窗控制栏「投屏」（EventBus，预览时也可直接投）
- **投屏控制面板**（投屏中再点投屏呼出）：暂停/继续、快进/快退 30 秒、断开投屏、切换设备、自动回填当前进度（标准 UPnP Pause/Play/Seek/GetPositionInfo/Stop，零第三方库）
- **投屏直链预处理**：投屏前替电视探测直链——展平 302 跳转再投（提高可播率）；403/401 防盗链、404 失效时给出明确提示（不再只看到电视端 -1011）
- 说明：投屏倍速受 DLNA 协议限制（DMR 普遍仅支持 Speed=1）；带防盗链的源电视无法裸拉，属协议限制，可换线路或本机播放

# v1.0.16 (2026-11-05)

**纯净版**：移除全部内置隐藏外联源地址

- 清空 `DEFAULT_STORE_API_URL`（原内置 `gitea.com/404ynnub` 源仓库，启动自动拉源列表的根源）
- 清空 `DEFAULT_API_URL`（原内置 `www.饭太硬.net` 默认线路）
- 不再预置默认 EPG 节目单地址（`epg.51zmt.top` / `epg.112114.xyz`）
- 源/直播/EPG/网盘全部由用户自行配置，**首次启动为全空白，零内置外联**
- 保留（功能性、非隐藏源）：GitHub 下载代理选项历史（ghproxy 等，仅下拉选项）、源 jar 下载代理 `DOMAIN_NAME_PROXY`、DoH 域名解析选项列表（需手动开启才连接）、CrossWalk 运行时按需下载

# v1.0.15 (2026-11-05)

- **图标修复**：修复部分 TV launcher 应用图标显示黑块 —— icon 改为白底位图（drawable/app_icon），TV banner 改为位图 app_banner_b（原 banner 为透明 shape XML）
- **权限收敛**：14 → 9 项（移除定位/电话状态/安装包/GET_TASKS/挂载文件系统，tools:node remove）
- **移除 AppsActivity**（应用管理）；首页应用名点击改为「切换站点」
- **首页精简**：隐藏日期；顶部新增「网盘」入口（云图标）
- **新增 DLNA 投屏**：详情页「投 屏」按钮，标准 UPnP 控制点（SSDP M-SEARCH 发现 + SOAP SetAVTransportURI/Play），零第三方库
- **新增网盘**：WebDAV 浏览/播放（坚果云/Nextcloud/Alist/群晖），TV 大字焦点列表，账号配置持久化
- **详情页**：下载/投屏等按钮间距统一

# Changelog

本项目为 `mlabalabala/box` 的自定义发行版。遵循语义化版本：`主.次.修订`。

## [1.0.14] — 2026-11-03 P1 原生化全链路打通 + 解析热路径重构

**打通 m3u8 原生过滤全链路**（深挖发现三处断点，导致原生路径此前从未真正生效）：
- 修复 `M3u8.purify()` 调用 `M3u8Native.removeMinority` **参数顺序颠倒**（content/tsUrlPre 对调），此前即使放开开关也永远返回 null 回退 Java。
- `app/build.gradle` 接入 `externalNativeBuild`（CMake 指向 `src/main/jni/CMakeLists.txt`）——此前 `libm3u8native.so` 根本没有编译进 APK，`System.loadLibrary` 恒失败。
  **新增构建前置**：需 NDK + CMake（如 `sdkmanager "ndk;21.4.7075529" "cmake;3.10.2"`）。
- `app/build.gradle` 显式 `buildToolsVersion "30.0.1"`（AGP 4.2.2 默认 30.0.3，但国内镜像普遍缺 30.0.3 包；30.0.1 与 compileSdk 30 完全兼容，保证镜像受限环境可复现构建）。
- `quickjs/build.gradle` compileSdk 由 33 统一为 30（与 app/player 一致；该模块只用预编译 jniLibs，不引用 API 31+ 接口，免去 SDK 33 依赖）。
- `CMakeLists.txt` 移除无效的 `set(ANDROID_ABI ...)` 死代码（ABI 实际由 Gradle 侧控制）。
- `jni_m3u8.c` 输出缓冲由 64KB 固定栈改为按需 `malloc`（上界 = 原文长 + 行数×(前缀+2) + 4KB），大清单不再直接回退；分配失败安全回退 Java。
- `M3u8Native.useNative()` 放开为 `true`：C 实现忠实复刻 + 样例单测通过 + 全失败路径自动回退 Java，最坏情况=回到纯 Java 行为。真机回归方法见类注释与 `docs/ROADMAP.md`。

**`JsonParallel` 重构（解析热路径性能，P2 前置）**：
- `OkHttpClient` / 线程池改为共享常驻单例（原每次 `parse` 新建 client + 5 线程池再销毁，连接与线程零复用，老盒子开销显著）。
- `futures`/`calls` 局部化：消除并发调用间互相 clear/cancel 的竞态；拿到首个结果后按 `Call` 精确取消本批，不再 `cancelAll()` 误伤并发批次。
- `completionService.take()` 无限等待改为 15s 整体超时：jx 全挂死不再永久阻塞解析线程。
- `Response` 显式 `close`，取消/异常路径不再泄漏连接。
- 修复 `getReqHeader`：`cat_ext` 为末位参数时 `end=-1` 导致 substring 越界异常 + `newUrl` 拼回整个 url。

**`SuperParse` 加固**：
- `doJsonJx(url)` 修复 `jsonJx` 为 null 时 `LOG` 直接 NPE；快照拷贝防遍历中被并发重写。
- `flagWebJx` 改 `ConcurrentHashMap`（`PlayActivity`/`PlayFragment` 各自解析池并发 put 的丢数据风险）。
- 注：`jsonJx` 静态竞态彻底修复需贯穿调用链重构，仍留在 P2（见 `docs/ROADMAP.md`）。

**`StoreApiConfig` 崩溃修复**：
- 订阅地址返回非 JSON（网关错误页/空内容）时，Gson 异常抛在 OkGo 回调线程直接崩溃 → `success`/`MutiUrl` 整体兜底，只提示不崩。
- 当前线路名不在 `storeMap` 时 `get()` 返回 null 直接传给 OkGo → 回退默认仓库地址。

**文档**：README 产物段对齐实际（包名 `tv.of.eu.box.daily`、版本指向 CHANGELOG）；ROADMAP P1 进展更新为"已打通、待真机回归"。

产物 `release/box-v1.0.14.apk`（versionCode 15，仅 `armeabi-v7a`）。

## [1.0.13] — 2026-10-31 关于页加 GitHub 地址
- "关于"对话框加入本发行版 GitHub：`https://github.com/zeyark88/box-lite`（保留上游出处）。
- 已核验地址在 APK 编译布局内。
- 产物 `release/box-v1.0.13.apk`（18MB，versionCode 14）。

## [1.0.12] — 2026-10-31 清理图标资源·核对源码
- 核对：manifest 图标 = `@mipmap/ic_launcher`（全密度不透明蓝紫渐变位图，alpha 全=255），无运行时改图标代码。
- 清理残留：删除旧白色 `app_icon`、废弃 `ic_launcher_background/foreground`；保留 `drawable/app_icon.png`（播放页用到）为蓝紫图标。
- 产物 `release/box-v1.0.12.apk`（19MB，versionCode 13）。

## [1.0.11] — 2026-10-31 修复图标黑块（彻底·全不透明位图）
- **彻底不用自适应图标**（部分电视/盒子启动器连自适应背景都不认 → 仍垫黑）。移除 `mipmap-anydpi-v26`，改用**全不透明位图**做图标：整块蓝紫渐变+白盒，**任何像素都不透明**，任何启动器都无法垫出黑块。
- 主图标已核验：APK 内为全不透明 PNG。
- 产物 `release/box-v1.0.11.apk`（18MB，versionCode 12）。

## [1.0.10] — 2026-10-31 修复图标黑块（自适应背景）
- **修复电视类设备黑块**：自适应图标 `<background>` 之前用 **PNG 位图**，部分安卓盒子/电视启动器不认位图当背景 → 显示成黑底。已改为 **XML 渐变 shape**（蓝紫对角渐变），任何启动器都正常。
- 前景（白盒+播放键）不变。
- 产物 `release/box-v1.0.10.apk`（18MB，versionCode 11）。

## [1.0.9] — 2026-10-31 修复图标黑块（终版）
- **彻底消除黑块**：legacy 图标改为**全不透明**（整块蓝紫渐变+白盒播放键，无任何透明角），任何启动器/任何背景都不再露黑；自适应图标（透明前景+不透明渐变背景）保持。
- 原版在部分设备黑块是上游通病（图标有透明区被启动器垫黑）；本版从根上规避。
- 产物 `release/box-v1.0.9.apk`（18MB，versionCode 10）。

## [1.0.8] — 2026-10-31 修复图标(续)
- **修复老设备(<安卓8) legacy 图标显示异常**：此前 legacy 渐变误用亮粉色，已统一为与自适应一致的**蓝紫渐变**+白盒播放键，圆角透明。
- 产物 `release/box-v1.0.8.apk`（18MB，versionCode 9）。
- 提示：Android 启动器会缓存旧图标，装新版前请先卸载旧包。

## [1.0.7] — 2026-10-31 修复图标黑块（自适应图标）
- **修复部分设备图标显示异常（黑块）**：改用**标准自适应图标**。
- 新增透明前景（白盒+播放三角）+ 蓝紫渐变背景 + `mipmap-anydpi-v26/ic_launcher.xml`，并补全 legacy `mipmap-*/ic_launcher.png`；manifest `android:icon/@roundIcon` 指向 `@mipmap/ic_launcher`。
- 产物 `release/box-v1.0.7.apk`（18MB，versionCode 8）。

## [1.0.6] — 2026-10-31 精简（去除冗余依赖）
- 移除 `com.google.net.cronet:cronet-okhttp` + `org.chromium.net:cronet-embedded`（代码 0 引用）→ **APK 20MB→18MB**，原生库 libcronet 消失。
- 复核 `juniversalchardet`（CharsetUtils/SubtitleLoader 在用）→ 保留；`brotli`（自研 urlhttp 栈在用）→ 保留。

## [1.0.5] — 2026-10-31 更换默认壁纸
- 按截图样式生成新的默认壁纸 `drawable/app_bg.png`：**左下暖橙黄 → 右上翠青/浅蓝的柔和对角线性渐变**（1440×958，轻磨砂雾感）。
- 已确认打进 APK（`BaseActivity` 默认 `R.drawable.app_bg`）。
- 产物 `release/box-v1.0.5.apk`（20MB，versionCode 6）。

## [1.0.4] — 2026-10-31 深层 bug 加固
- **`server/InputRequestProcess`**：远程推流/遥控 POST 参数缺失时 `.trim()` 会 NPE，已统一防空（恶意/残缺请求不再崩本地服务）。
- **字幕解析加固**：`subtitle/model/Time.java` 时间解析加 try-catch（畸形字幕不再 `NumberFormatException`/越界崩溃）；`Style.getRGBValue` 数值颜色分支加 try-catch（畸形色值返回 null 而非崩溃）。
- 复核确认无崩点：`JsonParallel` 的 `cat_ext` 解析已在 try 内；`StringUtils` 的 trim/substring 有长度边界保护。
- 备注（低危）：`LivePlayActivity` 的 `static SimpleDateFormat` 仅 UI 线程使用，暂不改动。

## [1.0.3] — 2026-10-31 定制化（禁用更新 + 改名 + 换图标）
- **禁用自动更新并删除更新地址**：`URL.GITHUB_VERSION_PATH` 置空；`App.initXUpdate()` 停用；设置"检查更新"改为提示"已禁用自动更新"（不再联网）。
- **改名**：`app_name` 由 `Jade` → `星辰盒`。
- **换图标**：用生成的新图标替换 `app_icon`（drawable 及 hdpi/xhdpi/xxhdpi/xxxhdpi 全密度）。
- 产物 `release/box-v1.0.3.apk`（19MB，versionCode 4，minSdk16，`应用名=星辰盒`）。

## [1.0.2] — 2026-10-31 启动闪退（续）：关闭 R8 混淆
- `app/proguard-rules.pro` 为空，但 release 此前 `minifyEnabled true`，R8 会删掉 Room/Gson/quickjs 等反射用类 → release 启动即崩（debug 正常）。
- **release 关闭 `minify`（`minifyEnabled false`）**，消除 R8 误删风险。
- 产物 `release/box-v1.0.2.apk`（19.6MB，versionCode 3，minSdk16 / armeabi-v7a）。

## [1.0.1] — 2026-10-31 启动闪退修复
- **修复启动闪退**：原生库（p2p/quickjs/ijk/player 等）仅预编译 `armeabi-v7a`，此前误把 release `abiFilters` 放开为三 ABI，导致 arm64/x86 设备缺 so、启动即崩。已还原为仅 `armeabi-v7a`（arm64 设备以 32 位兼容运行）。
- 产物 `release/box-v1.0.1.apk`（15.2MB，versionCode 2，仅 `lib/armeabi-v7a`）。

## [1.0.0] — 2026-10-31 首个可构建发行版
- **构建成功**：`assembleDebug` / `assembleRelease` 全通过；产物 `release/box-v1.0.0.apk`（约 20MB，v1+v2 双签名，1 签名者）。
- 补齐完整源码（`res`、`AndroidManifest`、`player`、`quickjs`、gradle 包装、`app/libs`）。
- 修复 2 处影响编译的构建问题：`FlowLayout` 依赖 groupId 笔误、根 `build.gradle` 死仓库。
- 签名改为从 `local.properties` 读取（keystore 在 `~/box-keys/`，**勿入库**）。
- 内含 P0 bug 修复 + P1 m3u8 原生核心（默认关闭），见 `docs/`。

## [Unreleased] — 初始骨架
- 清理 `app/build.gradle`：移除明文 key 与密码、改为 `local.properties` 读取。
- 版本号改为显式管理（`versionCode`/`versionName`）；`abiFilters` 放开为 `armeabi-v7a + arm64-v8a + x86`。
- 删除 Windows 专属 `DEBUG_APP`（硬编码 adb 路径）任务。
- 新增 `docs/`：`ANALYSIS.md`、`ROADMAP.md`、`DISTRIBUTION.md`；规范化 `README.md`、`CHANGELOG.md`、`.gitignore`。

### Added（P1 原生化，见 `docs/ROADMAP.md`）
- `app/src/main/jni/m3u8_core.c`：纯 C 复刻 `M3u8` 去广告过滤核心，已 `cc` 编译 + 样例自测通过。
- `app/src/main/jni/jni_m3u8.c` + `CMakeLists.txt` + `util/M3u8Native.java`：JNI 桥接。
- `M3u8.purify()` 接入原生路径（默认关闭 `useNative()`，待真机回归后放开）。

### Fixed（P0 bug 修复，见 `docs/ANALYSIS.md`）
- fix(H2): `MD5.java` 改为每次新建 `MessageDigest`，消除共享实例的线程安全问题。
- fix(H3): `AES.java` 先判空再 `trim()`，避免 null key NPE。
- fix(M1): `StoreApiConfig.MutiUrl` 合并既有历史，不再覆盖已存线路。
- fix(M2): `StoreApiConfig` 多仓/线路历史设 30 条上限，防止无限增长。
- fix(M3): `SuperParse` 每次按当前 `jx` 重建 `configs`，修复切源后配置不更新。
- fix(L1): `M3u8` 处理 `#EXT-X-KEY` 时对空 `URI=""` 的越界防护。
- chore: 删除源码中的 `AppUtils.java.bak`。

### 待办
- 编译验证需补齐完整源码（`res/`、`AndroidManifest.xml`、`player/`、`quickjs/`、预置 `.so`/`.apk`）。
- `SuperParse` 的 `jsonJx` 静态竞态完整修复（需贯穿调用链）待源码补全后执行。