#!/usr/bin/env bash
# 真机冒烟测试:自动部分(单测回归/APK 结构/素材环境/启动)+ 人工核对清单
# 用法: cd ~/sec-apk && bash smoke.sh [--install]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=com.selfhost.layasec
MODELS=/sdcard/models/laya-litert-sec/phone
TARGET_JSON=/sdcard/sec-lab/ssh-target.json
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  PASS $1"; }
bad(){ FAIL=$((FAIL+1)); echo "  FAIL $1"; }
note(){ echo "  ---- $1"; }

echo "== A. 单元测试回归 =="
if RES=$(bash "$HERE/run-tests.sh" 2>&1 | tail -1); then
  case "$RES" in
    *"0 失败"*) ok "$RES" ;;
    *) bad "单测结果: $RES" ;;
  esac
else
  bad "run-tests.sh 执行失败"
fi

echo "== B. APK 结构 =="
APK=$(ls -t "$HERE"/seclab-*.apk 2>/dev/null | head -1)
if [ -n "${APK:-}" ]; then
  ok "最新 APK: $(basename "$APK") ($(du -h "$APK" | cut -f1))"
  LIST=$(unzip -l "$APK" 2>/dev/null)
  for e in classes.dex lib/arm64-v8a/librunner_rt.so lib/arm64-v8a/libnpu_rt.so \
           lib/arm64-v8a/libLiteRt.so lib/arm64-v8a/libLiteRtDispatch_MediaTek.so \
           lib/arm64-v8a/libc++_shared.so; do
    if echo "$LIST" | grep -q "$e"; then ok "含 $e"; else bad "缺 $e"; fi
  done
  if echo "$LIST" | grep -q "contact_qr"; then ok "含联系人二维码资源"; else bad "缺 contact_qr 资源"; fi
  if apksigner verify "$APK" >/dev/null 2>&1; then ok "签名校验通过"; else bad "签名校验失败"; fi
  VC=$(grep -o 'versionCode="[0-9]*"' "$HERE/AndroidManifest.xml" | grep -o '[0-9]*')
  VN=$(grep -o 'versionName="[^"]*"' "$HERE/AndroidManifest.xml" | cut -d'"' -f2)
  ok "Manifest 版本: $VN (code $VC)"
else
  bad "未找到 seclab-*.apk(先执行 bash build.sh)"
fi

echo "== C. 素材与环境 =="
for f in laya_ml_s256_embeds_npu.tflite laya_ml_act_head_fp32.tflite \
         token_embeddings_fp16.bin laya_ml_scorer.bin laya_ml_calibration.json tokenizer.json; do
  if [ -s "$MODELS/$f" ]; then ok "模型包 $f ($(du -h "$MODELS/$f" | cut -f1))"; else bad "缺模型包 $f"; fi
done
[ -s "$MODELS/laya_ml_s256_embeds_wfp16.tflite" ] && note "wfp16 主图存在(GPU 后端可用)" || note "无 wfp16 主图(仅 NPU/CPU)"
if [ -s "$TARGET_JSON" ]; then
  CFG=$(python3 -c 'import json;d=json.load(open("/sdcard/sec-lab/ssh-target.json"));print(d["host"],d["port"],d.get("user",""))' 2>/dev/null)
  if [ -n "$CFG" ]; then
    ok "目标机配置: $CFG"
    set -- $CFG
    if timeout 5 bash -c "echo > /dev/tcp/$1/$2" 2>/dev/null; then ok "目标机 $1:$2 可达"
    else bad "目标机 $1:$2 不可达(演练 ④⑤⑥ 会失败)"; fi
  else
    bad "ssh-target.json 解析失败"
  fi
else
  note "未设定演练目标机(仅本机回环模式,正常)"
fi
note "SoC: $(getprop ro.soc.manufacturer) $(getprop ro.soc.model 2>/dev/null || getprop ro.board.platform)"
grep -q termux-wake-lock "$HOME/.bashrc" && ok "wake-lock 已入 .bashrc" || bad "wake-lock 未配置"

echo "== D. 启动检查 =="
OUT=$(am start -n "$PKG/.MainActivity" 2>&1)
if echo "$OUT" | grep -q "Starting"; then ok "App 启动 Intent 已发出(观察屏幕)"; else bad "启动失败: $OUT"; fi
if [ "${1:-}" = "--install" ] && [ -n "${APK:-}" ]; then
  cp -f "$APK" /sdcard/Download/ && am start -a android.intent.action.VIEW \
    -d "file:///sdcard/Download/$(basename "$APK")" -t application/vnd.android.package-archive >/dev/null 2>&1 \
    && ok "已发起安装 $(basename "$APK")"
fi

cat <<'CL'
== E. 人工核对清单(在手机上逐项确认) ==
  [ ] App 打开无闪退;演练页状态卡 4 行(模型/靶标/本机封禁/SSH目标机),①-⑥ 按钮齐全
  [ ] 分诊页「示例」→「分诊」输出类别/严重度/研判;引擎未加载时给出提示而非崩溃
  [ ] 未设目标机:④靶标 ⑤爆破(进度%滚动)⑥防御 → 事件流出现「封禁 127.0.0.1」且带 @本机靶标 灰标
  [ ] 一键演示(本机):自动跑 ④⑤⑥,事件流末尾有汇总;③部署规则弹确认后回显命令
  [ ] SSH 页:①连接测试(成功自动设为目标机);⋮→「使用说明」含 SSH 靶标模式与 ①-⑥ 说明
  [ ] 演练页状态卡变「靶标 …(SSH sshd) · 防御 远端 fail2ban」;④探测 banner 有 SSHN 输出
  [ ] SSH 模式⑤:弹授权确认(每次),选「是」后小字典尝试≤20 次,失败在目标机日志可见
  [ ] SSH 模式⑥:查询 fail2ban;若提示被自锁属预期(封禁演示生效);③部署弹确认
  [ ] 事件流:新事件自动贴底,上滑后松手恢复贴底;每行右侧目标机标注与事件来源一致
  [ ] ⋮→联系人:二维码显示正常;锁屏一段时间后回 App 不被杀(否则检查 HyperOS 省电设置)
CL

echo
echo "== 自动结果: $PASS 通过 / $FAIL 失败(人工清单见上) =="
[ "$FAIL" -eq 0 ]
