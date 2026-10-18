#!/usr/bin/env bash
# sec2 训练后自动流水:等待训练结束 → 重启 LLM → 评估 → 导出 int8 → LiteRT NPU 转换 → 打包 phone-sec2
# 全程日志: /data/ai/logs-cancer/post-sec2.log;末尾写 SEC2-POST-ALL-DONE 或失败标记
set -uo pipefail
LOG=/data/ai/logs-cancer/post-sec2.log
exec >> "$LOG" 2>&1
echo "=== post-train waiter started $(date '+%F %T') ==="

# 1) 等待训练进程结束
while pgrep -f "train_sms.py.*sec2" >/dev/null; do sleep 60; done
echo "=== training finished $(date '+%F %T') ==="
tail -8 /data/ai/logs-cancer/train-sec2.log || true

# 2) 立即恢复 LLM 升级通道后端(8899)
bash /data/ai/restart-llama.sh && echo "llama-server restored"

# 3) 评估(与基线同口径)
cd /data/ai/finetune
USE_TF=0 HF_HUB_OFFLINE=1 venv/bin/python evaluate.py \
  --model /data/ai/laya-sec-ft2 --data data/sec2-test.jsonl --classes data/sec2-classes.json \
  --task sec --out /data/ai/logs-cancer/metrics-sec2.json \
  || { echo "SEC2-EVAL-FAIL"; }
echo "=== evaluate done $(date '+%F %T') ==="

# 4) 导出 int8 ONNX(对齐现网签名)
venv/bin/python export_onnx.py --ckpt /data/ai/laya-sec-ft2 \
  --data data/sec2-test.jsonl --out /data/ai/laya-sec2-int8 \
  || { echo "SEC2-EXPORT-FAIL"; }
echo "=== export done $(date '+%F %T') ==="

# 5) LiteRT GPU/NPU 转换链(B=sec2;先建 ckpt 符号链接)
ln -sfn /data/ai/laya-sec-ft2 /data/ai/laya-sec2-ft
bash /data/ai/finetune/chain_litert.sh sec2 && echo "litert chain returned 0"
echo "=== litert chain done $(date '+%F %T') ==="

# 6) 打包 phone-sec2
python3 /data/ai/finetune/mkphone_sec.py sec2 && echo "SEC2-PACK-OK" || echo "SEC2-PACK-FAIL"
echo "=== POST done $(date '+%F %T') ==="
echo "SEC2-POST-ALL-DONE"