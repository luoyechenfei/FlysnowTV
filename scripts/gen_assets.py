"""
生成「飞雪TV」品牌资源：倾斜双层电视 LOGO + 雪花玻璃徽标。

  res/drawable-xhdpi/banner.png    320x180  电视 Leanback 桌面横幅
  res/mipmap-*/ic_launcher.png     48~192   应用图标
  res/drawable-nodpi/ic_snowflake.png       透明底雪花（备用素材）

底稿 scripts/assets/tv_logo_ref.png 为用户提供的参考图：橙色后层电视轮廓、
蓝色前层电视轮廓、两根橙色天线与圆点、深灰 TV 字母、倾斜错位透视线。
本脚本在此底稿上叠加一枚六角雪花玻璃徽标，并按「液态玻璃」风格重做底板。

**零第三方依赖**：只用 Python 标准库完成 PNG 解码 / 超采样绘制 / LANCZOS 式
面积平均缩小 / PNG 编码，不需要 Pillow。直接 `python scripts/gen_assets.py` 即可。

可选：
  python scripts/gen_assets.py --preview F:/android-build/brand-preview.png
"""
import math
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "assets", "tv_logo_ref.png")
ROOT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "res"))

ORANGE = (242, 158, 34)
BLUE = (41, 166, 222)
DARK = (64, 64, 66)
ICE = (236, 246, 255)          # 深色底横幅里替换 TV 字母，保证可读
WHITE = (255, 255, 255)

# ---------------------------------------------------------------- PNG 基础


def _chunks(data):
    pos = 8
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        yield typ, data[pos + 8:pos + 8 + ln]
        pos += 12 + ln


def read_png_rgb(path):
    """解码 8bit RGB / RGBA PNG，返回 (w, h, RGB bytearray)。"""
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    w = h = depth = ctype = 0
    idat = bytearray()
    for typ, payload in _chunks(data):
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", payload[:10])
        elif typ == b"IDAT":
            idat.extend(payload)
        elif typ == b"IEND":
            break
    assert depth == 8 and ctype in (2, 6), "仅支持 8bit RGB/RGBA"
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = w * bpp
    rows = bytearray(w * h * 3)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ft == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        base = y * w * 3
        for x in range(w):
            i = x * bpp
            rows[base + x * 3:base + x * 3 + 3] = line[i:i + 3]
        prev = line
    return w, h, rows


