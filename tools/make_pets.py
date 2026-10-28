"""Draws the app's pets (an original pixel-art cat, dog, monkey and lion) and writes each one's pack: a sprite sheet and a pet.json.

Run:  python tools/make_pets.py
Output: app/src/main/assets/pets/<id>/sheet.png and pet.json for every pet, plus preview.png beside this script.

Every pack has the same animations (idle, sit, sleep, walk, happy, fall, grab, dance, tired, yawn, alert, purr, dizzy): one row per
animation, one 32x32 cell per frame. The pets face right; the app mirrors them to face left, turns them to climb and spins them
when they are thrown, so only these poses are needed. The four pets share one skeleton and differ in colors, ears, snout, mane,
markings and tail (see SPECIES); a "traits" entry in pet.json gives each its own pace. No libraries are needed (the PNG is
written by hand).
"""
import json
import os
import struct
import zlib

N = 32


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)


O = rgb(0x2B1B14)   # outline
E = rgb(0x1A1A1A)   # eyes
H = rgb(0xFFFFFF)   # shine
Z = rgb(0x7FB2E5)   # sleep letters and notes
R = rgb(0xE84A5F)   # hearts and alerts
Y = rgb(0xFFD84A)   # stars
M = rgb(0x5A2A22)   # open mouth
TONGUE = rgb(0xF08A94)


class Species:
    def __init__(self, ident, name, body, dark, light, nose, inner, ears, tails, marks="none", mane=False, snout=0,
                 face=False, tuft=False, tongue=False, traits=None):
        self.id = ident
        self.name = name
        self.B = rgb(body)       # fur
        self.D = rgb(dark)       # stripes, patches, mane, tail tip
        self.W = rgb(light)      # belly, paws and muzzle
        self.N = rgb(nose)
        self.I = rgb(inner)      # inside of the ears
        self.ears = ears         # point, floppy, round or mane
        self.tails = tails
        self.marks = marks       # stripes, patch or none
        self.mane = mane
        self.snout = snout       # extra pixels the muzzle sticks out in front
        self.face = face         # a light patch on the face (the monkey)
        self.tuft = tuft         # a dark tuft at the tail's end (the lion)
        self.tongue = tongue
        self.traits = traits or {}


CAT_TAILS = {
    "up": [(7, 16), (6, 15), (5, 14), (5, 13), (5, 12), (5, 11), (5, 10)],
    "wave": [(7, 16), (6, 16), (5, 15), (4, 14), (4, 13), (5, 12), (6, 12)],
    "down": [(7, 16), (6, 17), (5, 18), (5, 19), (5, 20)],
}
DOG_TAILS = {
    "up": [(7, 16), (6, 15), (6, 14), (6, 13), (6, 12), (7, 11)],
    "wave": [(7, 16), (6, 15), (5, 14), (4, 13), (4, 12), (5, 11)],
    "down": [(7, 16), (6, 17), (6, 18), (6, 19)],
}
MONKEY_TAILS = {
    "up": [(7, 16), (6, 15), (5, 14), (5, 13), (5, 12), (5, 11), (5, 10), (6, 9), (7, 9), (8, 10)],
    "wave": [(7, 16), (6, 16), (5, 15), (4, 14), (4, 13), (4, 12), (5, 11), (6, 11), (7, 12)],
    "down": [(7, 16), (6, 17), (5, 18), (5, 19), (5, 20), (6, 21), (7, 21)],
}
LION_TAILS = {
    "up": [(7, 16), (6, 15), (5, 14), (5, 13), (5, 12), (5, 11)],
    "wave": [(7, 16), (6, 16), (5, 15), (4, 14), (4, 13), (5, 12)],
    "down": [(7, 16), (6, 17), (6, 18), (6, 19), (6, 20)],
}

SPECIES = [
    Species("cat", "Cat", 0xE59A55, 0xB8692A, 0xFBE9D0, 0xF29CA3, 0xF29CA3, "point", CAT_TAILS, marks="stripes",
            traits={"run": 1.0, "climb": 1.0, "climbChance": 55}),
    Species("dog", "Dog", 0xC99655, 0x7B4B2A, 0xFFF1DC, 0x2B1B14, 0xF29CA3, "floppy", DOG_TAILS, marks="patch", snout=2,
            tongue=True, traits={"run": 1.35, "climb": 0.9, "climbChance": 35}),
    Species("monkey", "Monkey", 0x8A5A34, 0x5E3A1F, 0xF0C99B, 0x5E3A1F, 0xF0C99B, "round", MONKEY_TAILS, face=True,
            traits={"run": 1.1, "climb": 1.5, "climbChance": 85}),
    Species("lion", "Lion", 0xD9A441, 0x8A4B1E, 0xF3DDA6, 0x7A3B1E, 0xF3DDA6, "mane", LION_TAILS, mane=True, tuft=True,
            traits={"run": 0.8, "climb": 0.75, "climbChance": 30}),
]

