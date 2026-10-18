#!/usr/bin/env python3
"""生成公众号推文素材:封面首图 900x383、方形分享图 500x500、下载二维码。
输出:docs/market/wechat-cover.png / wechat-square.png / wechat-qr-download.png"""
import os

from PIL import Image, ImageDraw, ImageFont
import qrcode

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "docs", "market")
FONT = "/system/fonts/NotoSansCJK-Regular.ttc"
ICON = os.path.join(HERE, "preview_icon.png")
RELEASE_URL = "https://github.com/supermy/sec-apk/releases/latest"

BG = (11, 18, 32)
CARD = (17, 26, 44)
TXT = (230, 237, 247)
DIM = (143, 163, 191)
OFF = (90, 110, 140)
PRIMARY = (76, 141, 255)
CYAN = (34, 211, 238)
GREEN = (52, 211, 153)


def font(size):
    return ImageFont.truetype(FONT, size, index=0)


def rounded_icon(size):
    im = Image.open(ICON).convert("RGBA").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size, size], radius=int(size * 0.22), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def cover():
    W, H = 900, 383
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=0, outline=(29, 44, 68), width=2)
    d.rectangle([0, 0, 8, H], fill=PRIMARY)
    ic = rounded_icon(210)
    im.paste(ic, (W - 210 - 72, (H - 210) // 2), ic)
    d.text((64, 74), "攻防实验台", font=font(66), fill=TXT)
    d.text((66, 162), "网络攻防演练 · 端侧 NPU 分诊", font=font(28), fill=CYAN)
    d.text((66, 208), "弱口令靶标 → 爆破模拟 → 自动封禁 → 403", font=font(24), fill=DIM)
    d.text((66, 252), "全程本机回环(127.0.0.1),非 Root 可用", font=font(24), fill=DIM)
    d.text((66, 312), "中文 / English · 开源 Apache-2.0 · github.com/supermy/sec-apk", font=font(19), fill=OFF)
    p = os.path.join(OUT, "wechat-cover.png")
    im.save(p)
    print("->", p)


def square():
    S = 500
    im = Image.new("RGB", (S, S), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([6, 6, S - 6, S - 6], radius=28, outline=(29, 44, 68), width=2)
    ic = rounded_icon(190)
    im.paste(ic, ((S - 190) // 2, 64), ic)
    t = "攻防实验台"
    f = font(52)
    d.text(((S - d.textlength(t, font=f)) / 2, 292), t, font=f, fill=TXT)
    s = "本机回环 · NPU 分诊 · 开源"
    f2 = font(24)
    d.text(((S - d.textlength(s, font=f2)) / 2, 372), s, font=f2, fill=CYAN)
    p = os.path.join(OUT, "wechat-square.png")
    im.save(p)
    print("->", p)


def qr_download():
    qr = qrcode.QRCode(border=2, box_size=8)
    qr.add_data(RELEASE_URL)
    qr.make(fit=True)
    qim = qr.make_image(fill_color=(11, 18, 32), back_color=(255, 255, 255)).convert("RGB").resize((380, 380), Image.NEAREST)

    W, H = 500, 560
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=24, outline=(29, 44, 68), width=2)
    bg = Image.new("RGB", (396, 396), (255, 255, 255))
    im.paste(bg, ((W - 396) // 2, 34))
    im.paste(qim, ((W - 380) // 2, 42))
    cap = "扫码下载 APK(v0.6.0 · 约 6MB)"
    f = font(24)
    d.text(((W - d.textlength(cap, font=f)) / 2, 456), cap, font=f, fill=TXT)
    sub = "github.com/supermy/sec-apk/releases"
    f2 = font(17)
    d.text(((W - d.textlength(sub, font=f2)) / 2, 498), sub, font=f2, fill=OFF)
    p = os.path.join(OUT, "wechat-qr-download.png")
    im.save(p)
    print("->", p)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    cover()
    square()
    qr_download()
