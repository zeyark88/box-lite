# Box Lite — TVBox 多源发行版

一个以 [mlabalabala/box](https://github.com/mlabalabala/box)（Jade box）为基线的自定义发行版。
目标：**多源/多仓/多线路**、**支持 Android 4.4+（minSdk 16）**、**兼容市面 TVBox 数据源**。

> 本仓库为 AGPL-3.0 开源的 fork，沿用上游许可，禁止闭源再分发。

## 特性
- 多源 / 多仓 / 多线路订阅（`StoreApiConfig`）
- 直播源 m3u8 与 tvbox 格式、`quickjs` 跑爬虫脚本
- 触屏设备快捷进设置；设置页「检测更新」小红点（**永不强制更新**）
- 低版本：`minSdkVersion 16`（Android 4.1 起，覆盖 4.4）

## 目录
- `app/` 主模块源码
- `docs/` — [`ANALYSIS.md`](docs/ANALYSIS.md)（bug 分析）、[`ROADMAP.md`](docs/ROADMAP.md)（改造路线）、[`DISTRIBUTION.md`](docs/DISTRIBUTION.md)（发行规范）
- `.github/workflows/` CI 构建

## 构建
```bash
# 需 JDK8 + Android SDK（本机已配好 portable 工具链：dist-tools/）
# 签名 keystore 放 ~/box-keys/box-release.jks，密码在 dist-tools/keystore-password.txt（勿入库）
export JAVA_HOME=$PWD/dist-tools/jdk
export PATH="$JAVA_HOME/bin:$PWD/dist-tools/gradle-6.7.1/bin:$PATH"
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
# 产物：release/box-v<版本>.apk
```

### 已发布产物

- **最新 [v1.0.16](https://github.com/zeyark88/box-lite/releases/tag/v1.0.16)**：`box-v1.0.16.apk`（`tv.of.eu.box.daily`，versionCode 17，minSdk16/targetSdk26，仅 `armeabi-v7a`），发布说明与 SHA256 校验值见 [Releases](https://github.com/zeyark88/box-lite/releases)
- v1.0.15：图标黑块修复 / 权限收敛(14→9) / DLNA 投屏 / 网盘 WebDAV / 移除应用管理 / 首页精简
- v1.0.16：**纯净版**（移除全部内置源地址，首启零内置外联，源/直播/EPG 全部自行配置）
- ⚠️ v1.0.14 起启用新正式签名（原密钥遗失），无法覆盖升级 1.0.13 及更早版本，需卸载后全新安装（详见该版 Release 说明）
- 历史各版本修复明细见 [`CHANGELOG.md`](CHANGELOG.md)
- keystore：`~/box-keys/box-release.jks`（**务必备份，丢失无法再发版**；路径与密码勿入库）

## 版权与协议
- 本发行版：AGPL-3.0
- 上游：`mlabalabala/box`（AGPL-3.0）→ `CatVodTVOfficial/TVBoxOSC`
- 数据源仅作示例，与版权内容无关。

> 测试可能不够，有 bug 欢迎提 issue。