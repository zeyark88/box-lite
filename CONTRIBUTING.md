# Contributing（贡献指南）

感谢对本发行版的关注。请遵守以下规范，保证协作质量与"标准 GitHub 仓库"体感。

## 分支与提交
- 使用 `main` 作为集成分支；功能用短命分支，合回前先 `git pull --rebase`。
- 提交信息建议：`type(scope): 简述`，说明"为什么改"，例如
  `fix(M3u8): #EXT-X-KEY 空 URI 越界防护（L1）`。
- **一次改动一个提交**，不要把无关改动混在一起。

## 代码风格
- Java：沿用上游风格（4 空格缩进、类注释保留作者署名）。
- 原生 C（`app/src/main/jni/`）：`-Wall -Wextra` 零告警；改动后跑 `./tests/m3u8_core_test.sh`。
- 任何对"去广告/解析"等播放链路的行为改动，**默认关停**，并在 PR 说明回归方法。

## 安全红线
- **禁止**提交 `.jks/.keystore/密码/签名配置` 到仓库（见 `SECURITY.md`）。
- 禁止提交包含明文密钥的构建文件。

## 发版
按 `docs/DISTRIBUTION.md`：升 `versionCode`+`versionName`、出 Release、附 `SHA256SUMS`。

## 文档
- 行为/算法改动请在 `docs/ROADMAP.md` 与 `CHANGELOG.md` 记录。
- 新增能力附最小可复现样例/测试。