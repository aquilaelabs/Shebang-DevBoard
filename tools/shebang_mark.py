"""The Shebang mark (concept B): # and ! interlocked, the bars cut parallel to the slanted ! with a gap.
Run: python3 tools/shebang_mark.py icon|key|svg (icon: the two path strings for res/drawable/ic_launcher_foreground.xml; key: the path for KeyIcons.SHEBANG; svg: a preview).
Coordinates in the 108 adaptive-icon box; also scaled into the 24 box for the key glyph."""
import math, sys

G = 2.5          # gap between a bar and the !
R = 3.5          # half the bar thickness (round caps)
# The ! : a slanted wedge and a dot.
STEM = [(61.5, 25), (70, 25), (64.5, 64), (57, 64)]
DOT = (59, 75, 5)
def left_edge(y):  return 61.5 - 4.5 * (y - 25) / 39
def right_edge(y): return 70 - 5.5 * (y - 25) / 39

def bar(y, x0, x1):
    """A bar from x0 to x1 (round ends) with the ! cut out of it."""
    t, b = y - R, y + R
    lt, lb = left_edge(t) - G, left_edge(b) - G
    rt, rb = right_edge(t) + G, right_edge(b) + G
    left = [("M", x0, t), ("L", lt, t), ("L", lb, b), ("L", x0, b), ("A", x0, t)]
    right = [("M", rt, t), ("L", x1, t), ("A", x1, b), ("L", rb, b)]
    return left, right

def stroke(a, b, w):
    """A straight stroke from a to b, width w, round ends, as a closed outline."""
    (x0, y0), (x1, y1) = a, b
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy)
    px, py = -dy / n * w / 2, dx / n * w / 2
    # Clockwise, as the bars are, so where they cross the fill adds up instead of cancelling.
    return [("M", x0 - px, y0 - py), ("L", x1 - px, y1 - py), ("A", x1 + px, y1 + py), ("L", x0 + px, y0 + py), ("A", x0 - px, y0 - py)]

def path(cmds, f, r):
    out = []
    for c in cmds:
        x, y = f(c[1], c[2])
        if c[0] == "A":
            out.append(f"A{r:.3g} {r:.3g} 0 0 1 {x:.2f} {y:.2f}")
        else:
            out.append(f"{c[0]}{x:.2f} {y:.2f}")
    return " ".join(out) + " Z"

def shapes():
    b1l, b1r = bar(47, 28, 72)
    b2l, b2r = bar(61, 26, 70)
    hashes = [stroke((46, 33), (40, 75), 2 * R), b1l, b1r, b2l, b2r]
    stem = [("M",) + STEM[0], ("L",) + STEM[1], ("L",) + STEM[2], ("L",) + STEM[3]]
    return hashes, stem

def dot_path(f, s):
    cx, cy, r = DOT
    x, y = f(cx - r, cy)
    x2, _ = f(cx + r, cy)
    rr = r * s
    return f"M{x:.2f} {y:.2f} A{rr:.3g} {rr:.3g} 0 1 1 {x2:.2f} {y:.2f} A{rr:.3g} {rr:.3g} 0 1 1 {x:.2f} {y:.2f} Z"

def render(scale, cx, cy, ox, oy):
    f = lambda x, y: ((x - cx) * scale + ox, (y - cy) * scale + oy)
    hashes, stem = shapes()
    h = " ".join(path(c, f, R * scale) for c in hashes)
    e = path(stem, f, 0) + " " + dot_path(f, scale)
    return h, e

# Mark centre (bounds x 24.5..75.5, y 25..80) and the two targets.
CX, CY = 50, 52.5
if __name__ == "__main__":
    which = sys.argv[1]
    if which == "icon":       # 108 box, 0.9 so it sits inside the adaptive icon's safe circle
        h, e = render(0.9, CX, CY, 54, 54)
        print(h); print(e)
    elif which == "key":      # 24 box
        h, e = render(0.4, CX, CY, 12, 12)
        print(h + " " + e)
    elif which == "svg":
        h, e = render(0.9, CX, CY, 54, 54)
        hk, ek = render(0.4, CX, CY, 12, 12)
        print(f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 108" width="1200" height="540">
<rect width="108" height="108" fill="#1B1F2A"/><path d="{h}" fill="#7BE0A6"/><path d="{e}" fill="#ECEFF4"/>
<rect x="120" width="108" height="108" fill="#252B36" rx="10"/>
<g transform="translate(132 12) scale(3.5)"><path d="{hk} {ek}" fill="#ECEFF4"/></g></svg>''')
