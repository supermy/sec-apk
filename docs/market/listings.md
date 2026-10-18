# 市场文案(sec-apk / 攻防实验台)

## 应用名

- 中文:攻防实验台
- English:Sec Lab

## 一句话简介(≤30 字)

攻防实验台:本机弱口令靶标 + 爆破模拟 + fail2ban 式封禁 + 端侧 NPU 告警分诊,纯本机回环教学演示。

- 英文:Sec Lab — local weak-password target, brute-force sim, fail2ban-style bans and on-device NPU alert triage, all on loopback.

## 完整描述(中文)

攻防实验台是一款网络安全教学演示应用,把"攻击发生 → 自动封禁 → 后续请求被拒"的完整闭环浓缩在一部手机上,全部流量仅限本机回环(127.0.0.1),不涉及任何外部目标。

· ①-⑥ 顺序演练:网络拉取/安装模型 → 加载引擎 → 启动弱口令靶标 → 模拟爆破 → 启动防御(fail2ban 式:600 秒窗口 5 次失败即封禁)→ 封禁后请求全部 403;
· 端侧 NPU 分诊:天玑平台走 MDLA 神经网络推理(非天玑自动回退 GPU/CPU),对告警文本输出 类别/严重度/是否转人工研判/建议动作;
· SSH 目标机防护演习:对你自有或已授权的服务器做 连接测试/防护检查/fail2ban 规则部署/告警拉取分诊/封禁解封,口令尝试内置小字典、每次授权确认、上限 20 次;
· 事件流全程可视:每一步显示实际(或等效)命令与结果,行尾标注作用目标机;
· 中文 / English 双语界面。

安全声明:模拟攻击永远只指向本机回环靶标;SSH 演习仅限你本人拥有或已获书面授权的设备;严禁用于任何未授权目标。

## Full description (English)

Sec Lab is a hands-on network-security teaching demo that packs the full "attack → auto-ban → requests rejected" loop into your phone. All traffic stays on loopback (127.0.0.1) — no external hosts are ever involved.

- Guided drill steps ①–⑥: fetch/install the model, load the engine, start the weak-password local target, simulate brute force, start the fail2ban-style defender (5 failures in 600 s ⇒ ban), and watch banned requests get 403.
- On-device NPU triage: neural inference on MediaTek Dimensity (MDLA), falling back to GPU/CPU elsewhere; outputs category / severity / human-review / suggested action.
- SSH defense drill for hosts you own or are authorized to test: connectivity test, hardening check, fail2ban lab rules, alert fetch + triage, ban/unban. Password attempts use a tiny built-in dictionary, require per-run authorization and stop at 20 tries.
- Live event feed shows the actual (or equivalent) command behind every step, tagged with its target.
- Chinese / English UI.

Safety: simulated attacks always stay on the loopback target; the SSH drill is restricted to devices you own or are formally authorized to test. Never use it against unauthorized targets.

## 更新说明模板

- vX.Y.Z:一句话亮点;修复:…;界面:…(见 CHANGELOG.md)
- en: vX.Y.Z — one-line highlight; fixes: …; UI: … (see CHANGELOG.md)

## 关键词

攻防演练, 网络安全教学, 弱口令, fail2ban, 端侧 AI, NPU, 网络安全, security lab, brute force simulation, fail2ban, on-device AI
