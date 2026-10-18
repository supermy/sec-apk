#!/usr/bin/env bash
# 纯逻辑单元测试(JVM 直跑,无需 Gradle/JUnit/设备);用法: bash run-tests.sh
# 运行时 classpath 不含 android.jar:android.jar 的 org.json/文本类是 Stub,真实实现由 tools/json-*.jar 提供
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SDK="${ANDROID_JAR:-$HOME/build/mytflm/sdk/android-13/android.jar}"
JSch="$HERE/libs/jsch-2.28.8.jar"
JSON="$HERE/tools/json-20240303.jar"
OUT="$HERE/out-test"

[ -f "$SDK" ] || { echo "缺少 android.jar: $SDK"; exit 1; }
[ -f "$JSON" ] || { echo "缺少 $JSON(可执行: curl -sL -o $JSON https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar)"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac --release 11 -cp "$SDK:$JSch:$JSON" -d "$OUT/classes" \
  "$HERE/src/com/selfhost/layasec/Theme.java" \
  "$HERE/src/com/selfhost/layasec/TargetStore.java" \
  "$HERE/src/com/selfhost/layasec/EventFeed.java" \
  "$HERE/src/com/selfhost/layasec/SecLab.java" \
  "$HERE/src/com/selfhost/layasec/SshOps.java" \
  $(find "$HERE/test" -name '*.java')

java -cp "$OUT/classes:$JSch:$JSON" com.selfhost.layasec.CoreLogicTest
