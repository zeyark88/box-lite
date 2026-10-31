# 发行版规范（Distribution）

这是一个以 `mlabalabala/box`（TVBox 多源 fork，AGPL-3.0）为基础的**自定义发行版**。下列规范保证它符合"标准 GitHub 仓库与源码规范"。

## 1. 协议与署名
- 协议：**AGPL-3.0**（沿用上游，**不可**改闭源；fork 必须保留许可声明与版权）。
- 保留上游版权与 AGPL 声明，在你的 README 顶部注明"基于 XX 修改"。

## 2. 仓库结构（已按标准骨架搭好）
```
box-dist/
├─ app/               应用主模块（src/ 为业务源码；build.gradle 已清理签名/版本）
├─ docs/              分析报告、路线、发行规范
├─ .github/workflows/ CI 构建
├─ README.md
├─ CHANGELOG.md
├─ LICENSE             AGPL-3.0
├─ .gitignore
├─ build.gradle / settings.gradle / gradle.properties
```
> 注：受网络限制，本骨架仅带入**核心业务源码**（`app/src/main/java/.../bbox/*`）与构建文件；`res/`、`AndroidManifest.xml`、`player/`、`quickjs/`、预置 `.so` 与 `app.apk` 需在网络恢复后从上游补齐：`git clone --filter=blob:none` 后 `git checkout HEAD -- .`，或用 `curl` 下载完整 tar 包解出对应目录再并入。

## 3. 签名（务必遵守）
- **严禁**把 `.jks` 和密码提交进仓库（上游就因此泄漏了 keystore，本项目已移除）。
- 生成你自己的 keystore：
  ```bash
  keytool -genkey -v -keystore ~/keys/box-release.jks -alias box -keyalg RSA \
    -keysize 2048 -validity 10000
  ```
- 在 **本地 `local.properties`**（已被 `.gitignore` 忽略）写入：
  ```properties
  box.storeFile=/home/你/.keys/box-release.jks
  box.storePassword=你的密码
  box.keyAlias=box
  box.keyPassword=你的密码
  ```
- `app/build.gradle` 的 `release` 签名会自动读取；未配置时输出未签名包（不可安装）。

## 4. 版本号
- `app/build.gradle` 里 `versionCode`（int，单调递增）+ `versionName`（语义化，如 `1.0.0`）。
- 发一个版本就升一次 `versionCode`（> 上一版），否则老用户检测更新不生效。

## 5. 发布（Release 流程）
- CI：`.github/workflows/build.yml`（`assembleRelease`）→ 产物 `box-<version>.apk`。
- GitHub Release 命名：`v<versionName>`；附 `SHA256SUMS.txt` + 更新说明。
- 默认线路在 `constant/URL.java` 的 `DEFAULT_API_URL`，应用名在 `res/values/strings.xml`（上游文档）——补全 `res/` 后改。

## 6. 提交规范
- 一次改动一个提交，message 用中文/英文均可，但说明"为什么改"。
- `P0` 修 bug 请附对应 `docs/ANALYSIS.md` 编号（如 `fix(H2): MD5 改为线程安全实现`）。