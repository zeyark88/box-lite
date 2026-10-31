# Security（安全）

本仓库是 AGPL-3.0 开源发行版，安全问题请遵循最小公开原则。

## 报告安全漏洞
请通过 GitHub **私有安全公告（Private vulnerability reporting）** 或项目 Issue（标注 SEC）报告，不要公开贴 exploit。

## 已知要点（务必遵守）
1. **签名密钥严禁入库**：`.jks/.keystore`/签名密码只能放本机 `local.properties`（已 `.gitignore`）。
   上游 `mlabalabala/box` 曾把 keystore 与密码提交进仓库，本项目已移除。任何人拿到 keystore 都能伪签同名包做**更新劫持**。
2. **下载安装**：只从官方 GitHub Release 安装，核对 `SHA256SUMS.txt` 与签名。
3. **数据源**：本应用仅聚合第三方数据源；源内容版权归内容方。请勿用于传播侵权/违法内容。
4. **更新模块**：本项目"检测更新"永远不强制更新；请勿在后添加强制更新逻辑。
5. **native 层**：`app/src/main/jni/` 的 C 代码改动需通过 `tests/m3u8_core_test.sh` 且 `-Wall -Wextra` 零告警，防止内存问题。

## 依赖
保持依赖版本明确（见 `app/build.gradle`），升级时关注已知 CVE。