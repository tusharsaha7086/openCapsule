"""Draws the default pet, an original pixel-art cat, and writes its pack: a sprite sheet and a pet.json.

Run:  python tools/make_cat.py
Output: app/src/main/assets/pets/cat/sheet.png and pet.json (plus preview.png beside this script).

The sheet has one row per animation and one 32x32 cell per frame. The cat faces right; the app mirrors it to face left and
turns it to climb, so only a few poses are needed. No libraries are needed (the PNG is written by hand).
"""
import json
import os
import struct
import zlib

N = 32
O = (0x2B, 0x1B, 0x14, 255)   # outline
B = (0xE5, 0x9A, 0x55, 255)   # body
D = (0xB8, 0x69, 0x2A, 255)   # stripes
W = (0xFB, 0xE9, 0xD0, 255)   # belly and paws
P = (0xF2, 0x9C, 0xA3, 255)   # nose and ears
E = (0x1A, 0x1A, 0x1A, 255)   # eyes
H = (0xFF, 0xFF, 0xFF, 255)   # shine
Z = (0x7F, 0xB2, 0xE5, 255)   # sleep letters
R = (0xE8, 0x4A, 0x5F, 255)   # heart
Y = (0xFF, 0xD8, 0x4A, 255)   # stars
M = (0x5A, 0x2A, 0x22, 255)   # open mouth


