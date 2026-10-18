# 骁龙(Snapdragon)NPU 可行性调研 — sec-apk

> 结论先行:**技术可行,但当前阻塞**。阻塞点不是"能不能跑",而是两点:
> ① 全链路 LiteRT 版本必须对齐(Maven 2.3.0 runtime 与本仓库现役 2.2 时代 libLiteRt/自编 MTK dispatch 存在 ABI 耦合);
> ② 无骁龙真机可回归。在拿到骁龙测试机之前,不建议把骁龙路径合入主线。

## 1. 两条官方路线(2026-10 现状)

| | AOT(离线编译) | 端上 JIT(推荐首期) |
|---|---|---|
| 做法 | PC/CI 用 LiteRT AOT Compiler 把 .tflite 编成目标 SoC 的 CompiledModel | 应用内 `CompiledModel.create(..., Options(NPU, GPU))`,首次运行在设备上编译 |
| 环境 | Ubuntu 22.04 + Bazel 7.4.1 + NDK(可在 GitHub Actions ubuntu runner 跑) | 仅需按 HTP 架构分包 runtime |
| 优点 | 启动快、内存低;支持多 SoC 同编 | 无需预编;官方内置 GPU/CPU 回退;支持编译缓存 |
| 缺点 | 每颗目标 SoC 需产出资产;CI 重 | 首次初始化慢(可用 `CompilerCacheDir` 缓存:指纹/模型/参数变化才重编) |
| 适用 | 大模型、SoC 已知 | 小模型、跨机型分发(**本项目的 triage 模型仅数 MB**) |

支持 SoC(官方清单):SM8850(8 Elite Gen5)/SM8750(8 Elite)/SM8650(8 Gen3)/SM8550(8 Gen2)/SM8475(8+ Gen1)/SM8450(8 Gen1)。

## 2. 实测证据(本仓库)

- 已下载 Maven runtime(`$PREFIX/tmp/litert-npu-runtime-{qualcomm,mediatek}-2.3.0.aar`,2026-10-02 构建):
  - Qualcomm:`libLiteRtCompilerPlugin_Qualcomm.so`(773KB)+ `libLiteRtDispatch_Qualcomm.so`(446KB)
  - MediaTek:对应两个 .so(420KB/331KB)
  - **AAR 不含 libLiteRt.so,也不含 QAIRT 运行库**——骁龙侧还需要 QAIRT(QNN)运行库:`libQnnHtp.so`、`libQnnHtpPrepare.so`、`libQnnHtpV{69,73,75,79,81}Skel.so`(按 HTP 架构版本),并需 `useLegacyPackaging` 类处理。
- 本仓库现役 `native/arm64-v8a/libLiteRt.so` 与手编的 MTK dispatch 为旧版组合(laya-test 时代 vendored);
  2.3.0 的 dispatch/replace-plugin 与之混用有 dispatch-API ABI 版本不匹配风险(编译插件 + dispatch API 与 LiteRT core 是成对交付的)。

## 3. 推荐落地步骤(拿到骁龙测试机后执行)

1. **版本对齐**:把 `libLiteRt.so` 与 MTK dispatch 升级到与 Maven runtime 同版(2.3.0),先在**现役天玑机**上回归 NPU 路径(这一步是无骁龙机也能做的关键回归)。
2. **骁龙分包**:把 QAIRT skel + Qualcomm dispatch/compiler 按 HTP 架构分目录打包(或按需下载);运行时用 `Build.SOC_MODEL`(API31+)判定档位,不匹配则走 GPU/CPU 回退(官方内置)。
3. **开启 JIT 编译缓存**:环境项 `CompilerCacheDir` 指向应用私有目录,避免每次冷启动重编。
4. **验证矩阵**:分诊样例(4 类)× {NPU, GPU 回退} 输出一致性 + 首编/热启时延打点。
5. 可选二期:GitHub Actions 上跑 AOT Compiler notebook,产出按 SoC 的 CompiledModel 资产,换启动时延。

## 4. 明确不做/不建议

- ❌ 直接把 2.3.0 Qualcomm dispatch 塞进现役旧 libLiteRt 组合:ABI 不匹配,可能连 MTK 路径一起崩。
- ❌ 在无骁龙真机时合入骁龙打包逻辑(无法回归,违反本项目"可测试才交付"原则)。
- ❌ 为分发打包全部 5 个 HTP skel 进单体 APK:体积膨胀且大部分机型用不到,应分包/按需。

## 5. 参考

- LiteRT NPU 统一指南(AOT/JIT、Play Feature Delivery、缓存):https://developers.google.com/edge/litert/next/npu
- Qualcomm AI Engine Direct(QNN HTP 后端、支持 SoC):https://developers.google.com/edge/litert/next/qualcomm
- LiteRT Qualcomm 集成(QNN 选项/HTP 配置):https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/README.md
- Qualcomm QAIRT:Run LiteRT Model on NPU:https://docs.qualcomm.com/doc/80-80022-15B/topic/run-litert-model-on-npu.html
