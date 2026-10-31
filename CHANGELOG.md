# Changelog

本项目为 `mlabalabala/box` 的自定义发行版。遵循语义化版本：`主.次.修订`。

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