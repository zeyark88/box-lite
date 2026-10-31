# Changelog

本项目为 `mlabalabala/box` 的自定义发行版。遵循语义化版本：`主.次.修订`。

## [Unreleased] — 初始骨架
- 清理 `app/build.gradle`：移除明文 keystore 与密码、改为 `local.properties` 读取。
- 版本号改为语义化（`versionCode`/`versionName` 显式管理）。
- `abiFilters` 由仅 `armeabi-v7a` 放开为 `armeabi-v7a + arm64-v8a + x86`。
- 删除 Windows 专属 `DEBUG_APP`（硬编码 adb 路径）任务。
- 新增 `docs/`：`ANALYSIS.md`、`ROADMAP.md`、`DISTRIBUTION.md`。
- 新增规范化 `README.md`、`CHANGELOG.md`、`.gitignore`。
- 待办（P0）：`MD5` 线程安全、`AES` 判空顺序、`StoreApiConfig` 订阅覆盖历史等——见 `docs/ANALYSIS.md`。