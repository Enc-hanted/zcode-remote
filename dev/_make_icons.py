#!/usr/bin/env python3
"""
生成 launcher 图标：黑底 #0A0A0A + 终端绿 Z 字母（#4ADE80）
============================================================

源图 dev/zcode-logo-src.png 的真实结构（识图模型实锤，2026-08-04）：
- 深灰色圆角方块 (45,45,45) + 白色 Z 字母 (255,255,255)，仅两色
- 此前"闪电斜杠"的说法是误读：明暗看反了——白色部分就是 Z 字母本身，
  "冠带/分叉"是 Z 的负空间与字母笔画，不是独立装饰

本脚本提取白色 Z 字母作为标记：
- 亮像素（lum>110）→ 终端绿 #4ADE80；暗部（方块）→ 透明，露出黑底磁贴
- THICKEN 对字母笔画做膨胀加粗（细笔画在小尺寸偏细，源坐标 192 基准）
- 字母按 bbox 裁剪后 fit 到磁贴 60%，黑底留边
- 无方块、无白色、无外描线，任何遮罩裁切安全

历史尝试（已淘汰）：
- 反色版：白方块 + 深色 Z 裂口（用户："太丑"）
- 白 Z + 绿闪电 / 白方块 + 蓝/绿字母（用户："白底绿z"）
- 绿方块 + 白字母（用户："黑套绿加白字"）
- 定稿：黑底直接绿字——与官方"深底 + 亮 Z"结构一致，底换黑、字母换终端绿
"""
from PIL import Image, ImageFilter
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'dev', 'zcode-logo-src.png')
OUT_FG = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi', 'ic_launcher_foreground.png')
MIPS = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

# 定稿常量
BG = (10, 10, 10, 255)              # 纯黑磁贴 #0A0A0A
Z_COL = (74, 222, 128)              # 终端绿 #4ADE80
THICKEN = 2                         # 字母笔画膨胀像素数（源坐标 192 基准；0 = 原样细笔画）


def extract_letter(im, z_col, thicken=0):
    """提取白色 Z 字母：亮像素 → z_col（alpha 按亮度斜坡过渡），暗方块 → 透明。
    返回按字母 bbox 裁剪后的图像。"""
    im = im.convert('RGBA')
    px = im.load()
    w, h = im.size
    lum = Image.new('L', (w, h))
    lp = lum.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            lp[x, y] = 0 if a == 0 else (r + g + b) // 3
    letter = lum.point(lambda v: 255 if v > 110 else 0)
    if thicken:
        letter = letter.filter(ImageFilter.MaxFilter(thicken * 2 + 1))   # 膨胀 = 笔画加粗
    letter_px = letter.load()
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0 or letter_px[x, y] == 0:
                continue
            v = (r + g + b) / 3.0
            t = max(0.0, min(1.0, (v - 60) / 120))       # 亮度斜坡：方块侧→0，字母侧→1
            opx[x, y] = (z_col[0], z_col[1], z_col[2], int(a * t))
    xs = [x for x in range(w) for y in range(h) if opx[x, y][3] > 40]
    if not xs:
        return out
    x0, x1 = min(xs), max(xs)
    ys = [y for y in range(h) for x in range(w) if opx[x, y][3] > 40]
    y0, y1 = min(ys), max(ys)
    return out.crop((x0, y0, x1 + 1, y1 + 1))


def fit(im, target):
    w, h = im.size
    s = target / float(max(w, h))
    return im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)


def compose_fg(logo, size, bbox, bg=BG):
    """磁贴：bg + 居中 logo，画布 size，logo 长边 bbox"""
    canvas = Image.new('RGBA', (size, size), bg)
    lg = fit(logo, bbox)
    canvas.paste(lg, ((size - lg.size[0]) // 2, (size - lg.size[1]) // 2), lg)
    return canvas


def main():
    src = Image.open(SRC).convert('RGBA')
    letter = extract_letter(src, Z_COL, THICKEN)
    print('letter bbox size:', letter.size)

    # adaptive foreground（432 画布，字母 60% 留边）
    compose_fg(letter, 432, round(432 * 0.60)).save(OUT_FG)
    print('foreground saved')

    # legacy mipmap：黑底 + 居中字母（60% 留边）
    for d, size in MIPS.items():
        im = compose_fg(letter, size, round(size * 0.60))
        for name in ('ic_launcher', 'ic_launcher_round'):
            im.save(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-%s' % d, name + '.png'))
    print('mipmaps saved')


if __name__ == '__main__':
    main()