class Cell:
    def __init__(self):
        self.g = [[None] * N for _ in range(N)]

    def px(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.g[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def clear(self, *pts):
        for x, y in pts:
            self.px(x, y, None)

    def outline(self):
        out = []
        for y in range(N):
            for x in range(N):
                if self.g[y][x] is None and any(
                    0 <= x + dx < N and 0 <= y + dy < N and self.g[y + dy][x + dx] is not None
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                ):
                    out.append((x, y))
        for x, y in out:
            self.g[y][x] = O


TAILS = {
    "up": [(7, 16), (6, 15), (5, 14), (5, 13), (5, 12), (5, 11), (5, 10)],
    "wave": [(7, 16), (6, 16), (5, 15), (4, 14), (4, 13), (5, 12), (6, 12)],
    "down": [(7, 16), (6, 17), (5, 18), (5, 19), (5, 20)],
}


def standing(bob=0, legs=None, tail="up", eyes="open", happy=False, leg_len=6, shift=0):
    c = Cell()
    legs = legs or [(9, 0, 0), (12, 0, 0), (18, 0, 0), (21, 0, 0)]
    b = bob + shift
    pts = TAILS[tail]
    for i, (x, y) in enumerate(pts):
        col = D if i >= len(pts) - 2 else B
        c.rect(x, y + b, x + 1, y + b, col)
    for x, lift, sw in legs:
        top = 22 + b
        bottom = 22 + leg_len - 1 - lift + shift
        c.rect(x + sw, top, x + sw + 1, bottom, B)
        c.rect(x + sw, bottom, x + sw + 1, bottom, W)
    c.rect(8, 14 + b, 22, 22 + b, B)
    c.clear((8, 14 + b), (8, 22 + b))
    c.rect(10, 21 + b, 20, 22 + b, W)
    for x in (11, 14, 17):
        c.rect(x, 14 + b, x, 16 + b, D)
    c.rect(21, 9 + b, 29, 18 + b, B)
    c.clear((21, 9 + b), (29, 9 + b), (29, 18 + b), (21, 18 + b))
    c.rect(27, 15 + b, 29, 17 + b, W)
    for x, y in ((22, 8), (23, 8), (24, 8), (23, 7), (26, 8), (27, 8), (28, 8), (27, 7)):
        c.px(x, y + b, B)
    c.px(23, 8 + b, P)
    c.px(27, 8 + b, P)
    c.outline()
    c.px(29, 15 + b, P)
    if eyes == "open":
        c.rect(26, 12 + b, 26, 13 + b, E)
    elif eyes == "blink":
        c.rect(25, 13 + b, 26, 13 + b, E)
    elif eyes == "happy":
        c.px(25, 13 + b, E)
        c.px(26, 12 + b, E)
        c.px(27, 13 + b, E)
    elif eyes == "wide":
        c.rect(25, 11 + b, 26, 13 + b, E)
        c.px(25, 11 + b, H)
    elif eyes == "x":
        for ex, ey in ((25, 12), (27, 12), (26, 13), (25, 14), (27, 14)):
            c.px(ex, ey + b, E)
    if happy:
        c.px(28, 16 + b, E)
        c.px(29, 16 + b, E)
    return c


def sitting(blink=False, tail_up=False):
    c = Cell()
    tail = [(6, 26), (5, 25), (4, 25), (3, 24), (3, 23)] if tail_up else [(6, 26), (5, 26), (4, 26), (3, 25), (3, 24)]
    for x, y in tail:
        c.rect(x, y, x + 1, y, B)
    c.rect(3, tail[-1][1], 4, tail[-1][1], D)
    c.rect(7, 17, 18, 27, B)
    c.clear((7, 17), (7, 27), (18, 17))
    c.rect(15, 12, 22, 27, B)
    c.rect(18, 22, 22, 27, W)
    for y in (18, 20, 22):
        c.rect(9, y, 10, y, D)
    c.rect(20, 22, 21, 27, B)
    c.rect(20, 27, 21, 27, W)
    c.rect(17, 4, 26, 13, B)
    c.clear((17, 4), (26, 4), (26, 13), (17, 13))
    c.rect(23, 10, 26, 12, W)
    for x, y in ((18, 3), (19, 3), (20, 3), (19, 2), (23, 3), (24, 3), (25, 3), (24, 2)):
        c.px(x, y, B)
    c.px(19, 3, P)
    c.px(24, 3, P)
    c.outline()
    if blink:
        c.rect(22, 8, 23, 8, E)
    else:
        c.rect(23, 7, 23, 8, E)
    c.px(26, 10, P)
    return c


def sleeping(z):
    c = Cell()
    for x, y in [(6, 25), (5, 24), (4, 23), (4, 22), (4, 21)]:
        c.rect(x, y, x + 1, y, B)
    c.rect(4, 21, 5, 21, D)
    c.rect(6, 17, 25, 27, B)
    c.clear((6, 17), (6, 27), (25, 17))
    c.rect(8, 25, 22, 27, W)
    for x in (10, 13, 16):
        c.rect(x, 17, x, 19, D)
    c.rect(19, 18, 28, 27, B)
    c.clear((19, 18), (28, 18), (28, 27))
    c.rect(25, 24, 28, 27, W)
    for x, y in ((20, 16), (21, 16), (22, 16), (21, 15), (25, 16), (26, 16), (27, 16), (26, 15)):
        c.px(x, y, B)
    c.px(21, 16, P)
    c.px(26, 16, P)
    c.outline()
    c.rect(23, 22, 24, 22, E)
    c.px(28, 24, P)
    small = [(24, 9), (25, 9), (26, 9), (25, 10), (24, 11), (25, 11), (26, 11)]
    big = small + [(28, 4), (29, 4), (30, 4), (29, 5), (28, 6), (29, 6), (30, 6)]
    for x, y in (small if z == 0 else big):
        c.px(x, y, Z)
    return c


def happy_pose(f):
    c = standing(tail="up" if f == 0 else "wave", eyes="happy", happy=True)
    if f == 1:
        for x, y in ((24, 2), (26, 2), (23, 3), (24, 3), (25, 3), (26, 3), (27, 3), (24, 4), (25, 4), (26, 4), (25, 5)):
            c.px(x, y, R)
    return c


def falling():
    c = Cell()
    for y in range(4, 14):
        c.rect(7, y, 8, y, D if y < 6 else B)
    c.rect(9, 14, 23, 21, B)
    c.clear((9, 14), (23, 14), (9, 21))
    c.rect(11, 20, 21, 21, W)
    for x in (12, 15, 18):
        c.rect(x, 14, x, 16, D)
    for x, y in ((8, 22), (7, 23), (6, 24), (10, 22), (9, 23), (8, 24), (20, 22), (21, 23), (22, 24), (22, 21), (23, 22), (24, 23)):
        c.rect(x, y, x + 1, y, B)
    for x, y in ((6, 24), (8, 24), (22, 24), (24, 23)):
        c.rect(x, y, x + 1, y, W)
    c.rect(22, 10, 30, 19, B)
    c.clear((22, 10), (30, 10), (30, 19), (22, 19))
    c.rect(28, 16, 30, 18, W)
    for x, y in ((23, 9), (24, 9), (25, 9), (24, 8), (27, 9), (28, 9), (29, 9), (28, 8)):
        c.px(x, y, B)
    c.px(24, 9, P)
    c.px(28, 9, P)
    c.outline()
    c.rect(26, 12, 27, 14, E)
    c.px(26, 12, H)
    c.px(30, 16, P)
    c.rect(28, 17, 29, 17, E)
    return c


def grabbed():
    return standing(tail="down", eyes="wide", leg_len=4, shift=-2)


def dots(c, pts, color):
    for x, y in pts:
        c.px(x, y, color)


NOTE_A = [(29, 2), (29, 3), (29, 4), (29, 5), (27, 5), (28, 5), (27, 6), (28, 6), (30, 2), (31, 3)]
NOTE_B = [(30, 3), (30, 4), (30, 5), (30, 6), (28, 6), (29, 6), (28, 7), (29, 7), (31, 3)]


def dancing(f):
    front = [(9, 0, 0), (12, 0, 0), (18, 0, 0), (21, 0, 0)]
    if f == 0:
        c = standing(bob=0, legs=[(9, 0, 0), (12, 0, 0), (18, 3, 0), (21, 3, 0)], tail="up", eyes="happy", happy=True)
        dots(c, NOTE_A, Z)
    elif f == 1:
        c = standing(bob=1, legs=front, tail="wave", eyes="happy", happy=True)
    elif f == 2:
        c = standing(bob=0, legs=[(9, 3, 0), (12, 3, 0), (18, 0, 0), (21, 0, 0)], tail="up", eyes="happy", happy=True)
        dots(c, NOTE_B, Z)
    else:
        c = standing(bob=1, legs=front, tail="wave", eyes="happy", happy=True)
    return c


def tired(f):
    c = standing(bob=1, tail="down", eyes="blink" if f == 0 else "open")
    if f == 1:
        c.rect(25, 13 + 1, 26, 13 + 1, E)
        c.px(26, 12 + 1, None)
    dots(c, [(30, 9 + f), (30, 10 + f)] if f == 0 else [(30, 11), (30, 12)], Z)
    return c


def yawning(k):
    c = sitting(blink=True)
    if k >= 1:
        c.rect(25, 10, 26, 11 + (k - 1), M)
        c.px(26, 10, M)
    return c


def alert(f):
    if f == 0:
        c = standing(eyes="wide", tail="up")
    else:
        c = standing(eyes="wide", tail="up", leg_len=4, shift=-2)
    dots(c, [(30, 0), (31, 0), (30, 1), (31, 1), (30, 2), (31, 2), (30, 3), (31, 3), (30, 5), (31, 5), (30, 6), (31, 6)], R)
    return c


def purring(f):
    c = sitting()
    # happy closed eyes instead of the open one
    c.px(23, 7, None)
    c.px(23, 8, None)
    for x, y in ((22, 8), (23, 7), (24, 8)):
        c.px(x, y, E)
    heart = [(28, 0), (30, 0), (27, 1), (28, 1), (29, 1), (30, 1), (31, 1), (28, 2), (29, 2), (30, 2), (29, 3)]
    small = [(29, 2), (31, 2), (29, 3), (30, 3), (31, 3), (30, 4)]
    dots(c, heart if f == 0 else small, R)
    return c


def dizzy(f):
    c = standing(bob=f, tail="down", eyes="x")
    dots(c, [(22, 3), (23, 3), (22, 4), (28, 2), (29, 2), (29, 3)] if f == 0 else [(24, 2), (25, 2), (24, 3), (29, 4), (30, 4), (30, 5)], Y)
    return c


def build():
    return [
        ("idle", [standing(), standing(eyes="blink", tail="wave")], 2),
        ("sit", [sitting(), sitting(blink=True, tail_up=True)], 1),
        ("sleep", [sleeping(0), sleeping(1)], 1),
        ("walk", [
            standing(bob=0, legs=[(9, 0, -1), (12, 0, 1), (18, 0, 1), (21, 0, -1)]),
            standing(bob=1, legs=[(9, 2, 0), (12, 0, 0), (18, 0, 0), (21, 2, 0)]),
            standing(bob=0, legs=[(9, 0, 1), (12, 0, -1), (18, 0, -1), (21, 0, 1)]),
            standing(bob=1, legs=[(9, 0, 0), (12, 2, 0), (18, 2, 0), (21, 0, 0)]),
        ], 8),
        ("happy", [happy_pose(0), happy_pose(1)], 4),
        ("fall", [falling()], 1),
        ("grab", [grabbed()], 1),
        ("dance", [dancing(0), dancing(1), dancing(2), dancing(3)], 6),
        ("tired", [tired(0), tired(1)], 2),
        ("yawn", [yawning(0), yawning(1), yawning(2)], 2),
        ("alert", [alert(0), alert(1)], 4),
        ("purr", [purring(0), purring(1)], 3),
        ("dizzy", [dizzy(0), dizzy(1)], 4),
    ]


def write_png(path, w, h, rows):
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in rows)

    def chunk(tag, data):
        body = struct.pack(">I", len(data)) + tag + data
        return body + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)


def main():
    rows = build()
    cols = 4
    w, h = cols * N, len(rows) * N
    pixels = [[(0, 0, 0, 0)] * w for _ in range(h)]
    meta = {}
    for r, (name, frames, fps) in enumerate(rows):
        meta[name] = {"row": r, "frames": len(frames), "fps": fps}
        for f, cell in enumerate(frames):
            for y in range(N):
                for x in range(N):
                    c = cell.g[y][x]
                    if c is not None:
                        pixels[r * N + y][f * N + x] = c
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "app", "src", "main", "assets", "pets", "cat")
    os.makedirs(out, exist_ok=True)
    write_png(os.path.join(out, "sheet.png"), w, h, pixels)
    with open(os.path.join(out, "pet.json"), "w") as f:
        json.dump({"name": "Cat", "frame": N, "animations": meta}, f, indent=2)
        f.write("\n")
    scale = 6
    big = [[pixels[y // scale][x // scale] for x in range(w * scale)] for y in range(h * scale)]
    write_png(os.path.join(here, "preview.png"), w * scale, h * scale, big)
    print("wrote", os.path.normpath(out), w, "x", h)


if __name__ == "__main__":
    main()
