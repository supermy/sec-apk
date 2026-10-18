#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""攻防实验台 APK 图标生成器(盾牌 + 利剑 + NPU 电路节点 主题)

产物:
  res/drawable/ic_launcher_fg.png          648x648 自适应图标前景(透明)
  res/mipmap-xxxhdpi/ic_launcher.png       512x512 传统方形图标(含底)
  tools/preview_*.png                      预览图(不入包)

用法: python3 tools/make_icon.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SS = 4  # 超采样倍数

BG_TOP = (16, 25, 44)
BG_BOT = (6, 10, 20)
SHIELD_TOP = (94, 155, 255)
SHIELD_BOT = (122, 92, 255)
SHIELD_RIM = (207, 224, 255)
BLADE = (232, 238, 249)
BLADE_EDGE = (159, 180, 216)
GUARD = (245, 165, 36)
GRIP = (42, 50, 64)
NODE = (232, 241, 255)
NODE_GREEN = (53, 196, 106)
RED = (240, 68, 56)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vgrad(size, top, bot):
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        px[0, y] = lerp(top, bot, y / max(1, h - 1))
    return img.resize((w, h))


def shield_points(cx, cy, w, h, n=160):
    """盾牌轮廓:上缘平直圆角,两侧弧线收到底部尖点。返回闭合点列。"""
    top = cy - h / 2
    bot = cy + h / 2
    r = w * 0.16
    pts = []
    # 顶部左圆角 → 顶部右圆角
    for i in range(24):
        a = math.pi + i * (math.pi / 2) / 23
        pts.append((cx - w / 2 + r + r * math.cos(a), top + r + r * math.sin(a)))
    for i in range(24):
        a = -math.pi / 2 + i * (math.pi / 2) / 23
        pts.append((cx + w / 2 - r + r * math.cos(a), top + r + r * math.sin(a)))
    # 右侧弧线到底尖(三次贝塞尔)
    p0 = (cx + w / 2, top + r)
    c1 = (cx + w / 2, top + h * 0.42)
    c2 = (cx + w * 0.42, bot - h * 0.20)
    p1 = (cx, bot)
    for i in range(1, n // 2 + 1):
        t = i / (n // 2)
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * c1[0] + 3 * mt * t**2 * c2[0] + t**3 * p1[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * c1[1] + 3 * mt * t**2 * c2[1] + t**3 * p1[1]
        pts.append((x, y))
    for i in range(1, n // 2 + 1):
        t = i / (n // 2)
        mt = 1 - t
        x = mt**3 * p1[0] + 3 * mt**2 * t * (2 * cx - c2[0]) + 3 * mt * t**2 * (2 * cx - c1[0]) + t**3 * (2 * cx - p0[0])
        y = mt**3 * p1[1] + 3 * mt**2 * t * c2[1] + 3 * mt * t**2 * c1[1] + t**3 * p0[1]
        pts.append((x, y))
    return pts


def draw_scene(size, scale=1.0, with_bg=True, bg_radius=0.22):
    """在 size x size 画布上绘制图标内容(4x 超采样由调用方处理)。"""
    S = size
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    if with_bg:
        bg = vgrad((S, S), BG_TOP, BG_BOT).convert("RGBA")
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * bg_radius), fill=255)
        img.paste(bg, (0, 0), mask)
        # 细电路底纹(点阵)
        d = ImageDraw.Draw(img)
        step = S / 18
        for gx in range(2, 17, 2):
            for gy in range(3, 17, 3):
                x, y = gx * step, gy * step
                if (gx + gy) % 4 == 1:
                    r = S * 0.004
                    d.ellipse([x - r, y - r, x + r, y + r], fill=(120, 160, 255, 60))

    cx, cy = S / 2, S / 2
    sw, sh = S * 0.447 * scale, S * 0.533 * scale   # 盾牌宽高

    # ---------- 利剑(盾后) ----------
    # 剑尖指向右上,剑柄在左下,两端探出盾牌
    ang = math.radians(-45)
    ux, uy = math.cos(ang), math.sin(ang)
    px_, py_ = -uy, ux

    def P(d_along, d_perp):
        return (cx + ux * d_along + px_ * d_perp, cy + uy * d_along + py_ * d_perp)

    tip_d = S * 0.55 * scale        # 剑尖(探出右上)
    guard_d = -S * 0.26 * scale     # 剑格(探出左下)
    grip_len = S * 0.22 * scale     # 剑柄长(剑首探出左下)
    blade_w = S * 0.105 * scale

    sword = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sword)
    tip = P(tip_d, 0)
    # 剑身:细长风筝形
    sd.polygon([tip, P(tip_d * 0.30, -blade_w * 0.28), P(guard_d, -blade_w / 2), P(guard_d, blade_w / 2), P(tip_d * 0.30, blade_w * 0.28)],
               fill=BLADE)
    # 中脊
    sd.line([tip, P(guard_d, 0)], fill=BLADE_EDGE, width=max(2, int(S * 0.006 * scale)))
    # 剑格(琥珀色横条)
    gw = blade_w * 1.9
    gl = S * 0.030 * scale
    sd.polygon([P(guard_d - gl, -gw / 2), P(guard_d + gl, -gw / 2), P(guard_d + gl, gw / 2), P(guard_d - gl, gw / 2)], fill=GUARD)
    # 剑柄
    sd.polygon([P(guard_d - gl, -blade_w * 0.34), P(guard_d - grip_len, -blade_w * 0.34),
                P(guard_d - grip_len, blade_w * 0.34), P(guard_d - gl, blade_w * 0.34)], fill=GRIP)
    # 剑首(琥珀圆)
    pr = blade_w * 0.46
    pcx, pcy = P(guard_d - grip_len, 0)
    sd.ellipse([pcx - pr, pcy - pr, pcx + pr, pcy + pr], fill=GUARD)
    sword = sword.filter(ImageFilter.GaussianBlur(S * 0.0018))
    img.alpha_composite(sword)

    # ---------- 盾牌 ----------
    cover = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    cd = ImageDraw.Draw(cover)
    pts = shield_points(cx, cy, sw, sh)
    cd.polygon(pts, fill=SHIELD_RIM + (255,))
    img.alpha_composite(cover)

    inner = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    idm = Image.new("L", (S, S), 0)
    ipts = shield_points(cx, cy - S * 0.004, sw * 0.90, sh * 0.94)
    ImageDraw.Draw(idm).polygon(ipts, fill=255)
    grad = vgrad((S, S), SHIELD_TOP, SHIELD_BOT).convert("RGBA")
    inner.paste(grad, (0, 0), idm)
    # 左上高光
    hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse([cx - sw * 0.55, cy - sh * 0.55, cx + sw * 0.15, cy - sh * 0.05],
                               fill=(255, 255, 255, 46))
    hl.putalpha(Image.composite(hl.getchannel("A"), Image.new("L", (S, S), 0), idm))
    inner.alpha_composite(hl)
    img.alpha_composite(inner)

    # ---------- 盾内电路(节点 + 连线) ----------
    cir = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    cd = ImageDraw.Draw(cir)
    lw = max(2, int(S * 0.010 * scale))
    n_r = S * 0.030 * scale
    nA = (cx - sw * 0.20, cy - sh * 0.20)
    nB = (cx + sw * 0.16, cy - sh * 0.04)
    nC = (cx - sw * 0.10, cy + sh * 0.14)
    nD = (cx + sw * 0.18, cy + sh * 0.26)
    for a, b in ((nA, nB), (nB, nC), (nC, nD)):
        cd.line([a, b], fill=NODE + (200,), width=lw)
    for (x, y), col in ((nA, NODE), (nB, NODE), (nC, NODE_GREEN), (nD, NODE)):
        cd.ellipse([x - n_r, y - n_r, x + n_r, y + n_r], fill=col + (255,))
        cd.ellipse([x - n_r * 0.45, y - n_r * 0.45, x + n_r * 0.45, y + n_r * 0.45], fill=(20, 30, 50, 220))
    img.alpha_composite(cir)

    # ---------- 来袭箭头(顶在盾左缘) ----------
    ar = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ad = ImageDraw.Draw(ar)
    ay = cy + sh * 0.10
    ah = S * 0.052 * scale
    # 盾缘 x:盾左边界在 ay 高度处(用轮廓点内插)
    edge_x = min(p[0] for p in pts if abs(p[1] - ay) < S * 0.04) if any(abs(p[1] - ay) < S * 0.04 for p in pts) else cx - sw / 2
    tipx = edge_x - S * 0.012 * scale
    ad.polygon([(tipx, ay), (tipx - ah, ay - ah * 0.72), (tipx - ah, ay + ah * 0.72)], fill=RED + (255,))
    for k in (0, 1):
        x1 = tipx - ah * (1.35 + k * 0.95)
        ad.line([(x1, ay), (x1 - ah * 0.55, ay)], fill=RED + (185 - k * 60,), width=max(2, int(S * 0.013 * scale)))
    ar = ar.filter(ImageFilter.GaussianBlur(S * 0.0012))
    img.alpha_composite(ar)

    return img


def main():
    fg_size = 648 * SS
    fg = draw_scene(fg_size, scale=1.0, with_bg=False)
    fg = fg.resize((648, 648), Image.LANCZOS)
    out_fg = os.path.join(ROOT, "res", "drawable", "ic_launcher_fg.png")
    os.makedirs(os.path.dirname(out_fg), exist_ok=True)
    fg.save(out_fg)

    leg_size = 512 * SS
    leg = draw_scene(leg_size, scale=0.80, with_bg=True)
    leg = leg.resize((512, 512), Image.LANCZOS)
    out_leg = os.path.join(ROOT, "res", "mipmap-xxxhdpi", "ic_launcher.png")
    os.makedirs(os.path.dirname(out_leg), exist_ok=True)
    leg.save(out_leg)

    # 预览:自适应前景贴到深色圆角底 + 传统图标
    prev = Image.new("RGB", (560, 560), (9, 12, 18))
    bgsq = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    mask = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 511, 511], radius=112, fill=255)
    bgsq.paste(vgrad((512, 512), BG_TOP, BG_BOT).convert("RGBA"), (0, 0), mask)
    fgc = fg.resize((512, 512), Image.LANCZOS)
    bgsq.alpha_composite(fgc)
    prev.paste(bgsq, (24, 24), bgsq)
    leg_small = leg.resize((160, 160), Image.LANCZOS)
    prev.paste(leg_small, (380, 380))
    prev.save(os.path.join(HERE, "preview_icon.png"))
    print("saved:", out_fg, out_leg)


if __name__ == "__main__":
    main()
