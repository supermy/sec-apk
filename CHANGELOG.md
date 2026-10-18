# Changelog

本项目所有重要变更记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- sec 决策模型数据增强管线:`finetune/collect_sec2.py`(新增 CISA KEV 已知被利用漏洞源)、
  `finetune/convert_sec2.py`(CVSS 连续分→严重度 1-5 五档、合成严重度分级提示词、正常业务困难负例、类别配额封顶)。
- 训练启动器 `finetune/train_sec2.sh`(GPU 机 RTX 5060 Ti,bf16,4 epoch)。
- 训练后自动流水 `finetune/post_train_sec2.sh`(等待→重启 LLM→评估→导出 int8→LiteRT NPU 转换→打包)
  与端侧打包脚本 `finetune/mkphone_sec.py`(命名对齐 APK 的 `laya_ml_*` 约定)。

## [0.1.0] - 2026-10-18

首个版本:独立「攻防实验台」APK,非 Root 本机网络攻防教学。

### Added
- **独立 APK 工程**(`com.selfhost.layasec`,minSdk 31 / targetSdk 28),复用 laya-test 推理引擎:
  - `SecTriage.kt`:端侧 Laya 决策模型三问分诊(类别 choice / 严重度 score / 是否人工研判 noul),
    直接读取 `/sdcard/models/laya-litert-sec/phone`(免 650MB 拷贝),MTK 天玑自动走 NPU(MDLA dispatch)。
  - `SecLab.java`:本地弱口令 HTTP 靶标(仅监听 127.0.0.1:18080)+ 弱口令爆破模拟(仅指向本机)+
    fail2ban 式防御(窗口内失败计数超阈值封禁)+ 分诊钩子。
  - `MainActivity.java`:控制台 UI(状态卡 / 8 个操作按钮 / 手动分诊 / 彩色事件流)。
  - `build.sh`:kotlinc + d8 + aapt2 + apksigner,复用 `jniExec` 原生库,产出约 5.6MB APK。
- 中英双语资源(`res/values` + `res/values-en`)。
- 使用流程文档 `README.md`(构建/安装/界面步骤/实测输出与解读/微调流程)。

### Verified
- 真机自测(Redmi / HyperOS 2,MT6993 天玑 9500,非 Root,adb 无线):
  - 安装成功;引擎加载为 `NPU(MTK MDLA)`。
  - 一键演示:34 候选爆破 → 5 次失败触发封禁 → 后续请求全部 403(命中 0)。
  - NPU 分诊:`类别=侦察探测(0.90) 严重度=2.00/5 人工研判=0.88 → 封禁IP并转人工研判`,`310ms`(含冷启,热路径 54-59ms/问)。
- 基线评估(现有 `laya-sec-ft`):choice 准确率 0.9984 / macro-F1 0.9988;
  **score MAE 1.017 / within-1 0.60(短板)**;noul F1 0.969 / ECE 0.064。

### Notes
- 靶场与攻击侧仅指向本机回环,用于教学演示,请勿改指向外部目标。
- sec 模型包只带 NPU 主图,非 MTK 机型无 GPU 兜底主图。
- 微调数据集含构造式合成(冷启动),分布 ≠ 真实安全运营数据。

[Unreleased]: https://github.com/supermy/sec-apk/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/supermy/sec-apk/releases/tag/v0.1.0