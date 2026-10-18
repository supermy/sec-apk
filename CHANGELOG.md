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
- **UI 使用说明与引导**:首次启动自动弹出使用说明,右上角「说明」按钮随时重看;
  顶部新增引导入口,覆盖模型/引擎/靶标/爆破/防御/手动分诊/SSH 演习全流程。
- **分诊示例数据**:`SecTriage.SAMPLES` 内置 7 条示例(5 类别 + 困难负例·误报/低危),
  手动分诊区新增「示例」按钮,选取后一键载入文本框再分诊。
- **SSH 目标机防护演习**(`SshOps.java` + `libs/jsch-2.28.8.jar`,仅限自有设备):
  连接测试、防护检查(sshd 配置/失败登录数/fail2ban 状态)、部署 fail2ban 实验规则
  (5 次失败/600s 封 600s,需 root 或免密 sudo)、拉取远端失败登录告警并送 NPU 分诊、
  fail2ban 封禁/解封源 IP。爆破模拟仍只指向本机回环。
- `build.sh` 支持 `libs/*.jar`(自动剥离多版本 jar 的 META-INF 后参与 javac/d8)。

## [0.6.0] - 2026-10-18

### Added
- **多语言支持(中文 / English)**:
  - UI 文案全量资源化:`res/values`(zh) + `res/values-en`(en),覆盖按钮、Tab、状态卡、SSH 目标行、
    菜单、对话框、说明页、示例选择与交互提示;应用名与桌面 label 同步本地化(`@string/app_name`)。
  - 语言切换:系统跟随 + 应用内切换(⋮ 菜单 → 语言 / Language,三态:跟随系统/中文/English),
    选择持久化,切换即时重建界面;API 33+ 已预留 localeConfig(供将来 targetSdk 升级后接入系统级 per-app 语言)。
  - 冷启动钩子:供自动化验证与演示 `am start -n com.selfhost.layasec/.MainActivity --es lang en|zh|system`。
  - 事件流日志(动态命令/结果行)暂保持中文,后续按需资源化。

## [0.5.1] - 2026-10-18

### Fixed
- 事件流贴底:setText 后单次 post 落在重新布局之前,高频事件下永远慢一拍 → 改为二次 post +
  合并连续滚动请求,新日志始终处于可视范围。
- 「停止全部」:**本地爆破**停止后不再播报在途请求结果(攻击代际计数,旧监听线程静默退出);
  新增对 **SSH 靶标模式口令尝试**的停止支持(每次尝试前检查停止标志,收到即中止);
  停止全部现覆盖 爆破 / 防御 / 靶标 / SSH 口令尝试。

### Added
- `smoke.sh` 真机冒烟:自动 21 项(单测回归/APK 结构与签名/模型包与目标机可达性/唤醒锁/启动)
  + 10 项人工核对清单;`--install` 顺带发起 APK 安装。
- 演示视频管线 `demo/`:解说词 `script.json` → edge-tts 语音 → PIL 幻灯片 → ffmpeg 合片并烧字幕,
  产出 `seclab-demo.mp4` / `preview.gif` / `subtitles.srt`(README 已嵌入)。
- GitHub Actions(`.github/workflows/build.yml`):单元测试 + APK 构建 + 产物上传;
  `native/arm64-v8a/` 收录 arm64 推理运行时,CI 构建不再依赖外部目录;
  `build.sh` 支持 `ANDROID_JAR/KOTLINC/KS/JNIEXEC` 环境覆盖与无签名库时的未签名产物。
- 单测扩至 36 断言(新增 stopBrute 预置停止、连接被拒连续两次自动停止)。

## [0.5.0] - 2026-10-18

### Added
- **SSH 靶标模式**:SSH目标页填好 主机/用户 后,演练页 ④⑤⑥ 自动指向目标机——④探测 sshd banner(免登录)、
  ⑤内置小字典口令尝试(每次弹授权确认,上限 20 次,失败真实计入目标机日志/fail2ban)、
  ⑥查询远端 fail2ban 状态与封禁列表;被封禁后新会话失败即「自锁」演示。未设定目标机时保持本机回环模式(互不影响)。
  一键演示按模式分流:SSH 靶标模式 = 授权确认后 ④→⑤→⑥→④(拉取告警分诊)。
- **纯逻辑单元测试**:`test/CoreLogicTest.java` + `run-tests.sh`(JVM 直跑,无需 Gradle/JUnit/设备),
  32 项断言覆盖 IP 校验(含注入字符)、SSH 告警解析、目标配置读写与容错(缺失/坏 JSON/空主机)、
  事件流配色、爆破字典约束(≤20 上限)、banner 探测失败路径。

### Changed
- **代码重构**:抽出 `Theme`(配色)/`EventFeed`(事件流)/`TargetStore`(目标配置)三个类,MainActivity 瘦身;
  事件流修复时间戳缺陷(历史行时间随渲染漂移 → 记录时定格);副标题版本号改为读取 Manifest。
- TargetStore/SshOps 解析方法支持注入路径/包可见,便于单测(行为不变)。

## [0.4.5] - 2026-10-18

### Changed
- **一键演示支持 SSH 目标机联动**:本机段(④⑤⑥)跑完后,若已设定目标机且填了密码,
  自动续跑远端处置链 ①连接→②检查→④拉取告警并 NPU 分诊→⑤封禁 TOP 来源 IP,
  形成「本机演示攻击 → 本机自动防御 → 真实服务器告警处置」闭环;未设定目标机/未填密码时
  仅跑本机段并在事件流提示。③部署实验规则(改目标机配置)仍保留手动确认。
- 使用说明新增「一键演示(含 SSH 目标机联动)」专节(中/英)。

