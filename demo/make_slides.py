#!/usr/bin/env python3
"""生成演示视频幻灯片(720x1280 竖屏,深色主题,与 App 配色一致)。输出 demo/frames/chXX.png"""
import json
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "frames")
FONT = "/system/fonts/NotoSansCJK-Regular.ttc"
W, H = 720, 1280

BG = (11, 18, 32)
CARD = (17, 26, 44)
TXT = (230, 237, 247)
DIM = (143, 163, 191)
OFF = (90, 110, 140)
PRIMARY = (76, 141, 255)
CYAN = (34, 211, 238)
GREEN = (52, 211, 153)
RED = (248, 113, 113)
AMBER = (245, 158, 11)
PURPLE = (192, 132, 252)


def font(size):
    return ImageFont.truetype(FONT, size, index=0)


def wrap(d, text, f, max_w):
    lines, cur = [], ""
    for ch in text:
        if d.textlength(cur + ch, font=f) <= max_w:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


# 每章要点(与 script.json 解说对应;kind: b=要点, feed=事件流示例, chips=按钮, kv=状态行, banner=SSH横幅)
CHAPTERS = {
    "ch01": dict(items=[
        ("logo", "攻防实验台"),
        ("sub", "Android 安全教学实验箱 · 开源"),
        ("b", "端侧 NPU 分诊(MediaTek MDLA / GPU / CPU 自适应)"),
        ("b", "回环靶标:弱口令爆破 → fail2ban 等价防御 → 封禁"),
        ("b", "SSH 靶标模式:自有服务器上的防御闭环演练"),
        ("b", "全流程离线可复现,附单测与冒烟脚本"),
    ]),
    "ch02": dict(items=[
        ("h", "三个页面 · 状态卡 · 事件流"),
        ("kv", "引擎  NPU(MDLA) 就绪"),
        ("kv", "靶标  127.0.0.1:18080 运行中"),
        ("kv", "本机封禁  127.0.0.1"),
        ("kv", "SSH目标机  lab@10.0.0.10:8022"),
        ("feed", [("10:02:11", "CMD", "$ curl -s http://127.0.0.1:18080/login   # 探活", "本机靶标")]),
        ("feed", [("10:02:12", "DEF", "防御方已启动:窗口 10s 内失败 ≥ 5 次即封禁", "本机靶标"),
                  ("10:02:13", "TRI", "分诊[NPU]: 攻击威胁 严重度 4.6 → 封禁并转人工", "")]),
    ]),
    "ch03": dict(items=[
        ("h", "演练页 ①-⑥(顺序门控)"),
        ("chips", [("① 网络拉取", True), ("② 安装模型", True), ("③ 加载引擎", True),
                   ("④ 启动靶标", True), ("⑤ 模拟爆破", False), ("⑥ 启动防御", False)]),
        ("b", "① 从 ModelScope 拉取模型:断点续传 + 百分比"),
        ("b", "② 拷入私有目录(约 650MB),加载更稳"),
        ("b", "③ 初始化端侧推理,状态卡显示实际后端"),
        ("b", "④⑤⑥ 依次解锁;未满足前置会给出提示"),
    ]),
    "ch04": dict(items=[
        ("h", "本机回环演示(流量不出手机)"),
        ("feed", [("10:03:01", "TGT", "靶标已启动 http://127.0.0.1:18080/login", "本机靶标"),
                  ("10:03:04", "ATK", "POST /login password=… → 401", "本机靶标"),
                  ("10:03:07", "DEF", "来源 127.0.0.1 失败 ≥ 5 → 已封禁(600s)", "本机靶标"),
                  ("10:03:08", "ATK", "POST /login → 403(IP 已被 fail2ban 式封禁)", "本机靶标")]),
        ("b", "封禁对象是回环地址 127.0.0.1(手机自身),属预期"),
        ("b", "完整复现:高频失败 → 封禁 → 后续 403"),
    ]),
    "ch05": dict(items=[
        ("h", "事件流:定格时间戳 · 自动贴底"),
        ("b", "青色 $ = 等效命令    红色 = 攻击   绿色 = 防御"),
        ("b", "紫色 = NPU 分诊结论   灰色 @ = 该行目标机"),
        ("feed", [("10:04:10", "CMD", "$ fail2ban-client status sshd   # 等效", ""),
                  ("10:04:11", "SSH", "Banned IP list: 203.0.113.7", "SSH目标机 lab@10.0.0.10"),
                  ("10:04:12", "TRI", "分诊[NPU]: 侦察探测 2.1 → 安排复核", "SSH目标机 lab@10.0.0.10")]),
    ]),
    "ch06": dict(items=[
        ("h", "SSH 靶标模式(仅限自有/已授权)"),
        ("banner", "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13"),
        ("b", "填好 主机/用户 → 设为演练目标机;①连接成功自动设定"),
        ("b", "⑤ 内置小字典 ≤20 次,每次弹授权确认,失败计入 fail2ban"),
        ("b", "⑥ 查询远端 jail 与封禁列表;告警 → 分诊 → 处置闭环"),
        ("b", "安全边界:模拟攻击永远只指向本机回环"),
    ]),
    "ch07": dict(items=[
        ("h", "一键演示 · 停止全部"),
        ("b", "本机模式:自动 ④启动靶标 → ⑤爆破 → ⑥防御"),
        ("b", "SSH 模式:授权后 ④探测 → ⑤尝试 → ⑥防御查询 → ④告警分诊"),
        ("b", "停止全部:中断爆破/防御/靶标/口令尝试,在途结果不再记录"),
    ]),
    "ch08": dict(items=[
        ("h", "NPU 端侧分诊"),
        ("kv", "类别  攻击威胁(0.93)"),
        ("kv", "严重度  4.6/5   人工研判  0.71"),
        ("kv", "建议  封禁IP并转人工研判"),
        ("kv", "后端  NPU MDLA · 86ms"),
        ("b", "手动输入或防御告警自动触发;天玑走 NPU,其它回退 GPU/CPU"),
    ]),
    "ch09": dict(items=[
        ("h", "安全边界与合规"),
        ("b", "模拟攻击永远只指向本机回环 127.0.0.1"),
        ("b", "SSH 演习仅限本人拥有或已获书面授权的设备"),
        ("b", "口令不落盘;配置仅保存 地址/端口/用户名"),
        ("b", "仅用于网络安全教学;违规使用后果自负"),
    ]),
    "ch10": dict(items=[
        ("logo", "攻防实验台"),
        ("sub", "github.com/supermy/sec-apk"),
        ("b", "bash build.sh     构建 APK"),
        ("b", "bash run-tests.sh 纯逻辑单测(JVM 直跑)"),
        ("b", "bash smoke.sh     真机冒烟 + 人工清单"),
    ]),
}


