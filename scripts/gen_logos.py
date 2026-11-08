"""
生成 18 个央视频道内置台标 PNG（无第三方依赖，纯标准库 + 5x7 点阵字体）。

输出：app/src/main/res/drawable-nodpi/logo_cctv1.png ... logo_cctv17.png、logo_cctv5plus.png
图形：深色圆角卡 + 左侧频道色条 + "CCTV" 小字 + 大号频道号。
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
OUT_DIR = os.path.join(ROOT, "drawable-nodpi")

W, H = 200, 120
SS = 3  # 超采样倍数，用于抗锯齿

OUTSIDE = (11, 14, 19)
CARD_TOP = (25, 31, 42)
CARD_BOTTOM = (16, 20, 28)
MUTED = (146, 158, 180)
WHITE = (238, 243, 252)

# 5x7 点阵字库
FONT = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
}

# 18 个频道各自的强调色（顺序与 CctvPreset 一致）
ACCENTS = [
    (76, 141, 255),   # 1 综合
    (240, 178, 74),   # 2 财经
    (196, 118, 255),  # 3 综艺
    (86, 196, 214),   # 4 中文国际
    (92, 206, 132),   # 5 体育
    (56, 186, 176),   # 5+
    (232, 122, 138),  # 6 电影
    (128, 158, 196),  # 7 国防军事
    (222, 146, 96),   # 8 电视剧
    (140, 176, 120),  # 9 纪录
    (108, 164, 232),  # 10 科教
    (206, 152, 196),  # 11 戏曲
    (150, 152, 210),  # 12 社会与法
    (232, 106, 106),  # 13 新闻
    (246, 184, 108),  # 14 少儿
    (176, 132, 226),  # 15 音乐
    (96, 190, 220),   # 16 奥林匹克
    (132, 196, 108),  # 17 农业农村
]

LOGOS = [(str(i), "logo_cctv%d" % i) for i in range(1, 18)]
LOGOS.insert(5, ("5+", "logo_cctv5plus"))  # 与 CctvPreset 顺序一致


def write_png(path, w, h, pixel_fn):
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            r, g, b = pixel_fn(x, y)
            row += bytes((r, g, b))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def text_hit(text, px, py, ox, oy, scale, spacing):
    step = 5 * scale + spacing
    for i, ch in enumerate(text):
        rows = FONT.get(ch)
        if rows is None:
            continue
        gx = int((px - ox - i * step) // scale)
        gy = int((py - oy) // scale)
        if 0 <= gx < 5 and 0 <= gy < 7 and rows[gy][gx] == "1":
            return True
    return False


def make_renderer(code, accent):
    small_scale, small_gap = 3.1, 3.0
    big_scale, big_gap = 8.4, 5.0
    small_w = 4 * (5 * small_scale + small_gap) - small_gap
    big_w = len(code) * (5 * big_scale + big_gap) - big_gap
    small_ox = (W - small_w) / 2
    big_ox = (W - big_w) / 2
    radius = 16.0

    def rounded(px, py):
        cx = min(max(px, radius), W - radius)
        cy = min(max(py, radius), H - radius)
        return (px - cx) ** 2 + (py - cy) ** 2 <= radius ** 2

    def shade(px, py):
        if not rounded(px, py):
            return OUTSIDE
        if px < 7:
            return accent
        if text_hit("CCTV", px, py, small_ox, 15, small_scale, small_gap):
            return MUTED
        if text_hit(code, px, py, big_ox, 45, big_scale, big_gap):
            return WHITE
        t = py / max(1.0, H - 1)
        return tuple(int(CARD_TOP[i] + (CARD_BOTTOM[i] - CARD_TOP[i]) * t) for i in range(3))

    def pixel(x, y):
        acc = [0, 0, 0]
        for sy in range(SS):
            for sx in range(SS):
                px = x + (sx + 0.5) / SS
                py = y + (sy + 0.5) / SS
                color = shade(px, py)
                for i in range(3):
                    acc[i] += color[i]
        total = SS * SS
        return tuple(min(255, acc[i] // total) for i in range(3))

    return pixel


def main():
    for (code, name), accent in zip(LOGOS, ACCENTS):
        write_png(os.path.join(OUT_DIR, name + ".png"), W, H, make_renderer(code, accent))
    print("generated %d logos in %s" % (len(LOGOS), OUT_DIR))


if __name__ == "__main__":
    main()
