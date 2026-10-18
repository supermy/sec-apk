#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 LiteRT 转换产物打包成端侧 phone 目录(命名对齐 APK 的 laya_ml_* 约定)。
用法: python mkphone_sec.py <B> [<ft-dir>] [<label>]
  B 业务名(如 sec2);ft-dir 默认 /data/ai/laya-<B>-ft;label 默认"网络攻防告警分诊"
产物: /data/ai/phone-<B>/
"""
import json, hashlib, os, shutil, sys, glob

B = sys.argv[1] if len(sys.argv) > 1 else "sec"
N = "laya_" + B
FT = sys.argv[2] if len(sys.argv) > 2 else f"/data/ai/laya-{B}-ft"
LABEL = sys.argv[3] if len(sys.argv) > 3 else "网络攻防告警分诊"
conv, neg, aot = f"/data/ai/litert-{B}-conv", f"/data/ai/litert-{B}-negfix", f"/data/ai/laya-aot/out-{B}"
out = f"/data/ai/phone-{B}"
os.makedirs(out, exist_ok=True)


def cp(a, b):
    if not os.path.isfile(a):
        sys.exit(f"[pack] missing {a}")
    shutil.copy(a, b)


def first(pat):
    g = glob.glob(pat)
    return g[0] if g else None


# 1. NPU 主图(AOT apply_plugin)
m = first(f"{aot}/{N}_s256_embeds_*apply_plugin.tflite")
if not m:
    sys.exit(f"[pack] no AOT apply_plugin in {aot}")
cp(m, f"{out}/laya_ml_s256_embeds_npu.tflite")
# 2. GPU 主图(wfp16,可选兜底)
wf = first(f"{conv}/{N}_s256_embeds_wfp16.tflite")
if wf:
    shutil.copy(wf, f"{out}/laya_ml_s256_embeds_wfp16.tflite")
# 3. scorer / act / 词表
cp(f"{neg}/{N}_scorer.bin", f"{out}/laya_ml_scorer.bin")
cp(f"{conv}/{N}_act_head_fp32.tflite", f"{out}/laya_ml_act_head_fp32.tflite")
cp(f"{conv}/token_embeddings_fp16.bin", f"{out}/token_embeddings_fp16.bin")
cp(f"{FT}/tokenizer/tokenizer.json", f"{out}/tokenizer.json")
# 4. 校准(微调温度)
cfg = json.load(open(f"{FT}/rl_agent_config.json"))
cal = {"temperature": cfg.get("temperature", [1.0, 1.0, 1.0]),
       "temperature_by_options": cfg.get("temperature_by_options", {}),
       "provenance": {"model": f"laya-{B}-ft", "task": B, "max_len": 256, "head_max_len": 256}}
json.dump(cal, open(f"{out}/laya_ml_calibration.json", "w"), ensure_ascii=False, indent=1)
# 5. 词表元数据(真实 sha256)
h = hashlib.sha256()
with open(f"{out}/token_embeddings_fp16.bin", "rb") as f:
    for c in iter(lambda: f.read(1 << 20), b""):
        h.update(c)
meta = {"shape": [256000, 768], "dtype": "float16", "byte_order": "little", "layout": "row-major",
        "filename": "token_embeddings_fp16.bin",
        "size_bytes": os.path.getsize(f"{out}/token_embeddings_fp16.bin"),
        "sha256": h.hexdigest(), "source_repository": "finetuned-laya", "source_task": B}
json.dump(meta, open(f"{out}/token_embeddings.json", "w"), indent=1)
# 6. 标签 + 问题定义(与 task_sec.json 同构)
open(f"{out}/label.txt", "w", encoding="utf-8").write(LABEL)
shutil.copy("/data/ai/finetune/task_sec.json", f"{out}/questions.json")
sz = sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out) if os.path.isfile(os.path.join(out, f)))
print(f"[pack] ready: {out} ({sz // 1048576} MB)")