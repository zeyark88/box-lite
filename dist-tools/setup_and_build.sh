#!/usr/bin/env bash
# 治本构建脚本：等三路后台下载完成后，解包 JDK8/Gradle/源码，执行 assembleRelease。
# 用法：bash dist-tools/setup_and_build.sh
set -uo pipefail

WORK="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$WORK/.." && pwd)"
WS="$(cd "$WORK/../.." && pwd)"     # 工作区根
# 后台下载实际落点（部分会落在工作区根，部分在 dist-tools）
SRC="/tmp/box-full.tar.gz"
JDK_TGZ="$WS/jdk8.tar.gz"
[ -f "$JDK_TGZ" ] || JDK_TGZ="$WORK/jdk8.tar.gz"
GRADLE_ZIP="$WS/gradle671.zip"
[ -f "$GRADLE_ZIP" ] || GRADLE_ZIP="$WORK/gradle671.zip"
OUT="$WORK/jdk"
GRADLE_DIR="$WORK/gradle-6.7.1"

echo "[1/4] 等待下载完成（后台续传中）..."
i=0
while [ $i -lt 600 ]; do
    ok=1
    [ -f "$SRC" ]        && gzip -t "$SRC" 2>/dev/null        || ok=0
    [ -f "$JDK_TGZ" ]    && gzip -t "$JDK_TGZ" 2>/dev/null   || ok=0
    [ -f "$GRADLE_ZIP" ] && unzip -tq "$GRADLE_ZIP" >/dev/null 2>&1 || ok=0
    [ $ok -eq 1 ] && break
    sleep 20; i=$((i+1))
done
[ $ok -eq 1 ] || { echo "超时：下载未完成，请稍后再跑"; exit 1; }
echo "  下载全部就绪。"

echo "[2/4] 解包源码"
rm -rf "$WORK/src-src"
mkdir -p "$WORK/src-src"
tar -xzf "$SRC" -C "$WORK/src-src" || { echo "tarball 解包失败"; exit 1; }

echo "[3/4] 解包 JDK8 + Gradle"
rm -rf "$OUT" "$GRADLE_DIR"
mkdir -p "$OUT"
tar -xzf "$JDK8_TGZ" -C "$OUT" --strip-components=1 || { echo "JDK8 解包失败"; exit 1; }
unzip -qo "$GRADLE_ZIP" -d "$WORK" || { echo "Gradle 解包失败"; exit 1; }
export JAVA_HOME="$OUT"
export PATH="$OUT/bin:$GRADLE_DIR/bin:$PATH"
"$OUT/bin/java" -version 2>&1 | head -1
"$GRADLE_DIR/bin/gradle" --version 2>&1 | grep -E 'Gradle|JVM' | head -3

echo "[4/4] 把上游源码并入项目并构建"
UP=$(ls -d "$WORK/src-src"/* | head -1)   # 解包后的顶层目录
# 补 root 下缺失的模块与构建文件（不覆盖本地已修复的 Java 源码）
for sub in player quickjs gradle gradlew gradlew.bat gradle.properties settings.gradle build.gradle; do
    if [ -e "$UP/$sub" ]; then cp -rf "$UP/$sub" "$PROJ/"; fi
done
# app 下：补齐缺失的 manifest / res / libs，以及缺失的 Java 源码（不覆盖本地已修复文件）
if [ -f "$UP/app/src/main/AndroidManifest.xml" ]; then mkdir -p "$PROJ/app/src/main"; cp -f "$UP/app/src/main/AndroidManifest.xml" "$PROJ/app/src/main/"; fi
[ -d "$UP/app/src/main/res" ] && cp -rf "$UP/app/src/main/res" "$PROJ/app/src/main/"
[ -d "$UP/app/libs" ] && cp -rf "$UP/app/libs" "$PROJ/app/"
# 合并上游 Java 源码中本地缺失的文件（保留已修复的 MD5/AES/StoreApiConfig/SuperParse/M3u8 等）
if [ -d "$UP/app/src/main/java" ]; then
    find "$UP/app/src/main/java" -type f | while read -r f; do
        rel="${f#"$UP"/app/src/main/java/}"
        dest="$PROJ/app/src/main/java/$rel"
        [ -e "$dest" ] || { mkdir -p "$(dirname "$dest")"; cp -f "$f" "$dest"; }
    done
fi
# app/src：只补齐缺的（manifest/res），保留已修复的 Java 逻辑
[ -e "$UP/app/src/main/AndroidManifest.xml" ] && mkdir -p "$PROJ/app/src/main" && cp -f "$UP/app/src/main/AndroidManifest.xml" "$PROJ/app/src/main/"
[ -d "$UP/app/src/main/res" ] && cp -rf "$UP/app/src/main/res" "$PROJ/app/src/main/"
[ -d "$UP/app/libs" ] && cp -rf "$UP/app/libs" "$PROJ/app/"

cd "$PROJ"
chmod +x gradlew 2>/dev/null || true
"$GRADLE_DIR/bin/gradle" assembleRelease --offline 2>&1 | tail -40 || \
"$GRADLE_DIR/bin/gradle" assembleRelease 2>&1 | tail -40
echo "EXIT=$?"
echo "产物：$(ls -l $PROJ/release/ 2>/dev/null | grep apk || echo 未生成，见上方日志)"