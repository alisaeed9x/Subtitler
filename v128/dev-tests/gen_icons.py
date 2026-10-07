import math, re, sys
from svgpathtools import parse_path, Line, CubicBezier, QuadraticBezier, Arc

# ---------- helpers: SVG-path strings ----------
def n(v): return ('%.2f' % v).rstrip('0').rstrip('.')
def circ(cx, cy, r): return f"M{n(cx-r)} {n(cy)}A{n(r)} {n(r)} 0 1 0 {n(cx+r)} {n(cy)}A{n(r)} {n(r)} 0 1 0 {n(cx-r)} {n(cy)}Z"
def ell(cx, cy, rx, ry): return f"M{n(cx-rx)} {n(cy)}A{n(rx)} {n(ry)} 0 1 0 {n(cx+rx)} {n(cy)}A{n(rx)} {n(ry)} 0 1 0 {n(cx-rx)} {n(cy)}Z"
def rr(x, y, w, h, r=1.5):
    return (f"M{n(x+r)} {n(y)}H{n(x+w-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w)} {n(y+r)}V{n(y+h-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w-r)} {n(y+h)}"
            f"H{n(x+r)}A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y+h-r)}V{n(y+r)}A{n(r)} {n(r)} 0 0 1 {n(x+r)} {n(y)}Z")
def ln(x1, y1, x2, y2): return f"M{n(x1)} {n(y1)}L{n(x2)} {n(y2)}"
def pl(*pts, close=False):
    s = "M" + "L".join(f"{n(x)} {n(y)}" for x, y in pts)
    return s + ("Z" if close else "")
def arc_pts(cx, cy, r, a0, a1):
    a0r, a1r = math.radians(a0), math.radians(a1)
    p0 = (cx + r*math.cos(a0r), cy + r*math.sin(a0r)); p1 = (cx + r*math.cos(a1r), cy + r*math.sin(a1r))
    large = 1 if abs(a1-a0) > 180 else 0; sweep = 1 if a1 > a0 else 0
    return p0, p1, f"M{n(p0[0])} {n(p0[1])}A{n(r)} {n(r)} 0 {large} {sweep} {n(p1[0])} {n(p1[1])}"
def arc(cx, cy, r, a0, a1): return arc_pts(cx, cy, r, a0, a1)[2]
def head(px, py, dx, dy, s=4.2, spread=38):
    """arrowhead at (px,py) pointing in direction (dx,dy)"""
    L = math.hypot(dx, dy); dx, dy = dx/L, dy/L
    out = []
    for sg in (+1, -1):
        a = math.radians(180 + sg*spread)
        rx = dx*math.cos(a) - dy*math.sin(a); ry = dx*math.sin(a) + dy*math.cos(a)
        out.append((px + rx*s, py + ry*s))
    return f"M{n(out[0][0])} {n(out[0][1])}L{n(px)} {n(py)}L{n(out[1][0])} {n(out[1][1])}"
def arc_arrow(cx, cy, r, a0, a1, s=4.2):
    """clockwise (a1>a0) arc with an arrowhead at a1"""
    p0, p1, d = arc_pts(cx, cy, r, a0, a1)
    a = math.radians(a1); sg = 1 if a1 > a0 else -1
    tx, ty = -math.sin(a)*sg, math.cos(a)*sg
    return d + head(p1[0], p1[1], tx, ty, s)
def star(cx, cy, R, r, k, rot=-90):
    pts = []
    for i in range(k*2):
        a = math.radians(rot + i*180/k); rad = R if i % 2 == 0 else r
        pts.append((cx + rad*math.cos(a), cy + rad*math.sin(a)))
    return pl(*pts, close=True)
def gear(cx, cy, Ro, Ri, teeth):
    pts = []
    for i in range(teeth):
        base = i*360/teeth
        for a, rad in ((-0.20, Ri), (-0.13, Ro), (0.13, Ro), (0.20, Ri)):
            ang = math.radians(base + a*360/teeth*1.25 - 90)
            pts.append((cx + rad*math.cos(ang), cy + rad*math.sin(ang)))
    return pl(*pts, close=True)

# ---------- colors ----------
GREEN = 0xFF4CAF50; RED = 0xFFEF5350; AMBER = 0xFFFFB300; BLUE = 0xFF42A5F5; ORANGE = 0xFFFF7043
YELLOW = 0xFFFFD54F; WHITE = 0xFFFFFFFF; DARK = 0xFF3A2A00; PINK = 0xFFF06292; GOLD = 0xFFFFC107

# layer: (mode, path, color|None, alpha)  mode: S stroke, F fill, FS fill+stroke(round join)
def S(d, c=None, a=1.0): return ('S', d, c, a)
def F(d, c=None, a=1.0): return ('F', d, c, a)
def FS(d, c=None, a=1.0): return ('FS', d, c, a)

I = {}
def speaker(): return FS("M3.5 9.5H7.5L12.5 5V19L7.5 14.5H3.5Z")
tri_r = lambda: pl((7, 4.5), (19.5, 12), (7, 19.5), close=True)

