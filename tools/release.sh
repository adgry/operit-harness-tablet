#!/usr/bin/env bash
# 打包并发布一个新版本：APK + SHA256 + update.json 一起上传到 GitHub Release。
# 用法：tools/release.sh "本次更新说明" [仓库，默认 adgry/operit-harness-tablet]
set -euo pipefail
cd -- "$(dirname -- "$0")/.."

NOTES="${1:-常规更新}"
REPO="${2:-adgry/operit-harness-tablet}"

VERSION_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || { echo "无法从 app/build.gradle.kts 读取版本号" >&2; exit 1; }

APK="app/build/outputs/apk/clone/app-clone.apk"
[[ -f "$APK" ]] || { echo "缺少 $APK，请先运行 ./gradlew :app:assembleClone" >&2; exit 1; }

TAG="v$VERSION_NAME"
ASSET="Operit-Harness-arm64-$VERSION_NAME.apk"
OUT="build/release"
mkdir -p "$OUT"
cp -- "$APK" "$OUT/$ASSET"
SHA=$(shasum -a 256 "$OUT/$ASSET" | awk '{print $1}')
printf '%s  %s\n' "$SHA" "$ASSET" > "$OUT/$ASSET.sha256"

cat > "$OUT/update.json" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkUrl": "https://github.com/$REPO/releases/download/$TAG/$ASSET",
  "sha256": "$SHA",
  "notes": "$(printf '%s' "$NOTES" | sed 's/"/\\"/g')"
}
JSON

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$OUT/$ASSET" "$OUT/$ASSET.sha256" "$OUT/update.json" --repo "$REPO" --clobber
else
  gh release create "$TAG" "$OUT/$ASSET" "$OUT/$ASSET.sha256" "$OUT/update.json" \
    --repo "$REPO" --title "Operit Harness $VERSION_NAME" --notes "$NOTES"
fi

echo "已发布 ${TAG}：${SHA}"
