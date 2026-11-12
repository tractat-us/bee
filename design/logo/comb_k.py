"""The honeycomb K: a candidate logo mark, kept for later. Writes comb-k.svg and comb-k-dark.svg beside this file.

Hexagons point up and down. The row count is even and odd rows sit half a cell right, so the
notch follows the grid's own 60-degree diagonal: in to the middle, then back out. Every edge is
whole cells; nothing is clipped and there is no outline. The settled setup is 12 rows, X0=6.
"""
import math
import pathlib

N, X0 = 12, 6          # rows (even); index of the top row's last cell
GAP = 0.45             # inset per cell, in units of the 64-unit height
H = 64                 # height of the mark
A1, A2 = "#ffc94a", "#e8890c"   # gradient, top-left to bottom-right


def hexpts(cx, cy, r):
    return " ".join(
        f"{cx + r * math.cos(math.radians(30 + 60 * i)):.2f},{cy + r * math.sin(math.radians(30 + 60 * i)):.2f}"
        for i in range(6)
    )


def cells(n=N, x0=X0):
    out = []
    for r in range(n):
        off = 0.5 * (r % 2)
        xmax = x0 - 0.5 * r if r < n // 2 else x0 - 0.5 * (n // 2 - 1) + 0.5 * (r - (n // 2 - 1))
        c = 0
        while c + off <= xmax + 1e-9:
            out.append((c + off, r))
            c += 1
    return out


def svg(fill=None):
    r = H / (1.5 * (N - 1) + 2)
    w = math.sqrt(3) * r
    cs = cells()
    width = (max(x for x, _ in cs) + 1) * w
    paint = fill or "url(#g)"
    grad = (f"<defs><linearGradient id='g' gradientUnits='userSpaceOnUse' x1='0' y1='0' x2='64' y2='64'>"
            f"<stop offset='0' stop-color='{A1}'/><stop offset='1' stop-color='{A2}'/></linearGradient></defs>")
    polys = "".join(f"<polygon points='{hexpts(w / 2 + x * w, r + y * 1.5 * r, r - GAP)}'/>" for x, y in cs)
    return (f"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 {width:.1f} {H}'>"
            f"{'' if fill else grad}<g fill='{paint}'>{polys}</g></svg>\n")


if __name__ == "__main__":
    here = pathlib.Path(__file__).parent
    (here / "comb-k.svg").write_text(svg())
    (here / "comb-k-dark.svg").write_text(svg("#1f2328"))
