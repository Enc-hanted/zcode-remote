#!/usr/bin/env python3
"""
生成品牌图标：黑底 #0A0A0A 磁贴 + 终端绿 >_ 提示符（#00E676）
================================================================

v2（二期品牌资产，docs/evolution-plan.md §3.5「品牌资产」裁定）：
- 前景从「源图提取白 Z 字母」改为程序绘制 >_ 提示符。几何与
  app/src/main/res/drawable/ic_logo.xml 逐值同源（viewport 24：
  折线 M6,6 L12,12 L6,18 + 下划线 M13,18 L19,18，strokeWidth 2.4 圆帽圆角），
  不再依赖任何位图源图——docs/assets/ 下 a_whiteZ_* / b_greenZ_* 是 v1 时代的
  候选图，仅存档，本脚本不读。
- 品牌绿取深色 accent #00E676（values-night/colors.xml:9）。磁贴色两模式
  同值 #0A0A0A（values 与 values-night 的 ic_launcher_background），图标不随
  昼夜切换，故用黑底对比更优的深色绿（黑底 12.58:1 AAA，evolution-plan §3.0）。
- 缩放改按「墨迹外接圆半径」适配 adaptive 安全区：108dp 画布的 66dp 直径
  安全校（半径 = 画布 30.56%）。旧法 bbox 60% 对 Z 字母的墨迹对角半径约
  177px，实际探出 132px 安全校，圆罩启动器会裁笔画——v2 一并修正。
- 新增 Android 13+ 主题图标单色层：透明底 + 白 >_（系统按 alpha 重新着色，
  白只是占位色）。mipmap-anydpi-v26 两个 XML 里的 <monochrome> 指向它，
  26-32 平台忽略该标签、33+ 生效（官方标准挂法）。

输出（重跑幂等：PIL PNG 不写时间戳，同参数同字节）：
- drawable-nodpi/ic_launcher_foreground.png   432×432 黑底 + 绿 >_
- drawable-nodpi/ic_launcher_monochrome.png   432×432 透明底 + 白 >_
- mipmap-{mdpi..xxxhdpi}/ic_launcher{,_round}.png  48..192，与前景同构图
"""
from PIL import Image, ImageDraw
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_FG = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi', 'ic_launcher_foreground.png')
OUT_MONO = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi', 'ic_launcher_monochrome.png')
MIPS = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

TILE = (10, 10, 10, 255)        # 磁贴 @color/ic_launcher_background #0A0A0A（两模式同值）
GREEN = (0, 230, 118, 255)      # 品牌绿 #00E676 = 深色 accent（values-night/colors.xml:9）
WHITE = (255, 255, 255, 255)    # monochrome 占位色，系统按 alpha 着色

# >_ 几何：与 ic_logo.xml 逐值同源（viewport 24 / stroke 2.4 / 圆帽圆角）
STROKE = 2.4
CHEVRON = [(6, 6), (12, 12), (6, 18)]      # ic_logo.xml:8  M6,6l6,6-6,6
UNDERSCORE = [(13, 18), (19, 18)]          # ic_logo.xml:14 M13,18h6

SS = 24                                    # 超采样倍数（viewport 24 → 576 画布再缩）
SAFE_R = 66.0 / 108.0 / 2.0                # adaptive 安全校半径 = 画布 30.56%
MARK_R = 0.95                              # 墨迹外接圆半径落在安全圆的 95%（留呼吸）


def draw_mark(color):
    """在 SS×24 的透明画布上画 >_：线段 + 端点/顶点圆（圆帽圆角的超采样近似）。"""
    W = 24 * SS
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = int(round(STROKE * SS))
    r = w / 2.0

    def polyline(pts):
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            d.line([(x0 * SS, y0 * SS), (x1 * SS, y1 * SS)], fill=color, width=w)
        for x, y in pts:                   # 端点圆帽 + 折点圆角（w/2 圆并集）
            d.ellipse([x * SS - r, y * SS - r, x * SS + r, y * SS + r], fill=color)

    polyline(CHEVRON)
    polyline(UNDERSCORE)
    return im


def ink_bbox(im):
    """按 alpha 裁出墨迹外接框（背景全透明，getbbox 即墨迹范围）。"""
    return im.crop(im.getbbox())


def ink_radius(im):
    """墨迹外接框中心到最远墨迹像素的距离——决定安全圆内缩放的量。"""
    w, h = im.size
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    px = im.load()
    best = 0.0
    for y in range(h):
        for x in range(w):
            if px[x, y][3] > 0:
                best = max(best, math.hypot(x - cx, y - cy))
    return best


def compose(mark, radius, size, ink_r_frac, tile):
    """tile 底 + 居中 mark，墨迹外接圆半径 = size × ink_r_frac（占全画布比例，
    与 SAFE_R 同口径——66dp 安全校是 108dp 全画布的 30.56%，不是半画布）。"""
    s = (size * ink_r_frac) / radius
    w = max(1, round(mark.size[0] * s))
    h = max(1, round(mark.size[1] * s))
    m = mark.resize((w, h), Image.LANCZOS)
    canvas = Image.new('RGBA', (size, size), tile)
    canvas.paste(m, ((size - w) // 2, (size - h) // 2), m)
    return canvas


def main():
    frac = SAFE_R * MARK_R
    green = ink_bbox(draw_mark(GREEN))
    white = ink_bbox(draw_mark(WHITE))
    print('mark ink bbox: green=%s white=%s, radius=%.1f/%.1f viewport px'
          % (green.size, white.size, ink_radius(green) / SS, ink_radius(white) / SS))

    compose(green, ink_radius(green), 432, frac, TILE).save(OUT_FG)
    print('foreground saved:', OUT_FG)
    compose(white, ink_radius(white), 432, frac, (0, 0, 0, 0)).save(OUT_MONO)
    print('monochrome saved:', OUT_MONO)

    for d, size in MIPS.items():
        im = compose(green, ink_radius(green), size, frac, TILE)
        for name in ('ic_launcher', 'ic_launcher_round'):
            im.save(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-%s' % d, name + '.png'))
    print('mipmaps saved')


if __name__ == '__main__':
    main()
