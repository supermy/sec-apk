#!/usr/bin/env bash
# 攻防实验台 APK 构建(kotlinc + 独立进程 runner;复用 laya engine 源码与 native libs)
# 用法: bash build.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
APPID="com.selfhost.layasec"
SDK="$HOME/build/mytflm/sdk/android-13/android.jar"
KS="$HOME/apk/k90.keystore"
JNIEXEC="$HOME/laya-test/apk/jniExec"
KOTLINC=${KOTLINC:-kotlinc}
OUT="$HERE/out"
VER="$(date +%H%M)"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/aar" "$OUT/jni" "$OUT/gen"
MANIFEST="$HERE/AndroidManifest.xml"
STDLIB="$(dirname $(readlink -f $(which kotlinc)))/../lib/kotlin-stdlib.jar"
echo "   stdlib: $STDLIB"

echo "== [0/6] 资源 + R(Java UI 引用 R,须先于 javac/kotlinc) =="
aapt2 compile --dir "$HERE/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/res.apk" -I "$SDK" --manifest "$MANIFEST" --java "$OUT/gen" "$OUT/res.zip"
javac --release 11 -cp "$SDK" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== [1/6] kotlinc(laya engine + SecTriage) =="
$KOTLINC -jvm-target 11 -cp "$SDK:$OUT/classes" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.kt')

echo "== [2/6] javac(MainActivity + SecLab) =="
javac --release 11 -cp "$SDK:$OUT/classes:$STDLIB" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

echo "== [3/6] d8 =="
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
d8 --min-api 31 --lib "$SDK" --lib "$STDLIB" --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB"

echo "== [4/6] aapt2 link =="
aapt2 link -o "$OUT/base.apk" -I "$SDK" --manifest "$MANIFEST" \
  --min-sdk-version 31 --target-sdk-version 28 "$OUT/res.zip"

echo "== [5/6] pack dex + jniLibs =="
cd "$OUT" && zip -qj base.apk dex/classes.dex
mkdir -p lib/arm64-v8a
# 独立进程推理 runner(按 lib*.so 命名装入 nativeLibraryDir 后可 exec)
cp "$JNIEXEC/litert-runner"      lib/arm64-v8a/librunner_rt.so
cp "$JNIEXEC/litert-runner-npu"  lib/arm64-v8a/libnpu_rt.so
# LiteRT 运行时 + dispatch + CL + absl + libc++(与 laya-test 打包一致)
for f in "$JNIEXEC"/lib*.so; do cp "$f" lib/arm64-v8a/; done
zip -qr base.apk lib
cd "$HERE"
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== [6/6] sign =="
apksigner sign --ks "$KS" --ks-pass pass:k90pass --ks-key-alias k90 \
  --out "$HERE/seclab-$VER.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$HERE/seclab-$VER.apk" | head -2
echo "完成: $HERE/seclab-$VER.apk (appId=$APPID)"
ls -la "$OUT/lib/arm64-v8a" | sed 's/^/   /'