## [0.4.4] - 2026-10-18

### Added
- **事件流目标机标注**:每行右侧加灰色标签「@本机靶标 127.0.0.1:18080」/「@SSH目标机 user@host:port」
  (攻击/防御/靶标/SSH 及 ④ 的 SSH 分诊行),拦截封禁日志一眼可辨作用对象。
- **使用说明补全演练页 ①-⑥ 按钮详解**(中/英):每个按钮做什么、门控顺序(④→⑤→⑥)、
  失败≥5 次触发封禁、封禁后 403、事件流目标机标注说明。

## [0.4.3] - 2026-10-18

### Changed
- **状态卡区分两套目标**:新增第 4 行「SSH目标机 user@host:port · 远端封禁以④输出为准」(未设定则显示「未设定」);
  原行改为「本机靶标 / 本机封禁」,避免把本地 127.0.0.1 封禁与远端 fail2ban 混淆。
- 使用说明新增「两个目标,互不影响」专节(中/英):本机靶标演示封禁回环 127.0.0.1 的原因、
  SSH 目标机远程封禁查法(②/④事件流输出)、安全边界(攻击永不出回环)。

## [0.4.2] - 2026-10-18

### Changed
- ① 连接测试成功后**自动将该 SSH 主机设为演练目标机**(写入 `/sdcard/sec-lab/ssh-target.json` 并更新页面提示,
  事件流播报「连接成功:user@host:port 已设为演练目标机」),无需再手动点「设为演练目标机」。

## [0.4.1] - 2026-10-18

### Added
- **演练目标机可设定**:SSH 页新增「设为演练目标机」,保存 主机/端口/用户 到
  `/sdcard/sec-lab/ssh-target.json`(可外部编辑);App 启动自动载入并显示「当前演练目标机: user@host:port」。
  密码仍不落盘。

## [0.4.0] - 2026-10-18

### Added
- **Tab 导航(演练 / 分诊 / SSH目标)**:不同内容分页展示,事件流固定常驻屏幕下半区,单屏即可看到日志。
- **SSH 步骤门控**:①连接测试默认可用,②~⑥ 需上一步成功解锁(置灰禁点),完成打勾;点击有「已解锁 X」提示。
- **SSH 输入区/动作行**沿用步骤编号;底部附一句话流程说明。
- 攻击按钮实时进度「⑤ 模拟爆破 12/34」;①②③ 长任务进度条(确定/不确定)+ 按钮内百分比。

## [0.3.0] - 2026-10-18

### Added
- **深色主题 UI 重做**:整页可滚动 + 事件流内嵌独立滚动区(固定高度、智能贴底、上限 800 条),
  卡片式布局(状态卡/流程卡/分诊卡/SSH 卡/日志卡)。
- **右上角 ⋮ 菜单**:使用说明(随时重看)/ 联系人(内置微信二维码名片)。
- **说明文案合规化**:使用警告与法律责任(仅限学习与自有设备,引用《网络安全法》《刑法》相关条款)、
  Apache-2.0 商用须保留署名说明、事件流 `$` 命令阅读指引。
- **步骤按钮状态**:①~⑥ 序号 + 完成打勾(✓ 变绿)+ 执行中「…处理中」,严格体现操作先后次序。
- **事件流展示实际命令**(`TYPE_CMD`,青色 `$` 行):靶标 `curl` 探活、爆破 `curl` 循环等效命令、
  封禁 `iptables -A INPUT -s <ip> -j DROP` 等效动作、SSH 远端执行脚本全文回显。
- **「① 网络拉取模型」**:`ModelNet.java` 从 ModelScope 公共仓库 `supermy/myjev`(`sec2/phone`)下载:
  MTK 机型取 NPU 主图、其它机型取 GPU(wfp16)主图;按 Content-Length 校验并跳过完整文件;`.part` 断点续传。
- **应用图标**:自绘自适应图标(盾 + 剑 + NPU 电路节点 + 红色来袭箭头;`tools/make_icon.py`),
  `mipmap-anydpi-v26` + 传统 xxxhdpi 兜底。
- `finetune/upload_sec2_myjev.py`:sec2 模型包上传脚本(PC 执行;与 laya-test 共用 `supermy/myjev`,
  上传至 `sec2/phone`,含 model card `sec2/README.md`)。

### Changed
- 事件流上限 300 → 800;攻击日志改为展示真实请求(`POST /login password="…" → 401/403`);
- 状态卡三行结构化着色(就绪/运行中绿色、封禁红色、引擎青色);按钮统一深色圆角样式;
- 手动分诊/SSH 区改为卡片容器;首启引导弹窗文案重写(合规警告置顶)。

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

[Unreleased]: https://github.com/supermy/sec-apk/compare/v0.5.1...HEAD
[0.5.1]: https://github.com/supermy/sec-apk/releases/tag/v0.5.1
[0.5.0]: https://github.com/supermy/sec-apk/releases/tag/v0.5.0
[0.4.5]: https://github.com/supermy/sec-apk/releases/tag/v0.4.5
[0.4.4]: https://github.com/supermy/sec-apk/releases/tag/v0.4.4
[0.4.3]: https://github.com/supermy/sec-apk/releases/tag/v0.4.3
[0.4.2]: https://github.com/supermy/sec-apk/releases/tag/v0.4.2
[0.4.1]: https://github.com/supermy/sec-apk/releases/tag/v0.4.1
[0.4.0]: https://github.com/supermy/sec-apk/releases/tag/v0.4.0
[0.3.0]: https://github.com/supermy/sec-apk/releases/tag/v0.3.0
[0.1.0]: https://github.com/supermy/sec-apk/releases/tag/v0.1.0