I['play'] = [FS(tri_r())]
I['play_l'] = [FS(pl((17, 4.5), (4.5, 12), (17, 19.5), close=True))]
I['pause'] = [F(rr(5.5, 4.5, 4.4, 15, 1.2)), F(rr(14.1, 4.5, 4.4, 15, 1.2))]
I['stop'] = [F(rr(5.5, 5.5, 13, 13, 2.2))]
I['prev'] = [F(rr(4.5, 5, 2.8, 14, 1.2)), FS(pl((19.5, 5), (8.8, 12), (19.5, 19), close=True))]
I['next'] = [F(rr(16.7, 5, 2.8, 14, 1.2)), FS(pl((4.5, 5), (15.2, 12), (4.5, 19), close=True))]
I['playpause'] = [FS(pl((3.5, 5), (11, 12), (3.5, 19), close=True)), F(rr(14, 5, 2.8, 14, 1.2)), F(rr(18.4, 5, 2.8, 14, 1.2))]
I['ff'] = [FS(pl((3, 6), (11, 12), (3, 18), close=True)), FS(pl((12, 6), (20.5, 12), (12, 18), close=True))]
I['rew'] = [FS(pl((21, 6), (13, 12), (21, 18), close=True)), FS(pl((12, 6), (3.5, 12), (12, 18), close=True))]
I['up2'] = [S(pl((5.5, 12), (12, 6), (18.5, 12))), S(pl((5.5, 19), (12, 13), (18.5, 19)))]
I['check_c'] = [F(circ(12, 12, 10), GREEN), S(pl((7, 12.5), (10.5, 16), (17, 8.5)), WHITE)]
I['check'] = [S(pl((4.5, 12.5), (9.5, 17.5), (19.5, 6.5)))]
I['check_box'] = [S(rr(3.5, 3.5, 17, 17, 3.5)), S(pl((7.5, 12), (10.8, 15.3), (16.5, 8.8)))]
I['box'] = [S(rr(3.5, 3.5, 17, 17, 3.5))]
I['cross'] = [S(ln(6, 6, 18, 18) + ln(18, 6, 6, 18))]
I['cross_c'] = [F(circ(12, 12, 10), RED), S(ln(8.2, 8.2, 15.8, 15.8) + ln(15.8, 8.2, 8.2, 15.8), WHITE)]
I['no_entry'] = [F(circ(12, 12, 10), RED), F(rr(6, 10.2, 12, 3.6, 1.3), WHITE)]
I['prohibited'] = [S(circ(12, 12, 9), RED), S(ln(5.6, 18.4, 18.4, 5.6), RED)]
I['warn'] = [FS(pl((12, 3), (21.6, 19.8), (2.4, 19.8), close=True), AMBER), S(ln(12, 9, 12, 13.8), DARK), F(circ(12, 16.8, 1.3), DARK)]
I['question'] = [F(circ(12, 12, 10), BLUE),
                 S("M9.3 9.6C9.3 8 10.5 7 12 7C13.6 7 14.8 8 14.8 9.4C14.8 11.5 12 11.6 12 14", WHITE), F(circ(12, 17.3, 1.25), WHITE)]
I['red_dot'] = [F(circ(12, 12, 8), RED)]
I['dot'] = [F(circ(12, 12, 5))]
I['info'] = [F(circ(12, 12, 10), BLUE), S(ln(12, 11, 12, 17), WHITE), F(circ(12, 7.6, 1.35), WHITE)]
I['hourglass'] = [S(ln(6, 3.5, 18, 3.5) + ln(6, 20.5, 18, 20.5), AMBER),
                  S("M7.5 3.5V7.5L12 12L7.5 16.5V20.5M16.5 3.5V7.5L12 12L16.5 16.5V20.5", AMBER),
                  F(pl((9, 19), (15, 19), (12, 15.5), close=True), AMBER)]
