#!/usr/bin/env bash
# 攻防实验台 APK 构建(kotlinc + 独立进程 runner;复用 laya engine 源码与 native libs)
# 用法: bash build.sh
# 环境覆盖(CI/其它机器):ANDROID_JAR  KOTLINC  KS/KS_PASS/KS_ALIAS  JNIEXEC
# 发布构建覆盖:VER=<版本号> MANIFEST=<发布清单(debuggable=false)>;release.sh 自动使用
# native 回退:JNIEXEC 不存在时使用仓库内 native/arm64-v8a; 无签名库时输出 seclab-<HHMM>-unsigned.apk
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
APPID="com.selfhost.layasec"
SDK="${ANDROID_JAR:-$HOME/build/mytflm/sdk/android-13/android.jar}"
KS="${KS:-$HOME/apk/k90.keystore}"
KS_PASS="${KS_PASS:-k90pass}"
KS_ALIAS="${KS_ALIAS:-k90}"
JNIEXEC="${JNIEXEC:-$HOME/laya-test/apk/jniExec}"
[ -d "$JNIEXEC" ] || JNIEXEC="$HERE/native/arm64-v8a"
KOTLINC=${KOTLINC:-kotlinc}
OUT="$HERE/out"
VER="${VER:-$(date +%H%M)}"
[ -f "$SDK" ] || { echo "缺少 android.jar: $SDK(可用 ANDROID_JAR 覆盖)"; exit 1; }
echo "   sdk: $SDK"
echo "   natives: $JNIEXEC"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/aar" "$OUT/jni" "$OUT/gen"
MANIFEST="${MANIFEST:-$HERE/AndroidManifest.xml}"
STDLIB="$(dirname $(readlink -f $(which kotlinc)))/../lib/kotlin-stdlib.jar"
echo "   stdlib: $STDLIB"

# 第三方依赖(libs/*.jar):剥离 META-INF(多版本 jar 的 Java9+ 类 Android 不需要)
DEPS="$OUT/deps"; mkdir -p "$DEPS"
JARFILES=(); CP_EXTRA=""
for j in "$HERE"/libs/*.jar; do
  [ -f "$j" ] || continue
  base="$(basename "$j" .jar)-clean.jar"
  rm -rf "$DEPS/x"; mkdir -p "$DEPS/x"
  ( cd "$DEPS/x" && unzip -qo "$j" -x 'META-INF/*' >/dev/null && jar cf "$DEPS/$base" . )
  JARFILES+=("$DEPS/$base"); CP_EXTRA="$CP_EXTRA:$DEPS/$base"
  echo "   lib: $(basename "$j") -> $base"
done

echo "== [0/6] 资源 + R(Java UI 引用 R,须先于 javac/kotlinc) =="
aapt2 compile --dir "$HERE/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/res.apk" -I "$SDK" --manifest "$MANIFEST" --java "$OUT/gen" "$OUT/res.zip"
javac --release 11 -cp "$SDK" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== [1/6] kotlinc(laya engine + SecTriage) =="
$KOTLINC -jvm-target 11 -cp "$SDK:$OUT/classes" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.kt')

echo "== [2/6] javac(MainActivity + SecLab + SshOps) =="
javac --release 11 -cp "$SDK:$OUT/classes:$STDLIB$CP_EXTRA" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

echo "== [3/6] d8 =="
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
d8 --min-api 31 --lib "$SDK" --lib "$STDLIB" --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB" "${JARFILES[@]}"

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
if [ -f "$KS" ]; then
  apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$HERE/seclab-$VER.apk" "$OUT/aligned.apk"
  apksigner verify --print-certs "$HERE/seclab-$VER.apk" | head -2
  echo "完成: $HERE/seclab-$VER.apk (appId=$APPID)"
else
  cp "$OUT/aligned.apk" "$HERE/seclab-$VER-unsigned.apk"
  echo "未找到签名库 $KS → 输出未签名 APK(CI 可先用 keytool 生成临时库并传 KS/KS_PASS/KS_ALIAS)"
  echo "完成: $HERE/seclab-$VER-unsigned.apk (appId=$APPID)"
fi
ls -la "$OUT/lib/arm64-v8a" | sed 's/^/   /'