SP = SPECIES[0]


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


def dots(c, pts, color):
    for x, y in pts:
        c.px(x, y, color)


# --- the head, which is where the species differ most ---

def head(c, x0, y0, x1, y1, muzzle, left, right, row, snout=None):
    """Fills the head (a mane behind it, ears and a snout on it). [muzzle] is the light patch's rectangle."""
    sp = SP
    if sp.mane:
        c.rect(x0 - 2, y0 - 2, x1 + 1, y1 + 3, sp.D)
        c.clear((x0 - 2, y0 - 2), (x1 + 1, y0 - 2), (x0 - 2, y1 + 3), (x1 + 1, y1 + 3))
    c.rect(x0, y0, x1, y1, sp.B)
    c.clear((x0, y0), (x1, y0), (x1, y1), (x0, y1))
    if sp.face:
        c.rect(x0 + 3, y0 + 3, x1, y1 - 1, sp.W)
        c.clear((x1, y0 + 3), (x1, y1 - 1))
    mx0, my0, mx1, my1 = muzzle
    c.rect(mx0, my0, mx1, my1, sp.W)
    reach = sp.snout if snout is None else snout
    if reach:
        c.rect(mx1 + 1, my0 + 1, mx1 + reach, my1, sp.W)
    ears(c, x0, y0, x1, left, right, row)


def ears(c, x0, y0, x1, left, right, row):
    sp = SP
    if sp.ears == "point":
        for base in (left, right):
            c.rect(base, row, base + 2, row, sp.B)
            c.px(base + 1, row - 1, sp.B)
            c.px(base + 1, row, sp.I)
    elif sp.ears == "floppy":
        # a long ear hanging down the back of the head, and the far one just showing on top
        c.rect(left, row, left + 2, row, sp.D)
        c.rect(left, row + 1, left + 2, row + 6, sp.D)
        c.rect(right, row, right + 1, row, sp.D)
    elif sp.ears == "round":
        # a round ear on the side of the head and a little one on top
        c.rect(x0 - 2, y0 + 2, x0, y0 + 5, sp.B)
        c.clear((x0 - 2, y0 + 2), (x0 - 2, y0 + 5))
        c.rect(x0 - 1, y0 + 3, x0, y0 + 4, sp.I)
        c.rect(right, row, right + 1, row, sp.B)
    elif sp.ears == "mane":
        for base in (left, right):
            c.rect(base, row - 1, base + 1, row, sp.B)


def face(c, nose_xy, snout=None):
    """The nose, drawn after the outline."""
    x, y = nose_xy
    reach = SP.snout if snout is None else snout
    c.px(x + reach, y, SP.N)


def marks(c, positions, y0, y1):
    sp = SP
    if sp.marks == "stripes":
        for x in positions:
            c.rect(x, y0, x, y1, sp.D)
    elif sp.marks == "patch":
        x = positions[0]
        c.rect(x, y0, x + 3, y1 + 1, sp.D)


def standing(bob=0, legs=None, tail="up", eyes="open", happy=False, leg_len=6, shift=0):
    sp = SP
    c = Cell()
    legs = legs or [(9, 0, 0), (12, 0, 0), (18, 0, 0), (21, 0, 0)]
    b = bob + shift
    pts = sp.tails[tail]
    for i, (x, y) in enumerate(pts):
        col = sp.D if i >= len(pts) - 2 else sp.B
        c.rect(x, y + b, x + 1, y + b, col)
    if sp.tuft:
        x, y = pts[-1]
        c.rect(x - 1, y - 2 + b, x + 1, y + b, sp.D)
    for x, lift, sw in legs:
        top = 22 + b
        bottom = 22 + leg_len - 1 - lift + shift
        c.rect(x + sw, top, x + sw + 1, bottom, sp.B)
        c.rect(x + sw, bottom, x + sw + 1, bottom, sp.W)
    c.rect(8, 14 + b, 22, 22 + b, sp.B)
    c.clear((8, 14 + b), (8, 22 + b))
    c.rect(10, 21 + b, 20, 22 + b, sp.W)
    marks(c, (11, 14, 17), 14 + b, 16 + b)
    head(c, 21, 9 + b, 29, 18 + b, (27, 15 + b, 29, 17 + b), 22, 26, 8 + b)
    c.outline()
    face(c, (29, 15 + b))
    if eyes == "open":
        c.rect(26, 12 + b, 26, 13 + b, E)
    elif eyes == "blink":
        c.rect(25, 13 + b, 26, 13 + b, E)
    elif eyes == "happy":
        dots(c, [(25, 13 + b), (26, 12 + b), (27, 13 + b)], E)
    elif eyes == "wide":
        c.rect(25, 11 + b, 26, 13 + b, E)
        c.px(25, 11 + b, H)
    elif eyes == "x":
        dots(c, [(ex, ey + b) for ex, ey in ((25, 12), (27, 12), (26, 13), (25, 14), (27, 14))], E)
    if happy:
        mouth = 28 + sp.snout
        c.px(mouth, 16 + b, E)
        c.px(mouth + 1, 16 + b, E)
        if sp.tongue:
            c.px(mouth, 17 + b, TONGUE)
    return c