I['clock'] = [S(circ(12, 12, 9)), S(pl((12, 7), (12, 12), (15.6, 14.2)))]
I['stopwatch'] = [S(circ(12, 13.5, 7.8)), S(ln(12, 13.5, 12, 9.4)), S(ln(9.5, 2.8, 14.5, 2.8)), S(ln(12, 2.8, 12, 5.7)), S(ln(18.3, 7.2, 19.7, 5.8))]
I['battery'] = [S(rr(3, 7.5, 16, 9, 2.2)), F(rr(5.5, 10, 8, 4, 0.8)), F(rr(20, 10.5, 1.8, 3, 0.6))]
I['burst'] = [FS(star(12, 12, 10, 5.2, 8, -90), AMBER)]
I['menu'] = [S(ln(4, 6.5, 20, 6.5) + ln(4, 12, 20, 12) + ln(4, 17.5, 20, 17.5))]
I['chev_l'] = [S(pl((15, 5), (8, 12), (15, 19)))]
I['chev_r'] = [S(pl((9, 5), (16, 12), (9, 19)))]
I['chev_d'] = [S(pl((5, 9), (12, 16), (19, 9)))]
I['chev_u'] = [S(pl((5, 15), (12, 8), (19, 15)))]
I['fullscreen'] = [S(pl((4, 9), (4, 4), (9, 4)) + pl((15, 4), (20, 4), (20, 9)) + pl((20, 15), (20, 20), (15, 20)) + pl((9, 20), (4, 20), (4, 15)))]
I['fit'] = [S(rr(3.5, 5.5, 17, 13, 2.5)), F(rr(7.2, 9, 9.6, 6, 1.2))]
I['expand'] = [S(ln(14, 10, 20, 4) + pl((15, 4), (20, 4), (20, 9)) + ln(10, 14, 4, 20) + pl((4, 15), (4, 20), (9, 20)))]
I['pip'] = [S(rr(3.5, 3.5, 12, 12, 2.2)), F(rr(9, 9, 11.5, 11.5, 2.5), None, 0.3), S(rr(9, 9, 11.5, 11.5, 2.5))]
I['box_tall'] = [S(rr(7.5, 3.5, 9, 17, 2.2))]
I['box_s'] = [S(rr(6.5, 6.5, 11, 11, 2))]
I['box_in'] = [S(rr(3.5, 3.5, 17, 17, 3)), F(rr(8, 8, 8, 8, 1.5))]
I['up'] = [S(ln(12, 20, 12, 5) + pl((6, 11), (12, 5), (18, 11)))]
I['down'] = [S(ln(12, 4, 12, 19) + pl((6, 13), (12, 19), (18, 13)))]
I['swap_h'] = [S(ln(4, 12, 20, 12) + pl((8, 7.5), (3.5, 12), (8, 16.5)) + pl((16, 7.5), (20.5, 12), (16, 16.5)))]
I['swap_v'] = [S(ln(12, 4, 12, 20) + pl((7.5, 8), (12, 3.5), (16.5, 8)) + pl((7.5, 16), (12, 20.5), (16.5, 16)))]
I['undo'] = [S(pl((9, 7.5), (4.5, 12), (9, 16.5)) + "M5 12H14.5C18 12 20 14 20 17V19")]
I['rotate'] = [S(arc_arrow(12, 12, 7.5, -50, 255, 4.4))]
I['plus'] = [S(ln(12, 5, 12, 19) + ln(5, 12, 19, 12))]
I['sparkle'] = [FS(pl((12, 2), (14.4, 9.6), (22, 12), (14.4, 14.4), (12, 22), (9.6, 14.4), (2, 12), (9.6, 9.6), close=True))]
I['sparkles'] = [FS(pl((10, 3.5), (12, 10), (18.5, 12), (12, 14), (10, 20.5), (8, 14), (1.5, 12), (8, 10), close=True), GOLD),
                 FS(pl((19, 3), (19.9, 5.1), (22, 6), (19.9, 6.9), (19, 9), (18.1, 6.9), (16, 6), (18.1, 5.1), close=True), GOLD),
                 FS(pl((19, 15), (19.8, 16.8), (21.6, 17.6), (19.8, 18.4), (19, 20.2), (18.2, 18.4), (16.4, 17.6), (18.2, 16.8), close=True), GOLD)]
I['flame'] = [FS("M12 2.5C13 6 18 8.5 18 14.5C18 18.5 15.5 21.5 12 21.5C8.5 21.5 6 18.5 6 15C6 12.5 7.5 11 8.5 9.5C9.2 11 10 11.5 11 11.5C10.5 8 11 5 12 2.5Z", ORANGE),
                F("M12 21.5C10.3 21.5 9 20 9 18.3C9 16.5 10.5 15.5 12 13.5C13.5 15.5 15 16.5 15 18.3C15 20 13.7 21.5 12 21.5Z", YELLOW)]
I['bolt'] = [FS(pl((13.5, 2), (5, 13.5), (11, 13.5), (10, 22), (19, 10), (13, 10), close=True), YELLOW)]
I['wave'] = [S("M2.5 8.5C4.8 5.5 7.2 5.5 9.5 8.5C11.8 11.5 14.2 11.5 16.5 8.5C18.8 5.5 21 6 22 7.5"),
             S("M2.5 15.5C4.8 12.5 7.2 12.5 9.5 15.5C11.8 18.5 14.2 18.5 16.5 15.5C18.8 12.5 21 13 22 14.5")]
I['fog'] = [S(ln(4, 7, 20, 7) + ln(7, 12, 21, 12) + ln(3, 17, 17, 17))]
I['moon'] = [FS("M20.5 14.2A8.8 8.8 0 1 1 9.8 3.5A7 7 0 0 0 20.5 14.2Z")]
I['sun'] = [S(circ(12, 12, 4.2))] + [S(ln(12 + 7.2*math.cos(math.radians(a)), 12 + 7.2*math.sin(math.radians(a)),
                                          12 + 9.6*math.cos(math.radians(a)), 12 + 9.6*math.sin(math.radians(a)))) for a in range(0, 360, 45)]
