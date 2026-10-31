# Changelog

本项目为 `mlabalabala/box` 的自定义发行版。遵循语义化版本：`主.次.修订`。

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