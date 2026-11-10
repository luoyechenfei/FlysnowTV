"""
生成「飞雪TV」品牌资源：纯白色六角雪花，不含任何文字。

  res/drawable-xhdpi/banner.png    320x180  电视 Leanback 桌面横幅
  res/mipmap-*/ic_launcher.png     48~192   应用图标

1.3 起按用户要求去掉「飞雪TV」字样，只保留白色雪花图形。
需要 Pillow（pip install pillow）。若 Pillow 安装在非默认位置，
可用环境变量 PIL_PATH 指向该目录。
"""
import math
import os
import sys

extra = os.environ.get("PIL_PATH")
if extra:
    sys.path.insert(0, extra)

try:
    from PIL import Image, ImageDraw
except ImportError:
    raise SystemExit("未找到 Pillow，请先 pip install pillow，或用 PIL_PATH 指定其所在目录")

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

BLUE_TOP = (48, 104, 246)
BLUE_BOTTOM = (18, 48, 138)
DARK_TOP = (16, 28, 58)
DARK_BOTTOM = (5, 10, 24)
WHITE = (255, 255, 255)
GLOW = (86, 142, 255)


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=color)
    return img


def snowflake(d, cx, cy, radius, color, width):
    """六条主轴 + 每条轴两对分支 + 中心圆点，纯白无文字"""
    branch_width = max(1, int(width * 0.78))
    for i in range(6):
        angle = math.radians(i * 60 - 90)
        ux, uy = math.cos(angle), math.sin(angle)
        d.line(
            [(cx, cy), (cx + ux * radius, cy + uy * radius)],
            fill=color, width=width
        )
        # 主枝末端的小 V
        for frac in (0.52, 0.76):
            bx = cx + ux * radius * frac
            by = cy + uy * radius * frac
            length = radius * 0.24 * (1.0 - frac * 0.32)
            for sign in (-1, 1):
                branch_angle = angle + sign * math.radians(52)
                d.line(
                    [(bx, by),
                     (bx + math.cos(branch_angle) * length,
                      by + math.sin(branch_angle) * length)],
                    fill=color, width=branch_width
                )
        # 主轴末端小分叉
        tip_x = cx + ux * radius
        tip_y = cy + uy * radius
        cap = radius * 0.13
        for sign in (-1, 1):
            cap_angle = angle + sign * math.radians(38)
            d.line(
                [(tip_x, tip_y),
                 (tip_x - math.cos(cap_angle) * cap,
                  tip_y - math.sin(cap_angle) * cap)],
                fill=color, width=branch_width
            )
    core = max(1.0, radius * 0.075)
    d.ellipse([cx - core, cy - core, cx + core, cy + core], fill=color)


def render(size, top, bottom, radius_ratio, width_ratio, glow=True, ring=False):
    """4 倍超采样后 LANCZOS 缩小，保证 48dp 图标也清晰"""
    scale = 4
    w, h = size[0] * scale, size[1] * scale
    img = gradient((w, h), top, bottom)
    d = ImageDraw.Draw(img)
    cx, cy = w / 2, h / 2
    radius = min(w, h) * radius_ratio

    if glow:
        # 由内向外渐亮的一圈极淡光环，衬托白色雪花，不引入任何文字
        for step in range(12, 0, -1):
            r = radius * (1.0 + step * 0.045)
            t = (12 - step) / 12.0
            shade = tuple(
                int(DARK_BOTTOM[i] * (1 - t) + GLOW[i] * t) for i in range(3)
            )
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=shade, width=1)

    if ring:
        m = max(1, int(min(w, h) * 0.035))
        d.rounded_rectangle(
            [m, m, w - m, h - m],
            radius=int(min(w, h) * 0.22),
            outline=(150, 195, 255), width=max(1, int(min(w, h) * 0.022))
        )

    snowflake(d, cx, cy, radius, WHITE, max(1, int(min(w, h) * width_ratio)))
    return img.resize(size, Image.LANCZOS)


def make_banner(w=320, h=180):
    return render((w, h), DARK_TOP, DARK_BOTTOM, 0.33, 0.020, glow=True)


def make_icon(size):
    return render((size, size), BLUE_TOP, BLUE_BOTTOM, 0.31, 0.045, glow=False, ring=True)


def make_snowflake_foreground(size=128):
    """透明底纯白雪花，用于界面标题栏（替代原来的「飞雪TV」文字）"""
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    snowflake(d, s / 2, s / 2, s * 0.39, (255, 255, 255, 255), max(1, int(s * 0.045)))
    return img.resize((size, size), Image.LANCZOS)


def main():
    banner_path = os.path.join(ROOT, "drawable-xhdpi", "banner.png")
    os.makedirs(os.path.dirname(banner_path), exist_ok=True)
    make_banner().save(banner_path)
    print("written", os.path.relpath(banner_path, ROOT), "320x180")

    mark_path = os.path.join(ROOT, "drawable-nodpi", "ic_snowflake.png")
    os.makedirs(os.path.dirname(mark_path), exist_ok=True)
    make_snowflake_foreground().save(mark_path)
    print("written", os.path.relpath(mark_path, ROOT), "128x128 (transparent)")

    for folder, size in {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }.items():
        path = os.path.join(ROOT, folder, "ic_launcher.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        make_icon(size).save(path)
        print("written", os.path.relpath(path, ROOT), f"{size}x{size}")


if __name__ == "__main__":
    main()