EYE = "M2 12C5 6.8 8.5 5 12 5C15.5 5 19 6.8 22 12C19 17.2 15.5 19 12 19C8.5 19 5 17.2 2 12Z"
I['eye'] = [S(EYE), F(circ(12, 12, 3.4))]
I['eye_off'] = [S(EYE, None, 0.9), F(circ(12, 12, 3.2), None, 0.9), S(ln(4.5, 4.5, 19.5, 19.5))]
I['lock'] = [F(rr(5, 10.5, 14, 10.5, 2.6) + circ(12, 15.3, 1.7)), S("M8 10.5V7.5A4 4 0 0 1 16 7.5V10.5")]
I['unlock'] = [F(rr(5, 10.5, 14, 10.5, 2.6) + circ(12, 15.3, 1.7)), S("M8 10.5V7.5A4 4 0 0 1 15.6 6.2")]
I['key'] = [S(circ(8, 15.5, 4.3)), S(ln(11.1, 12.4, 20.5, 3) + ln(17, 6.5, 19.6, 9.1) + ln(14.3, 9.2, 16.4, 11.3))]
I['shield'] = [S("M12 2.8L20 5.8V11.5C20 16.4 16.6 19.9 12 21.4C7.4 19.9 4 16.4 4 11.5V5.8Z"), S(pl((8.4, 12), (11, 14.6), (15.8, 9.6)))]
I['mute'] = [speaker(), S(ln(16, 9.5, 21, 14.5) + ln(21, 9.5, 16, 14.5))]
I['vol_low'] = [speaker(), S("M15.8 9.5C17.1 10.7 17.1 13.3 15.8 14.5")]
I['vol'] = [speaker(), S("M15.8 9.5C17.1 10.7 17.1 13.3 15.8 14.5"), S("M18.4 6.8C21.4 9.6 21.4 14.4 18.4 17.2")]
I['bell_off'] = [S("M6 16.5V11C6 7.8 8.5 5.5 12 5.5C15.5 5.5 18 7.8 18 11V16.5L19.5 18H4.5Z"), S(ln(10.5, 20.5, 13.5, 20.5)), S(ln(4, 4, 20, 20))]
I['mic'] = [FS(rr(9, 3, 6, 11, 3)), S("M5.5 11C5.5 15 8.2 17.5 12 17.5C15.8 17.5 18.5 15 18.5 11"), S(ln(12, 17.5, 12, 21) + ln(8.5, 21, 15.5, 21))]
I['headphones'] = [S("M4 15V12A8 8 0 0 1 20 12V15"), F(rr(3, 13.5, 4.6, 7.5, 2)), F(rr(16.4, 13.5, 4.6, 7.5, 2))]
I['note'] = [F(circ(8, 18, 3.3)), S(ln(11.3, 18, 11.3, 4.5)), F(pl((11.3, 4.5), (18, 6.8), (18, 10.4), (11.3, 8.2), close=True))]
I['note2'] = [F(circ(6.8, 18, 3)), F(circ(16.8, 16, 3)), S(ln(9.8, 18, 9.8, 6.8) + ln(19.8, 16, 19.8, 4.8)), F(pl((9.8, 6.8), (19.8, 4.8), (19.8, 8.4), (9.8, 10.4), close=True))]
I['clapper'] = [S(rr(3, 10, 18, 10.5, 2)), S("M3.3 10L2.8 6L19.6 3.4L20.4 7.2L21 10"), S(ln(7.4, 4.8, 8.8, 8.8) + ln(12.6, 4.1, 14, 8.1))]
I['film'] = [S(rr(3, 4, 18, 16, 2.5)), S(ln(8, 4, 8, 20) + ln(16, 4, 16, 20) + ln(3, 9, 8, 9) + ln(3, 15, 8, 15) + ln(16, 9, 21, 9) + ln(16, 15, 21, 15))]
I['radio'] = [S(rr(3, 8, 18, 12, 2.5)), S(ln(7, 8, 17, 3.5)), S(circ(16, 14, 2.8)), S(ln(6.5, 12.5, 10.5, 12.5) + ln(6.5, 15.5, 10.5, 15.5))]
I['signal'] = [F(rr(4, 14.5, 3.2, 5.5, 0.9)), F(rr(9, 10.5, 3.2, 9.5, 0.9)), F(rr(14, 6.5, 3.2, 13.5, 0.9)), F(rr(19, 2.5, 3.2, 17.5, 0.9), None, 0.45)]
FOLDER = "M3 6.5C3 5.4 3.9 4.5 5 4.5H9.5L12 7H19C20.1 7 21 7.9 21 9V18C21 19.1 20.1 20 19 20H5C3.9 20 3 19.1 3 18Z"
I['folder'] = [F(FOLDER, None, 0.25), S(FOLDER)]
I['folder_open'] = [S("M3 18V6.5C3 5.4 3.9 4.5 5 4.5H9.5L12 7H18C19.1 7 20 7.9 20 9V10.5"), F("M3.4 19.5L6 11.8C6.2 11 6.9 10.5 7.7 10.5H21C21.9 10.5 22.4 11.3 22.1 12.1L19.9 18.2C19.6 19 18.9 19.5 18.1 19.5Z", None, 0.22), S("M3.4 19.5L6 11.8C6.2 11 6.9 10.5 7.7 10.5H21C21.9 10.5 22.4 11.3 22.1 12.1L19.9 18.2C19.6 19 18.9 19.5 18.1 19.5Z")]
I['folders'] = [S(rr(3, 8.5, 18, 12, 2.2)), S("M3 8.5V6.8C3 5.8 3.8 5 4.8 5H9L11 7H16.8C17.7 7 18.5 7.8 18.5 8.7"), F(rr(3, 8.5, 18, 12, 2.2), None, 0.2)]
I['note_edit'] = [S("M12.5 4.5H6C4.9 4.5 4 5.4 4 6.5V18C4 19.1 4.9 20 6 20H17.5C18.6 20 19.5 19.1 19.5 18V11.5"), S(pl((17.5, 3), (21, 6.5), (12, 15.5), (8.2, 16.3), (9, 12.5), close=True))]
I['clipboard'] = [S(rr(5, 5, 14, 16.5, 2.5)), FS(rr(8.5, 2.5, 7, 4.5, 1.5)), S(ln(8.5, 11.5, 15.5, 11.5) + ln(8.5, 15.5, 13.5, 15.5))]
I['scroll'] = [S("M6 3H14.5L19 7.5V21H6ZM14.5 3V7.5H19"), S(ln(9, 12, 16, 12) + ln(9, 16, 16, 16))]
TRAY = "M4 15V19C4 19.6 4.4 20 5 20H19C19.6 20 20 19.6 20 19V15"
I['upload'] = [S(TRAY), S(ln(12, 15, 12, 4) + pl((7.5, 8.5), (12, 4), (16.5, 8.5)))]
I['download'] = [S(TRAY), S(ln(12, 4, 12, 15) + pl((7.5, 10.5), (12, 15), (16.5, 10.5)))]
I['save'] = [S("M5 3.5H16.5L20.5 7.5V19C20.5 19.8 19.8 20.5 19 20.5H5C4.2 20.5 3.5 19.8 3.5 19V5C3.5 4.2 4.2 3.5 5 3.5Z"), F(rr(7, 3.9, 8, 5, 0.8)), S(rr(7, 13, 10, 7.5, 1.2))]
I['trash'] = [S(ln(4, 6.5, 20, 6.5) + "M9.5 6.5V4.5H14.5V6.5"), S("M6 6.5L7 19.5C7.1 20.3 7.7 21 8.6 21H15.4C16.3 21 16.9 20.3 17 19.5L18 6.5"), S(ln(10, 10.5, 10, 17) + ln(14, 10.5, 14, 17))]
PEN = "M4 20L5 15.5L16.5 4C17.3 3.2 18.6 3.2 19.4 4L20 4.6C20.8 5.4 20.8 6.7 20 7.5L8.5 19Z"
I['pencil'] = [F(PEN, None, 0.25), S(PEN), S(ln(14.5, 6, 18, 9.5))]
I['scissors'] = [S(circ(6.5, 6.5, 2.8)), S(circ(6.5, 17.5, 2.8)), S(ln(8.6, 8.5, 20.5, 19) + ln(8.6, 15.5, 20.5, 5))]
I['box_pkg'] = [S("M12 3L20.5 7.5V16.5L12 21L3.5 16.5V7.5Z"), S(pl((3.5, 7.5), (12, 12), (20.5, 7.5)) + ln(12, 12, 12, 21))]
I['bars'] = [F(rr(4, 12, 4, 8, 1)), F(rr(10, 5, 4, 15, 1)), F(rr(16, 9, 4, 11, 1))]
I['trend'] = [S(pl((3.5, 17), (9, 11.5), (13, 15), (20.5, 6.5)) + pl((15.5, 6.5), (20.5, 6.5), (20.5, 11.5)))]
I['ruler'] = [S(pl((3, 17), (17, 3), (21, 7), (7, 21), close=True)), S(ln(7.2, 13.8, 9.2, 15.8) + ln(10, 11, 12, 13) + ln(12.8, 8.2, 14.8, 10.2))]
I['pin'] = [F("M12 21.5C12 21.5 5 14.8 5 9.5A7 7 0 0 1 19 9.5C19 14.8 12 21.5 12 21.5Z" + circ(12, 9.5, 2.6))]
I['search'] = [S(circ(10.5, 10.5, 6.5)), S(ln(15.4, 15.4, 21, 21))]
I['link'] = [S("M10 13A5 5 0 0 0 17.5 13.5L20.5 10.5A5 5 0 0 0 13.4 3.4L11.7 5.1"), S("M14 11A5 5 0 0 0 6.5 10.5L3.5 13.5A5 5 0 0 0 10.6 20.6L12.3 18.9")]
I['globe'] = [S(circ(12, 12, 9.5)), S("M12 2.5C8.4 6.2 8.4 17.8 12 21.5C15.6 17.8 15.6 6.2 12 2.5Z"), S(ln(2.5, 12, 21.5, 12))]
I['translate'] = [S("M3.5 5.5H13.5M8.5 3.5V5.5M5.5 5.5C6 9.5 9 12.5 12.8 14M11.5 5.5C11 9.5 8.5 12.5 4.5 14.5"), S(pl((12.8, 21), (16.9, 10), (21, 21)) + ln(14.4, 17.2, 19.4, 17.2))]
I['chat'] = [S("M4 5.5C4 4.7 4.7 4 5.5 4H18.5C19.3 4 20 4.7 20 5.5V15C20 15.8 19.3 16.5 18.5 16.5H10L5.5 20.5V16.5C4.7 16.5 4 15.8 4 15Z"),
             F(circ(8.5, 10.3, 1.2)), F(circ(12, 10.3, 1.2)), F(circ(15.5, 10.3, 1.2))]