def draw_chapter(ch, idx, total):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    # 顶栏
    d.text((40, 42), "攻防实验台 · 使用演示", font=font(30), fill=PRIMARY)
    d.text((40, 88), f"{idx + 1:02d} / {total:02d}  {ch['title']}", font=font(44), fill=TXT)
    d.line((40, 156, W - 40, 156), fill=(30, 42, 66), width=2)

    y = 196
    for item in CHAPTERS[ch["id"]]["items"]:
        kind = item[0]
        if kind == "logo":
            d.text((40, y + 40), item[1], font=font(88), fill=TXT)
            y += 150
        elif kind == "sub":
            d.text((40, y), item[1], font=font(36), fill=DIM)
            y += 80
        elif kind == "h":
            d.text((40, y), item[1], font=font(40), fill=AMBER)
            y += 84
        elif kind == "b":
            for i, line in enumerate(wrap(d, item[1], font(34), W - 130)):
                d.text((64 if i else 40, y), ("· " if i == 0 else "  ") + line, font=font(34), fill=TXT)
                y += 52
            y += 16
        elif kind == "kv":
            d.rounded_rectangle((40, y, W - 40, y + 74), 14, fill=CARD)
            d.text((64, y + 20), item[1], font=font(32), fill=DIM)
            y += 92
        elif kind == "banner":
            d.rounded_rectangle((40, y, W - 40, y + 84), 14, fill=(10, 32, 30))
            d.text((64, y + 26), item[1], font=font(26), fill=GREEN)
            y += 104
        elif kind == "chips":
            x = 40
            for label, on in item[1]:
                f = font(28)
                w = d.textlength(label, font=f) + 40
                if x + w > W - 40:
                    x = 40
                    y += 84
                d.rounded_rectangle((x, y, x + w, y + 64), 32, fill=(24, 40, 72) if on else (20, 26, 40),
                                    outline=PRIMARY if on else (44, 58, 84), width=2)
                d.text((x + 20, y + 16), label, font=f, fill=TXT if on else OFF)
                x += w + 16
            y += 100
        elif kind == "feed":
            colors = {"CMD": CYAN, "ATK": RED, "DEF": GREEN, "TGT": (96, 165, 250), "TRI": PURPLE, "SSH": AMBER}
            for t, ty, text, tag in item[1]:
                d.rounded_rectangle((40, y, W - 40, y + 64), 12, fill=(14, 22, 38))
                d.text((58, y + 16), f"[{t}]", font=font(26), fill=DIM)
                d.text((170, y + 16), text, font=font(26), fill=colors.get(ty, TXT))
                if tag:
                    d.text((170, y + 42), "@" + tag, font=font(20), fill=DIM)
                y += 74
            y += 12

    # 底栏进度点
    dots_y = H - 64
    x = 40
    for i in range(total):
        c = PRIMARY if i == idx else (44, 58, 84)
        d.ellipse((x, dots_y, x + 16, dots_y + 16), fill=c)
        x += 30
    d.text((W - 40 - d.textlength("github.com/supermy/sec-apk", font=font(22)), dots_y - 2),
           "github.com/supermy/sec-apk", font=font(22), fill=OFF)
    img.save(os.path.join(OUT, ch["id"] + ".png"))


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(HERE, "script.json"), encoding="utf-8") as f:
        script = json.load(f)
    chs = script["chapters"]
    for i, ch in enumerate(chs):
        draw_chapter(ch, i, len(chs))
        print("ok", ch["id"])


if __name__ == "__main__":
    main()
