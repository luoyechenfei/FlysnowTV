#!/usr/bin/env python3
"""给 launcher 图标与 Leanback 横幅烘焙圆角（四角透明），适配直接显示 PNG 的 TV 桌面。

背景：本应用图标是纯静态 PNG（无 adaptive-icon），多数电视盒子的系统桌面不会
对图标做圆形 / 圆角遮罩，而是原样显示——四角不透明就是直角。此脚本把圆角
直接烘进图片 alpha 通道（4 倍超采样抗锯齿）：

- res/mipmap-*/ic_launcher.png  48~192px，圆角半径 = 22% 边长（现代圆角方块观感）
- res/drawable-xhdpi/banner.png 320x180，圆角半径 16px（与图标协调、不过分）

幂等：只做 alpha 裁剪，重复执行不会改变已圆角的图。依赖 Pillow（见
F:/android-build/pylib 或 pip install pillow）。
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")

ICONS = [
    ("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192),
]
ICON_RADIUS_RATIO = 0.22          # 圆角 = 22% 边长
BANNER_RADIUS = 16                # 320x180 横幅固定 16px
SS = 4                            # 超采样倍数（抗锯齿）


def rounded_mask(size_w, size_h, radius):
    """4 倍超采样绘制圆角矩形 mask，再高质量缩回原尺寸。"""
    big = Image.new("L", (size_w * SS, size_h * SS), 0)
    d = ImageDraw.Draw(big)
    d.rounded_rectangle(
        [0, 0, size_w * SS - 1, size_h * SS - 1],
        radius=radius * SS, fill=255)
    return big.resize((size_w, size_h), Image.LANCZOS)


def round_file(path, radius):
    im = Image.open(path).convert("RGBA")
    mask = rounded_mask(im.width, im.height, radius)
    alpha = im.getchannel("A")
    # 只做裁剪（取交集），保证幂等且不破坏原本就透明的区域
    from PIL import ImageChops
    new_alpha = ImageChops.darker(alpha, mask)
    im.putalpha(new_alpha)
    im.save(path, optimize=True)
    return im.size


def main():
    for folder, expected in ICONS:
        p = os.path.join(RES, folder, "ic_launcher.png")
        if not os.path.isfile(p):
            print("skip(missing):", p)
            continue
        w, h = round_file(p, round(ICON_RADIUS_RATIO * expected))
        print(f"ic_launcher {folder}: {w}x{h} radius={round(ICON_RADIUS_RATIO * expected)}")
    p = os.path.join(RES, "drawable-xhdpi", "banner.png")
    if os.path.isfile(p):
        w, h = round_file(p, BANNER_RADIUS)
        print(f"banner: {w}x{h} radius={BANNER_RADIUS}")


if __name__ == "__main__":
    sys.exit(main())