BRAIN_L = ("M12 5V19.5M12 5C12 3.6 10.9 2.8 9.7 3C8.3 3.2 7.5 4.5 7.9 5.8C6.1 6.2 5.1 7.8 5.6 9.4C4.4 10.4 4.3 12.3 5.4 13.4"
           "C4.7 14.9 5.5 16.6 7.1 17C7.3 18.8 8.9 20 10.4 19.6C11.3 19.4 12 18.6 12 17.5")
BRAIN_R = ("M12 5C12 3.6 13.1 2.8 14.3 3C15.7 3.2 16.5 4.5 16.1 5.8C17.9 6.2 18.9 7.8 18.4 9.4C19.6 10.4 19.7 12.3 18.6 13.4"
           "C19.3 14.9 18.5 16.6 16.9 17C16.7 18.8 15.1 20 13.6 19.6C12.7 19.4 12 18.6 12 17.5")
I['brain'] = [S(BRAIN_L), S(BRAIN_R)]
I['robot'] = [S(rr(4.5, 8, 15, 11.5, 3)), S(ln(12, 8, 12, 4.8)), F(circ(12, 3.8, 1.4)), F(circ(9, 13.2, 1.6)), F(circ(15, 13.2, 1.6)), S(ln(9.5, 16.7, 14.5, 16.7) + ln(2.6, 12, 2.6, 15.5) + ln(21.4, 12, 21.4, 15.5))]
I['wrench'] = [S("M14.7 6.3A1 1 0 0 0 14.7 7.7L16.3 9.3A1 1 0 0 0 17.7 9.3L21.5 5.5A6 6 0 0 1 13.5 13.4L6.6 20.3A2.1 2.1 0 0 1 3.6 17.3L10.5 10.4A6 6 0 0 1 18.5 2.5L14.7 6.3Z")]
I['toolbox'] = [S(rr(3, 8, 18, 12, 2.2)), S("M9 8V6C9 5.4 9.4 5 10 5H14C14.6 5 15 5.4 15 6V8"), S(ln(3, 13, 21, 13)), F(rr(10.5, 11.3, 3, 3.4, 0.7))]
BR = "M10.8 10.8L13.2 13.2L10.5 20.5H3.5Z"
I['broom'] = [S(ln(20.5, 3.5, 12.5, 11.5)), F(BR, None, 0.25), S(BR)]
I['grid'] = [S(rr(3.5, 3.5, 7, 7, 1.6) + rr(13.5, 3.5, 7, 7, 1.6) + rr(3.5, 13.5, 7, 7, 1.6) + rr(13.5, 13.5, 7, 7, 1.6))]
I['palette'] = [S("M12 3C6.9 3 3 6.9 3 12C3 17 7 21 12 21C13.2 21 14 20.2 14 19.2C14 18.6 13.7 18.2 13.4 17.8C13.1 17.4 12.9 17 12.9 16.5C12.9 15.5 13.7 14.8 14.7 14.8H17C19.2 14.8 21 13 21 10.8C21 6.5 17 3 12 3Z"),
                F(circ(7.6, 11.8, 1.4)), F(circ(10.2, 7.6, 1.4)), F(circ(14.6, 7.6, 1.4)), F(circ(17.4, 11.2, 1.4))]
