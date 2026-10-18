#!/usr/bin/env bash
# 发版流程:升版本 → 重建(debuggable=false) → 校验 badging/签名 → 复制到 release/
#   (教训来自 laya-test:先 sed 升版本、后复用旧构建 → 附件版本错位;必须"升版本后重建")
# 用法:
#   bash release.sh                  # 补丁号 +1、versionCode +1
#   bash release.sh -v 0.7.0 -c 20   # 指定版本
# 产物: release/seclab-<version>.apk + .sha256
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MAN="$HERE/AndroidManifest.xml"

NEW_V=""; NEW_C=""
while [ $# -gt 0 ]; do
  case "$1" in
    -v) NEW_V="$2"; shift 2;;
    -c) NEW_C="$2"; shift 2;;
    *) echo "用法: bash release.sh [-v X.Y.Z] [-c versionCode]"; exit 2;;
  esac
done

CUR_V=$(grep -o 'android:versionName="[^"]*"' "$MAN" | head -1 | sed 's/.*="//;s/"$//')
CUR_C=$(grep -o 'android:versionCode="[^"]*"' "$MAN" | head -1 | sed 's/.*="//;s/"$//')
[ -n "$NEW_V" ] || NEW_V=$(echo "$CUR_V" | awk -F. '{printf "%d.%d.%d", $1, $2, $3+1}')
[ -n "$NEW_C" ] || NEW_C=$((CUR_C + 1))

echo "== 发版: $CUR_V($CUR_C) → $NEW_V($NEW_C) =="

# 1) 升版本(开发清单保留 debuggable=true,发布清单单独生成)
sed -i "s/android:versionCode=\"$CUR_C\"/android:versionCode=\"$NEW_C\"/; s/android:versionName=\"$CUR_V\"/android:versionName=\"$NEW_V\"/" "$MAN"

# 2) 发布用清单(debuggable=false);放在 build.sh 清理范围(out/)之外
REL_MAN="$HERE/out-release-manifest.xml"
trap 'rm -f "$REL_MAN"' EXIT
sed 's/android:debuggable="true"/android:debuggable="false"/' "$MAN" > "$REL_MAN"
grep -q 'android:debuggable="false"' "$REL_MAN" || { echo "发布清单 debuggable 替换失败"; exit 1; }

# 3) 重建(VER 命名 + MANIFEST 覆盖,确保产物即目标版本)
MANIFEST="$REL_MAN" VER="$NEW_V" bash "$HERE/build.sh"

APK="$HERE/seclab-$NEW_V.apk"
[ -f "$APK" ] || { echo "未生成 $APK(签名库缺失?设 KS/KS_PASS/KS_ALIAS 后重试)"; exit 1; }

# 4) 校验:版本一致 + 非 debuggable + 签名有效 + 单测回归
BAD=$(aapt2 dump badging "$APK")
echo "$BAD" | grep -q "versionName='$NEW_V'" || { echo "badging versionName 与 $NEW_V 不一致"; exit 1; }
echo "$BAD" | grep -q "versionCode='$NEW_C'" || { echo "badging versionCode 与 $NEW_C 不一致"; exit 1; }
if echo "$BAD" | grep -q 'application-debuggable'; then echo "错误: 发布包仍为 debuggable"; exit 1; fi
apksigner verify "$APK" || { echo "签名校验失败"; exit 1; }
bash "$HERE/run-tests.sh" | tail -1

# 5) 复制发布
mkdir -p "$HERE/release"
cp -f "$APK" "$HERE/release/seclab-$NEW_V.apk"
( cd "$HERE/release" && sha256sum "seclab-$NEW_V.apk" > "seclab-$NEW_V.apk.sha256" )

echo
echo "完成: release/seclab-$NEW_V.apk(debuggable=false,已签名)"
echo "下一步(发布清单见 docs/market/README.md):"
echo "  1) CHANGELOG.md 补 [$NEW_V] 条目;git add -A && git commit && git push"
echo "  2) git tag v$NEW_V && git push origin v$NEW_V"
echo "  3) gh release create v$NEW_V release/seclab-$NEW_V.apk --notes \"见 CHANGELOG $NEW_V\""
echo "  4) F-Droid 自建仓库(gh-pages fdroid/repo)增量发布,见 docs/market/README.md"