def sitting(blink=False, tail_up=False, happy_eyes=False):
    sp = SP
    c = Cell()
    tail = [(6, 26), (5, 25), (4, 25), (3, 24), (3, 23)] if tail_up else [(6, 26), (5, 26), (4, 26), (3, 25), (3, 24)]
    for x, y in tail:
        c.rect(x, y, x + 1, y, sp.B)
    c.rect(3, tail[-1][1], 4, tail[-1][1], sp.D)
    c.rect(7, 17, 18, 27, sp.B)
    c.clear((7, 17), (7, 27), (18, 17))
    c.rect(15, 12, 22, 27, sp.B)
    c.rect(18, 22, 22, 27, sp.W)
    marks(c, (9, 9, 9), 18, 18)
    if sp.marks == "stripes":
        for y in (20, 22):
            c.rect(9, y, 10, y, sp.D)
    c.rect(20, 22, 21, 27, sp.B)
    c.rect(20, 27, 21, 27, sp.W)
    head(c, 17, 4, 26, 13, (23, 10, 26, 12), 18, 23, 3)
    c.outline()
    face(c, (26, 10))
    if happy_eyes:
        dots(c, [(22, 8), (23, 7), (24, 8)], E)
    elif blink:
        c.rect(22, 8, 23, 8, E)
    else:
        c.rect(23, 7, 23, 8, E)
    return c


def sleeping(z):
    sp = SP
    c = Cell()
    for x, y in [(6, 25), (5, 24), (4, 23), (4, 22), (4, 21)]:
        c.rect(x, y, x + 1, y, sp.B)
    c.rect(4, 21, 5, 21, sp.D)
    c.rect(6, 17, 25, 27, sp.B)
    c.clear((6, 17), (6, 27), (25, 17))
    c.rect(8, 25, 22, 27, sp.W)
    marks(c, (10, 13, 16), 17, 19)
    head(c, 19, 18, 28, 27, (25, 24, 28, 27), 20, 25, 16, snout=1 if sp.snout else 0)
    c.outline()
    face(c, (28, 24), snout=1 if sp.snout else 0)
    c.rect(23, 22, 24, 22, E)
    small = [(24, 9), (25, 9), (26, 9), (25, 10), (24, 11), (25, 11), (26, 11)]
    big = small + [(28, 4), (29, 4), (30, 4), (29, 5), (28, 6), (29, 6), (30, 6)]
    dots(c, small if z == 0 else big, Z)
    return c


def falling():
    sp = SP
    c = Cell()
    for y in range(4, 14):
        c.rect(7, y, 8, y, sp.D if y < 6 else sp.B)
    c.rect(9, 14, 23, 21, sp.B)
    c.clear((9, 14), (23, 14), (9, 21))
    c.rect(11, 20, 21, 21, sp.W)
    marks(c, (12, 15, 18), 14, 16)
    for x, y in ((8, 22), (7, 23), (6, 24), (10, 22), (9, 23), (8, 24), (20, 22), (21, 23), (22, 24), (22, 21), (23, 22), (24, 23)):
        c.rect(x, y, x + 1, y, sp.B)
    for x, y in ((6, 24), (8, 24), (22, 24), (24, 23)):
        c.rect(x, y, x + 1, y, sp.W)
    head(c, 22, 10, 30, 19, (28, 16, 30, 18), 23, 27, 9, snout=1 if sp.snout else 0)
    c.outline()
    face(c, (30, 16), snout=1 if sp.snout else 0)
    c.rect(26, 12, 27, 14, E)
    c.px(26, 12, H)
    c.rect(28, 17, 29, 17, E)
    return c


def grabbed():
    return standing(tail="down", eyes="wide", leg_len=4, shift=-2)


