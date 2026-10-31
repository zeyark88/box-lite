#!/usr/bin/env bash
# 可复现地编译并自测原生 m3u8 去广告核心（无需 Android/NDK，宿主机 cc 即可）。
# 用法：./tests/m3u8_core_test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/app/src/main/jni/m3u8_core.c"
BIN="$(mktemp -d)/m3u8_core"

cc -Wall -Wextra -DM3U8_TEST "$CORE" -o "$BIN"

echo "== m3u8 原生核心自测 =="
OUT="$("$BIN")"
echo "$OUT"

# 期望：三样例均通过（normal/single 回退 r=0；ad-injected 广告被移除 r=1）
if echo "$OUT" | grep -q "OK"; then
  echo "=> 测试通过"
else
  echo "=> 测试失败" >&2
  exit 1
fi