def write_png_rgba(path, w, h, buf):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw.extend(buf[y * stride:(y + 1) * stride])

    def chunk(typ, payload):
        return (struct.pack(">I", len(payload)) + typ + payload
                + struct.pack(">I", zlib.crc32(typ + payload) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


# ---------------------------------------------------------------- 底稿抠白底

PALETTE = (ORANGE, BLUE, DARK)


def unmatte_sprite(path):
    """把参考图的白底还原成透明，边缘按「主色 + alpha」反算，避免白边。"""
    w, h, rgb = read_png_rgb(path)
    out = bytearray(w * h * 4)
    for i in range(w * h):
        p = (rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])
        if min(p) >= 250:
            continue
        best = None
        for c in PALETTE:
            weights = tuple(255 - c[k] for k in range(3))
            den = sum(v * v for v in weights)
            num = sum((255 - p[k]) * weights[k] for k in range(3))
            a = num / den if den else 1.0
            a = 0.0 if a < 0 else (1.0 if a > 1 else a)
            err = sum((p[k] - (a * c[k] + (1.0 - a) * 255)) ** 2 for k in range(3))
            if best is None or err < best[0]:
                best = (err, c, a)
        err, c, a = best
        if err > 1800:               # 两色相交的极少数边缘，保留原色不透明
            c, a = p, 1.0
        j = i * 4
        out[j], out[j + 1], out[j + 2] = c
        out[j + 3] = int(a * 255 + 0.5)
    return w, h, out


# ---------------------------------------------------------------- 画布工具


class Canvas:
    def __init__(self, w, h, bg_fn):
        self.w, self.h = w, h
        self.buf = bytearray(w * h * 4)
        for y in range(h):
            base = y * w * 4
            for x in range(w):
                r, g, b = bg_fn(x, y, w, h)
                j = base + x * 4
                self.buf[j], self.buf[j + 1], self.buf[j + 2], self.buf[j + 3] = r, g, b, 255

    def blend(self, x, y, r, g, b, a):
        if a <= 0 or x < 0 or y < 0 or x >= self.w or y >= self.h:
            return
        if a > 255:
            a = 255
        j = (y * self.w + x) * 4
        ia = 255 - a
        dst_a = self.buf[j + 3]
        out_a = a + dst_a * ia // 255
        if out_a <= 0:
            return
        prem = dst_a * ia // 255
        self.buf[j] = _clamp8((r * a + self.buf[j] * prem) // out_a)
        self.buf[j + 1] = _clamp8((g * a + self.buf[j + 1] * prem) // out_a)
        self.buf[j + 2] = _clamp8((b * a + self.buf[j + 2] * prem) // out_a)
        self.buf[j + 3] = _clamp8(out_a)

    def sample(self, x, y):
        j = (y * self.w + x) * 4
        return self.buf[j], self.buf[j + 1], self.buf[j + 2]


def _clamp8(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


def _glow(u, v, cx, cy, radius):
    d2 = ((u - cx) ** 2 + (v - cy) ** 2) / (radius * radius)
    if d2 >= 1.0:
        return 0.0
    f = 1.0 - d2
    return f * f


def light_bg(x, y, w, h):
    """冰白玻璃底：极浅冷色渐变 + 双环境光 + 斜向镜面反光。"""
    u, v = x / w, y / h
    t = 0.55 * u + 0.45 * v
    r = 255 - 24 * t
    g = 255 - 14 * t
    b = 255 - 2 * t
    warm = _glow(u, v, 0.20, 0.82, 0.75) * 13
    cool = _glow(u, v, 0.85, 0.16, 0.70) * 12
    r += warm + cool * 0.15
    g += warm * 0.55 + cool * 0.6
    b += cool
    sheen = 1.0 / (1.0 + ((u + v - 1.02) / 0.20) ** 2) * 9
    r += sheen; g += sheen; b += sheen
    return _clamp8(r), _clamp8(g), _clamp8(b)


def dark_bg(x, y, w, h):
    """深蓝液态玻璃底：多光源环境光 + 斜向高光 + 边缘压暗。"""
    u, v = x / w, y / h
    t = 0.45 * u + 0.55 * v
    r = 18 - 11 * t
    g = 52 - 30 * t
    b = 112 - 62 * t
    cyan = _glow(u, v, 0.16, 0.22, 0.72)
    amber = _glow(u, v, 0.86, 0.18, 0.50)
    violet = _glow(u, v, 0.62, 0.94, 0.72)
    r += cyan * 10 + amber * 34 + violet * 15
    g += cyan * 46 + amber * 20 + violet * 12
    b += cyan * 62 + amber * 6 + violet * 40
    sheen = 1.0 / (1.0 + ((u * 0.8 + v - 0.95) / 0.22) ** 2) * 13
    r += sheen; g += sheen; b += sheen
    edge = max(0.0, 1.0 - min(u, 1 - u, v, 1 - v) / 0.10)
    r -= edge * 6; g -= edge * 10; b -= edge * 14
    return _clamp8(r), _clamp8(g), _clamp8(b)


def line(cv, x0, y0, x1, y1, color, width, alpha=255):
    """带圆头端点的抗锯齿线段（超采样画布上按距离计算覆盖率）。"""
    half = width / 2.0
    lo_x = max(0, int(min(x0, x1) - half - 2))
    hi_x = min(cv.w - 1, int(max(x0, x1) + half + 2))
    lo_y = max(0, int(min(y0, y1) - half - 2))
    hi_y = min(cv.h - 1, int(max(y0, y1) + half + 2))
    dx, dy = x1 - x0, y1 - y0
    seg2 = dx * dx + dy * dy or 1.0
    for y in range(lo_y, hi_y + 1):
        for x in range(lo_x, hi_x + 1):
            t = ((x - x0) * dx + (y - y0) * dy) / seg2
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            d = math.hypot(x - (x0 + t * dx), y - (y0 + t * dy))
            cov = half + 0.5 - d
            if cov > 0:
                cv.blend(x, y, *color, min(255, int(alpha * min(1.0, cov))))


def snowflake(cv, cx, cy, radius, color, width, alpha=255):
    """六角雪花：六条主轴 + 每轴两对分支 + 轴端小 V + 中心点。"""
    bw = max(1.0, width * 0.74)
    for i in range(6):
        ang = math.radians(i * 60 - 90)
        ux, uy = math.cos(ang), math.sin(ang)
        tx, ty = cx + ux * radius, cy + uy * radius
        line(cv, cx, cy, tx, ty, color, width, alpha)
        for frac in (0.50, 0.74):
            bx, by = cx + ux * radius * frac, cy + uy * radius * frac
            ln = radius * 0.25 * (1.0 - frac * 0.30)
            for s in (-1, 1):
                a = ang + s * math.radians(52)
                line(cv, bx, by, bx + math.cos(a) * ln, by + math.sin(a) * ln,
                     color, bw, alpha)
        cap = radius * 0.14
        for s in (-1, 1):
            a = ang + s * math.radians(36)
            line(cv, tx, ty, tx - math.cos(a) * cap, ty - math.sin(a) * cap,
                 color, bw, alpha)
    core = max(1.0, radius * 0.085)
    for y in range(int(cy - core - 2), int(cy + core + 3)):
        for x in range(int(cx - core - 2), int(cx + core + 3)):
            d = math.hypot(x - cx, y - cy)
            cov = core + 0.5 - d
            if cov > 0:
                cv.blend(x, y, *color, min(255, int(alpha * min(1.0, cov))))


def glass_badge(cv, cx, cy, r, dark=False):
    """雪花外的液态玻璃圆片：外发光 + 渐变球面 + 上缘高光 + 底部内影。"""
    # 外发光
    outer = int(r * 1.55)
    glow = (90, 165, 255) if not dark else (120, 205, 255)
    for y in range(max(0, int(cy - outer)), min(cv.h, int(cy + outer + 1))):
        for x in range(max(0, int(cx - outer)), min(cv.w, int(cx + outer + 1))):
            d = math.hypot(x - cx, y - cy)
            if d < outer:
                f = (1.0 - d / outer) ** 3
                cv.blend(x, y, *glow, int(70 * f))
    if dark:
        top, bottom = (126, 205, 255), (28, 96, 188)
    else:
        top, bottom = (64, 126, 224), (12, 46, 122)
    for y in range(max(0, int(cy - r)), min(cv.h, int(cy + r + 1))):
        for x in range(max(0, int(cx - r)), min(cv.w, int(cx + r + 1))):
            d = math.hypot(x - cx, y - cy)
            if d > r + 0.6:
                continue
            cov = r + 0.5 - d
            if cov <= 0:
                continue
            t = max(0.0, min(1.0, (y - (cy - r)) / (2 * r)))
            base = tuple(top[k] + (bottom[k] - top[k]) * t for k in range(3))
            # 左上镜面反光
            spec = 1.0 / (1.0 + (((x - cx) / r + (y - cy) / r + 0.85) / 0.42) ** 2)
            col = tuple(_clamp8(base[k] + spec * 58) for k in range(3))
            a = int(255 * min(1.0, cov))
            cv.blend(x, y, *col, a)
    # 上缘亮环 / 下缘暗环
    for deg in range(0, 360):
        a = math.radians(deg)
        for step in (0, 1):
            rr = r - step
            x, y = cx + math.cos(a) * rr, cy + math.sin(a) * rr
            if math.sin(a) < -0.15:
                cv.blend(int(x), int(y), 225, 243, 255, 150 if step == 0 else 70)
            elif math.sin(a) > 0.35:
                cv.blend(int(x), int(y), 5, 22, 68, 90)
    snowflake(cv, cx, cy, r * 0.60, WHITE, max(1.5, r * 0.105))


def sprite_sample(sp, sw, sh, u, v):
    """双线性采样 RGBA 底稿。"""
    fx = max(0.0, min(sw - 1.001, u * (sw - 1)))
    fy = max(0.0, min(sh - 1.001, v * (sh - 1)))
    x0, y0 = int(fx), int(fy)
    x1, y1 = min(x0 + 1, sw - 1), min(y0 + 1, sh - 1)
    ax, ay = fx - x0, fy - y0
    res = []
    for ch in range(4):
        p00 = sp[(y0 * sw + x0) * 4 + ch]
        p10 = sp[(y0 * sw + x1) * 4 + ch]
        p01 = sp[(y1 * sw + x0) * 4 + ch]
        p11 = sp[(y1 * sw + x1) * 4 + ch]
        top = p00 + (p10 - p00) * ax
        bot = p01 + (p11 - p01) * ax
        res.append(top + (bot - top) * ay)
    return res


def draw_sprite(cv, sp, sw, sh, box, recolor_dark=False):
    x0, y0, bw, bh = box
    for y in range(max(0, int(y0)), min(cv.h, int(y0 + bh))):
        v = (y - y0) / bh
        for x in range(max(0, int(x0)), min(cv.w, int(x0 + bw))):
            u = (x - x0) / bw
            r, g, b, a = sprite_sample(sp, sw, sh, u, v)
            if a <= 1:
                continue
            # 深灰 TV 字母 + 抗锯齿灰边统一换冰白：按「低饱和暗色」判定，
            # 不能只比对主色精确值，否则双线性采样出的灰边会漏掉、字会发虚。
            if recolor_dark and max(r, g, b) - min(r, g, b) < 42 \
                    and max(r, g, b) < 205:
                r, g, b = ICE
            cv.blend(x, y, int(r), int(g), int(b), int(a))


def downsample(cv, tw, th):
    """面积平均（box filter）超采样缩小，边缘干净无锯齿。"""
    out = bytearray(tw * th * 4)
    for ty in range(th):
        sy0 = ty * cv.h // th
        sy1 = max(sy0 + 1, (ty + 1) * cv.h // th)
        for tx in range(tw):
            sx0 = tx * cv.w // tw
            sx1 = max(sx0 + 1, (tx + 1) * cv.w // tw)
            r = g = b = a = 0
            n = 0
            for sy in range(sy0, sy1):
                base = sy * cv.w * 4
                for sx in range(sx0, sx1):
                    j = base + sx * 4
                    r += cv.buf[j]; g += cv.buf[j + 1]; b += cv.buf[j + 2]
                    a += cv.buf[j + 3]
                    n += 1
            k = (ty * tw + tx) * 4
            out[k], out[k + 1], out[k + 2], out[k + 3] = r // n, g // n, b // n, a // n
    return out


# ---------------------------------------------------------------- 组装画面

ICON_MASTER = 1024
BANNER_MASTER = (1280, 720)


def icon_master(sp, sw, sh):
    cv = Canvas(ICON_MASTER, ICON_MASTER, light_bg)
    bw = 832
    bh = bw * sh / sw
    box = (96.0, 150.0, bw, bh)
    draw_sprite(cv, sp, sw, sh, box)
    # 参考图右上角空白处放雪花玻璃徽标（略微压住蓝色电视框，像挂角标）
    bcx = box[0] + 190 / 229 * bw
    bcy = box[1] + 46 / 214 * bh
    glass_badge(cv, bcx, bcy, 118, dark=False)
    # 极淡的背景雪花，呼应「飞雪」
    snowflake(cv, 150, 880, 62, (150, 190, 245), 6, 60)
    snowflake(cv, 905, 880, 44, (150, 190, 245), 5, 48)
    return cv


def banner_master(sp, sw, sh):
    w, h = BANNER_MASTER
    cv = Canvas(w, h, dark_bg)
    # 背景飘雪（低透明度，制造景深）
    for (x, y, r, a) in ((150, 150, 68, 24), (1130, 570, 86, 20),
                         (1030, 110, 48, 18), (240, 620, 46, 15)):
        snowflake(cv, x, y, r, (170, 215, 255), max(4, r * 0.070), a)
    bh = h * 0.78
    bw = bh * sw / sh
    box = ((w - bw) / 2, (h - bh) / 2, bw, bh)
    draw_sprite(cv, sp, sw, sh, box, recolor_dark=True)
    bcx = box[0] + 190 / 229 * bw
    bcy = box[1] + 46 / 214 * bh
    glass_badge(cv, bcx, bcy, bh * 34 / 214, dark=True)
    return cv


def transparent_snowflake(size=128):
    cv = Canvas(size * 4, size * 4, lambda x, y, w, h: (0, 0, 0))
    # 手动透明画布
    for i in range(3, len(cv.buf), 4):
        cv.buf[i] = 0
    s = cv.w
    snowflake(cv, s / 2, s / 2, s * 0.39, WHITE, max(2, s * 0.042))
    return downsample(cv, size, size)


def transparent_brand(sp, sw, sh, size=160):
    """界面标题栏用的透明底品牌标记：与 launcher 同款 TV + 雪花，
    深灰 TV 字母换冰白，保证深蓝玻璃墙面上可读。"""
    s = size * 4
    cv = Canvas(s, s, lambda x, y, w, h: (0, 0, 0))
    for i in range(3, len(cv.buf), 4):
        cv.buf[i] = 0
    bw = s * 0.86
    bh = bw * sh / sw
    box = ((s - bw) / 2, (s - bh) / 2 + s * 0.02, bw, bh)
    draw_sprite(cv, sp, sw, sh, box, recolor_dark=True)
    bcx = box[0] + 190 / 229 * bw
    bcy = box[1] + 46 / 214 * bh
    glass_badge(cv, bcx, bcy, s * 0.135, dark=True)
    return downsample(cv, size, size)


def save_variant(cv, path, tw, th):
    write_png_rgba(path, tw, th, downsample(cv, tw, th))
    print("written", os.path.relpath(path, ROOT), f"{tw}x{th}")


def main():
    preview = None
    if "--preview" in sys.argv:
        idx = sys.argv.index("--preview")
        preview = sys.argv[idx + 1]

    sw, sh, sp = unmatte_sprite(REF)
    icon = icon_master(sp, sw, sh)
    banner = banner_master(sp, sw, sh)

    for folder, size in {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }.items():
        save_variant(icon, os.path.join(ROOT, folder, "ic_launcher.png"), size, size)

    save_variant(banner, os.path.join(ROOT, "drawable-xhdpi", "banner.png"), 320, 180)

    mark = os.path.join(ROOT, "drawable-nodpi", "ic_snowflake.png")
    write_png_rgba(mark, 128, 128, transparent_snowflake())
    print("written", os.path.relpath(mark, ROOT), "128x128 (transparent)")

    brand = os.path.join(ROOT, "drawable-nodpi", "ic_brand_tv.png")
    write_png_rgba(brand, 160, 160, transparent_brand(sp, sw, sh))
    print("written", os.path.relpath(brand, ROOT), "160x160 (transparent)")

    if preview:
        # 预览拼图：上排大图标，下排横幅
        pw, ph = 720, 900
        out = bytearray(pw * ph * 4)
        for i in range(0, len(out), 4):
            out[i], out[i + 1], out[i + 2], out[i + 3] = 10, 28, 62, 255
        i256 = downsample(icon, 512, 512)
        b640 = downsample(banner, 640, 360)
        for y in range(512):
            row = (y + 30) * pw * 4
            src = y * 512 * 4
            out[row + 104 * 4:row + 616 * 4] = i256[src:src + 512 * 4]
        for y in range(360):
            row = (y + 570) * pw * 4
            src = y * 640 * 4
            out[row + 40 * 4:row + 680 * 4] = b640[src:src + 640 * 4]
        write_png_rgba(preview, pw, ph, out)
        print("preview", preview)


if __name__ == "__main__":
    main()
