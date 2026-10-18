#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""上传 sec2 端侧模型包到 ModelScope(与 laya-test 共用仓库 supermy/myjev)

布局: sec2/phone/<模型文件> —— APK「网络加载模型」默认从该目录下载
(同一仓库沿用 laya-test 约定;设备侧按 base/npu/gpu 子集选择文件)。

用法:
  export MS_TOKEN='ms-xxxx'          # ModelScope 访问令牌(SDK 令牌,不写入文件)
  python3 finetune/upload_sec2_myjev.py [phone-sec2 目录]
"""
import os
import sys

from modelscope.hub.api import HubApi

REPO = 'supermy/myjev'
TOKEN = os.environ.get('MS_TOKEN', '')
HERE = os.path.dirname(os.path.abspath(__file__))
PHONE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', 'phone-sec2')

README = '''# laya-sec2 端侧决策模型(攻防实验台)

网络攻防告警分诊 Laya 决策模型 v2(sec2):类别(5 类)/严重度(1-5)/是否人工研判;
MTK 天玑 NPU 主图 + GPU(wfp16)兜底。

- 训练:基于 convaiinnovations/laya-multilingual(mmBERT 322M)微调;数据增强 v2
  (CVSS 连续分五档、CISA KEV 在野利用、合成严重度分级、困难负例)。
- 文件:
  - `laya_ml_s256_embeds_npu.tflite`  NPU 主图(MTK MDLA AOT)
  - `laya_ml_s256_embeds_wfp16.tflite` GPU 兜底主图
  - `laya_ml_act_head_fp32.tflite` / `laya_ml_scorer.bin` / `laya_ml_calibration.json`
  - `token_embeddings_fp16.bin` / `token_embeddings.json` / `tokenizer.json`
  - `questions.json` / `label.txt`
- 端侧:sec-apk(com.selfhost.layasec)「网络加载模型」默认从本仓库 `sec2/phone` 下载;
  MTK 机型取 base+npu 子集,其它机型取 base+gpu 子集,已存在且完整的文件自动跳过。
- 许可证:模型权重基于上游 laya-multilingual 微调,遵循其许可;本仓库脚本 Apache-2.0,
  商用请保留署名。
'''


def main():
    if not TOKEN:
        print('MISSING MS_TOKEN')
        sys.exit(1)
    if not os.path.isdir(PHONE):
        print('MISSING phone dir:', PHONE)
        sys.exit(1)
    api = HubApi()
    api.login(TOKEN)
    print('login ok', flush=True)
    api.upload_file(repo_id=REPO, path_or_fileobj=README.encode('utf-8'),
                    path_in_repo='sec2/README.md',
                    commit_message='sec2: model card (laya-sec2 phone package)')
    print('README uploaded', flush=True)
    api.upload_folder(repo_id=REPO, folder_path=PHONE, path_in_repo='sec2/phone',
                      commit_message='sec2 phone package (laya-sec2-ft, NPU+GPU)')
    print('SEC2-MYJEV-DONE', flush=True)


if __name__ == '__main__':
    main()