I['sliders'] = [S(ln(6, 4, 6, 20) + ln(12, 4, 12, 20) + ln(18, 4, 18, 20)), FS(rr(3.8, 14, 4.4, 3.6, 1.2)), FS(rr(9.8, 6.5, 4.4, 3.6, 1.2)), FS(rr(15.8, 11, 4.4, 3.6, 1.2))]
I['gear'] = [S(gear(12, 12, 9.8, 7.4, 8)), S(circ(12, 12, 3.2))]
I['shuffle'] = [S("M3 7H6.5C8.5 7 9.8 8 11 9.8L13 14.2C14.2 16 15.5 17 17.5 17H20.5M3 17H6.5C8.5 17 9.8 16 10.8 14.6M13.2 9.4C14.2 8 15.5 7 17.5 7H20.5"),
                S(pl((18, 4), (21, 7), (18, 10)) + pl((18, 14), (21, 17), (18, 20)))]
I['repeat'] = [S(pl((17, 3), (21, 7), (17, 11)) + "M3 11V9.5C3 8.1 4.1 7 5.5 7H21" + pl((7, 21), (3, 17), (7, 13)) + "M21 13V14.5C21 15.9 19.9 17 18.5 17H3")]
I['refresh'] = [S("M3.5 10.5A9 9 0 0 1 18.4 5.7L21 8.3M20.5 13.5A9 9 0 0 1 5.6 18.3L3 15.7"), S(pl((21, 3.5), (21, 8.5), (16, 8.5)) + pl((3, 20.5), (3, 15.5), (8, 15.5)))]
I['hole'] = [S(ell(12, 12, 9, 4.8)), F(ell(12, 12.9, 6.3, 2.8), None, 0.6)]
I['lifebuoy'] = [S(circ(12, 12, 9.2)), S(circ(12, 12, 4)), S(ln(5.8, 5.8, 9.3, 9.3) + ln(18.2, 5.8, 14.7, 9.3) + ln(5.8, 18.2, 9.3, 14.7) + ln(18.2, 18.2, 14.7, 14.7))]
I['steth'] = [S("M6 3V10A4.5 4.5 0 0 0 15 10V3"), S("M10.5 14.5V16A4 4 0 0 0 18.5 16V14.7"), S(circ(18.5, 12.5, 2.2)), F(circ(6, 3, 1.1)), F(circ(15, 3, 1.1))]
I['users'] = [S(circ(9, 8.5, 3.3)), S("M3 20C3 16.2 5.7 14 9 14C12.3 14 15 16.2 15 20"), S(circ(17, 9.5, 2.6)), S("M16.6 14.2C19.3 14.2 21.3 16 21.3 19")]
I['person'] = [S(circ(12, 8, 4)), S("M4.5 21C4.5 16.5 7.7 13.5 12 13.5C16.3 13.5 19.5 16.5 19.5 21")]
I['female'] = [S(circ(12, 9, 5.5)), S(ln(12, 14.5, 12, 21.5) + ln(8.5, 18.5, 15.5, 18.5))]
I['male'] = [S(circ(10, 14, 5.5)), S(ln(14, 10, 20.5, 3.5) + pl((15, 3.5), (20.5, 3.5), (20.5, 9)))]
I['touch'] = [F(circ(10, 10, 2.8)), S(circ(10, 10, 6.8), None, 0.55), FS(pl((14, 13.5), (22, 17), (18.4, 18.4), (20.2, 22), (18, 23), (16.2, 19.4), (13.5, 22), close=True))]
I['face'] = [S(circ(12, 12, 9.3)), F(circ(8.8, 10, 1.3)), F(circ(15.2, 10, 1.3)), S(ln(8.5, 15.2, 15.5, 15.2))]
I['age18'] = [S(circ(12, 12, 9.5)), S(ln(8, 9, 8, 15)), S(circ(14.4, 10.6, 1.8)), S(circ(14.4, 13.9, 2))]
I['heart'] = [FS("M12 20.5C12 20.5 3 14.8 3 8.8C3 6.1 5 4.2 7.4 4.2C9.4 4.2 11.2 5.3 12 7C12.8 5.3 14.6 4.2 16.6 4.2C19 4.2 21 6.1 21 8.8C21 14.8 12 20.5 12 20.5Z", PINK)]
I['text_aa'] = [S(pl((2.5, 19), (8, 5), (13.5, 19)) + ln(4.8, 14, 11.2, 14)), S(circ(17.8, 15.6, 3.2)), S(ln(21, 12.4, 21, 19))]
I['more_v'] = [F(circ(12, 5, 2) + circ(12, 12, 2) + circ(12, 19, 2))]
I['more_h'] = [F(circ(5, 12, 2) + circ(12, 12, 2) + circ(19, 12, 2))]