NOTE_A = [(29, 2), (29, 3), (29, 4), (29, 5), (27, 5), (28, 5), (27, 6), (28, 6), (30, 2), (31, 3)]
NOTE_B = [(30, 3), (30, 4), (30, 5), (30, 6), (28, 6), (29, 6), (28, 7), (29, 7), (31, 3)]


def dancing(f):
    base = [(9, 0, 0), (12, 0, 0), (18, 0, 0), (21, 0, 0)]
    if f == 0:
        c = standing(legs=[(9, 0, 0), (12, 0, 0), (18, 3, 0), (21, 3, 0)], tail="up", eyes="happy", happy=True)
        dots(c, NOTE_A, Z)
    elif f == 1:
        c = standing(bob=1, legs=base, tail="wave", eyes="happy", happy=True)
    elif f == 2:
        c = standing(legs=[(9, 3, 0), (12, 3, 0), (18, 0, 0), (21, 0, 0)], tail="up", eyes="happy", happy=True)
        dots(c, NOTE_B, Z)
    else:
        c = standing(bob=1, legs=base, tail="wave", eyes="happy", happy=True)
    return c


def tired(f):
    c = standing(bob=1, tail="down", eyes="blink" if f == 0 else "open")
    if f == 1:
        c.rect(25, 14, 26, 14, E)
        c.px(26, 13, None)
    dots(c, [(30, 9), (30, 10)] if f == 0 else [(30, 11), (30, 12)], Z)
    return c


def yawning(k):
    c = sitting(blink=True)
    if k >= 1:
        reach = SP.snout
        c.rect(25, 10, 26 + reach, 11 + (k - 1), M)
        c.px(26 + reach, 10, M)
    return c


def alert(f):
    c = standing(eyes="wide", tail="up") if f == 0 else standing(eyes="wide", tail="up", leg_len=4, shift=-2)
    dots(c, [(30, 0), (31, 0), (30, 1), (31, 1), (30, 2), (31, 2), (30, 3), (31, 3), (30, 5), (31, 5), (30, 6), (31, 6)], R)
    return c


def purring(f):
    c = sitting(happy_eyes=True)
    heart = [(28, 0), (30, 0), (27, 1), (28, 1), (29, 1), (30, 1), (31, 1), (28, 2), (29, 2), (30, 2), (29, 3)]
    small = [(29, 2), (31, 2), (29, 3), (30, 3), (31, 3), (30, 4)]
    dots(c, heart if f == 0 else small, R)
    return c


def dizzy(f):
    c = standing(bob=f, tail="down", eyes="x")
    stars = [(22, 3), (23, 3), (22, 4), (28, 2), (29, 2), (29, 3)] if f == 0 else [(24, 2), (25, 2), (24, 3), (29, 4), (30, 4), (30, 5)]
    dots(c, stars, Y)
    return c


def happy_pose(f):
    c = standing(tail="up" if f == 0 else "wave", eyes="happy", happy=True)
    if f == 1:
        dots(c, [(24, 2), (26, 2), (23, 3), (24, 3), (25, 3), (26, 3), (27, 3), (24, 4), (25, 4), (26, 4), (25, 5)], R)
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


def sheet_of(rows):
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
    return w, h, pixels, meta


def main():
    global SP
    here = os.path.dirname(os.path.abspath(__file__))
    previews = []
    for species in SPECIES:
        SP = species
        w, h, pixels, meta = sheet_of(build())
        out = os.path.join(here, "..", "app", "src", "main", "assets", "pets", species.id)
        os.makedirs(out, exist_ok=True)
        write_png(os.path.join(out, "sheet.png"), w, h, pixels)
        with open(os.path.join(out, "pet.json"), "w") as f:
            json.dump({"name": species.name, "frame": N, "traits": species.traits, "animations": meta}, f, indent=2)
            f.write("\n")
        previews.append((w, h, pixels))
        print("wrote", species.id, w, "x", h)
    # a preview with the four sheets side by side, at 4x
    scale = 4
    gap = 2
    total_w = sum(w for w, _, _ in previews) + gap * (len(previews) - 1)
    height = max(h for _, h, _ in previews)
    canvas = [[(24, 24, 24, 255)] * total_w for _ in range(height)]
    x_off = 0
    for w, h, pixels in previews:
        for y in range(h):
            for x in range(w):
                if pixels[y][x][3]:
                    canvas[y][x_off + x] = pixels[y][x]
        x_off += w + gap
    big = [[canvas[y // scale][x // scale] for x in range(total_w * scale)] for y in range(height * scale)]
    write_png(os.path.join(here, "preview.png"), total_w * scale, height * scale, big)


if __name__ == "__main__":
    main()
