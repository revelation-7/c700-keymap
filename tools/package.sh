#!/bin/bash
# 一键构建 + 出包：编译 APK / 守护 dex，组装 Magisk 模块 zip 到 release/
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(grep -m1 '^version=' "$ROOT/module/module.prop" | cut -d= -f2)

echo "== 1/3 编译（app + daemon）=="
bash "$ROOT/app/build.sh"

echo "== 2/3 组装模块包 v$VER =="
rm -rf "$ROOT/out/pkg" && mkdir -p "$ROOT/out/pkg" "$ROOT/release"
cp "$ROOT/module/module.prop" "$ROOT/module/service.sh" \
   "$ROOT/module/injectd-handler.sh" "$ROOT/module/config.default" "$ROOT/out/pkg/"
cp "$ROOT/out/c700-keymapd.apk" "$ROOT/out/pkg/app.apk"
cp "$ROOT/out/inject.dex"            "$ROOT/out/pkg/inject.dex"
( cd "$ROOT/out/pkg" && rm -f "$ROOT/release/c700-keymap-v$VER.zip" && \
  zip -q "$ROOT/release/c700-keymap-v$VER.zip" \
      module.prop service.sh injectd-handler.sh config.default app.apk inject.dex )

echo "== 3/3 出独立 APK + 校验和 =="
cp "$ROOT/out/c700-keymapd.apk" "$ROOT/release/c700-keymapd-v$VER.apk"
( cd "$ROOT/release" && sha256sum "c700-keymap-v$VER.zip" "c700-keymapd-v$VER.apk" > SHA256SUMS.txt )

ls -la "$ROOT/release"
echo "OK: release/c700-keymap-v$VER.zip"