# ---- MX-style rotation icon (phone + circular arrows) ----
I['rot_screen'] = [S(rr(8.4, 6.6, 7.2, 10.8, 1.7), None, 1.0), F(rr(10.6, 14.6, 2.8, 0.9, 0.4)),
                   S(arc_arrow(12, 12, 10, 205, 335, 4.6)), S(arc_arrow(12, 12, 10, 25, 155, 4.6))]

# ---------- codepoint -> icon ----------
CP = {}
def reg(name, chars):
    for ch in chars: CP[ord(ch)] = name
reg('play', '▶'); reg('play_l', '◀'); reg('pause', '⏸'); reg('stop', '⏹'); reg('prev', '⏮'); reg('next', '⏭🔚'); reg('playpause', '⏯')
reg('ff', '⏩'); reg('rew', '⏪'); reg('up2', '⏫')
reg('check_c', '✅'); reg('check', '✓✔'); reg('check_box', '☑'); reg('box', '☐'); reg('cross', '✗✕'); reg('cross_c', '❌')
reg('no_entry', '⛔'); reg('prohibited', '🚫'); reg('warn', '⚠'); reg('question', '❓'); reg('red_dot', '🔴'); reg('dot', '●'); reg('info', 'ℹ')
reg('hourglass', '⏳'); reg('clock', '🕒🕘'); reg('stopwatch', '⏱'); reg('battery', '🔋'); reg('burst', '💥')
reg('menu', '☰'); reg('chev_l', '❮◂'); reg('chev_r', '❯▸'); reg('chev_d', '▾'); reg('chev_u', '▴')
reg('fullscreen', '⛶'); reg('fit', '⬛'); reg('expand', '⤢'); reg('pip', '⧉'); reg('box_tall', '▯'); reg('box_s', '▫'); reg('box_in', '▣')
reg('up', '⬆'); reg('down', '⬇'); reg('swap_h', '↔'); reg('swap_v', '↕'); reg('undo', '↩'); reg('rotate', '↻'); reg('plus', '➕＋')
reg('sparkle', '✦'); reg('sparkles', '✨'); reg('flame', '🔥'); reg('bolt', '⚡🚀'); reg('wave', '🌊'); reg('fog', '🌫'); reg('moon', '🌙'); reg('sun', '☀')
reg('eye', '👁'); reg('eye_off', '🙈'); reg('lock', '🔒🔐'); reg('unlock', '🔓'); reg('key', '🔑'); reg('shield', '🛡')
reg('mute', '🔇'); reg('vol_low', '🔈'); reg('vol', '🔊'); reg('bell_off', '🔕'); reg('mic', '🎙'); reg('headphones', '🎧')
reg('note', '♪🎵'); reg('note2', '♫🎶'); reg('clapper', '🎬'); reg('film', '🎞📼'); reg('radio', '📻'); reg('signal', '📡📶')
reg('folder', '📁'); reg('folder_open', '📂'); reg('folders', '🗂'); reg('note_edit', '📝'); reg('clipboard', '📋'); reg('scroll', '📜')
reg('upload', '📤'); reg('download', '📥'); reg('save', '💾'); reg('trash', '🗑'); reg('pencil', '✏'); reg('scissors', '✂'); reg('box_pkg', '📦')
reg('bars', '📊'); reg('trend', '📈'); reg('ruler', '📏'); reg('pin', '📍'); reg('search', '🔍🔎'); reg('link', '🔗'); reg('globe', '🌐')
reg('translate', '🈯'); reg('chat', '💬'); reg('brain', '🧠'); reg('robot', '🤖'); reg('wrench', '🔧'); reg('toolbox', '🧰'); reg('broom', '🧹')
reg('grid', '🧩'); reg('palette', '🎨'); reg('sliders', '🎚'); reg('gear', '⚙'); reg('shuffle', '🔀'); reg('repeat', '🔁'); reg('refresh', '🔄♻')
reg('hole', '🕳'); reg('lifebuoy', '🛟'); reg('steth', '🩺'); reg('users', '🤝'); reg('person', '🧑'); reg('female', '♀'); reg('male', '♂')
reg('touch', '👆☝🤏'); reg('face', '😐'); reg('age18', '🔞'); reg('heart', '💓'); reg('text_aa', '🔤'); reg('rot_screen', '\ue000'); reg('more_v', '⋮'); reg('more_h', '⋯')
for k, v in CP.items(): assert v in I, (hex(k), v)

# ---------- normalize path to absolute M/L/C/Z ----------
def norm(d):
    p = parse_path(d); out = []
    for sub in p.continuous_subpaths():
        first = True; segs = []
        for s in sub:
            if isinstance(s, Arc):
                try: segs += list(s.as_cubic_curves(max(1, int(abs(s.delta)//60)+1)))
                except Exception: segs.append(s)
            elif isinstance(s, QuadraticBezier):
                p0, p1, p2 = s.start, s.control, s.end
                segs.append(CubicBezier(p0, p0+2/3*(p1-p0), p2+2/3*(p1-p2), p2))
            else: segs.append(s)
        out.append(f"M{n(segs[0].start.real)} {n(segs[0].start.imag)}")
        for s in segs:
            if isinstance(s, Line): out.append(f"L{n(s.end.real)} {n(s.end.imag)}")
            else: out.append(f"C{n(s.control1.real)} {n(s.control1.imag)} {n(s.control2.real)} {n(s.control2.imag)} {n(s.end.real)} {n(s.end.imag)}")
        if abs(sub.start - sub.end) < 1e-6 and len(segs) > 1: out.append("Z")
    return "".join(out)

def argb(c): return '_' if c is None else '%08X' % c

def to_svg(name, color_default='#FFFFFF', size=24):
    parts = []
    for mode, d, c, a in I[name]:
        col = color_default if c is None else '#%06X' % (c & 0xFFFFFF)
        fill = col if 'F' in mode else 'none'
        stroke = col if 'S' in mode else 'none'
        parts.append(f'<path d="{norm(d)}" fill="{fill}" fill-rule="evenodd" stroke="{stroke}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" opacity="{a}"/>')
    return "".join(parts)

def preview(path, names, cols=10, cell=84, bg='#1d1d22'):
    import cairosvg
    rows = math.ceil(len(names)/cols); W = cols*cell; H = rows*(cell+16)
    body = [f'<rect width="{W}" height="{H}" fill="{bg}"/>']
    for i, nm in enumerate(names):
        x = (i % cols)*cell; y = (i//cols)*(cell+16)
        body.append(f'<g transform="translate({x+12},{y+4}) scale({(cell-24)/24})">{to_svg(nm)}</g>')
        body.append(f'<text x="{x+cell/2}" y="{y+cell+10}" font-size="9" fill="#9aa" text-anchor="middle" font-family="DejaVu Sans">{nm}</text>')
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">{"".join(body)}</svg>'
    cairosvg.svg2png(bytestring=svg.encode(), write_to=path)

if __name__ == '__main__':
    names = [k for k in I if k != 'rot_phone']
    for i in range(0, len(names), 40):
        preview(f'/home/claude/prev{i//40}.png', names[i:i+40])
    print(len(names), 'icons;', len(CP), 'codepoints')


# ================= Kotlin emission =================
SC = {'chev_l': 1.0, 'chev_r': 1.0, 'chev_d': 1.0, 'chev_u': 1.0, 'dot': 1.0, 'red_dot': 1.1, 'box_s': 1.1,
      'plus': 1.2, 'check': 1.15, 'cross': 1.15, 'more_v': 1.3, 'more_h': 1.3, 'rot_screen': 1.3}

def emit_kotlin(path):
    L = []
    for name, layers in I.items():
        items = []
        for mode, d, c, a in layers:
            items.append(f'"{mode}|{argb(c)}|{a:g}|{norm(d)}"')
        L.append(f'        "{name}" to arrayOf({", ".join(items)}),')
    defs = "\n".join(L)
    cps = []
    for cp, nm in sorted(CP.items()):
        cps.append(f'        0x{cp:X} to "{nm}",')
    cpm = "\n".join(cps)
    scs = ", ".join(f'"{k}" to {v}f' for k, v in SC.items())
    kt = open('/home/claude/Icons.kt.tmpl', encoding='utf8').read()
    kt = kt.replace('/*DEFS*/', defs).replace('/*CPS*/', cpm).replace('/*SCS*/', scs)
    open(path, 'w', encoding='utf8').write(kt)

if __name__ == '__main__' and len(sys.argv) > 1 and sys.argv[1] == 'kt':
    emit_kotlin(sys.argv[2])
    print('written', sys.argv[2])
