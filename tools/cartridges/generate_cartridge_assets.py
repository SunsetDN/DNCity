# AGENT-DONE(claude): cartridge-items
"""Generates item assets for every round in game/ballistics/src/main/resources/dncity/cartridges.json.

Writes, for each round (item id dncity:cartridge_<caliber>_<round>):
  assets/dncity/textures/item/cartridge/<caliber>_<round>.png   64x64 icon
  assets/dncity/models/item/cartridge_<caliber>_<round>.json     item/generated model
and merges the item names into assets/dncity/lang/en_us.json and ko_kr.json (other keys are kept).

Icons are drawn from the catalog's dimensions on ONE scale for all calibers (the longest cartridge fills the icon's
diagonal), so a 9x19 is drawn at its real size relative to a .50 BMG. Run from anywhere:

    python tools/cartridges/generate_cartridge_assets.py

Needs Pillow. Re-run after editing the catalog; the output is deterministic.
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "game" / "ballistics" / "src" / "main" / "resources"
CATALOG = RES / "dncity" / "cartridges.json"
ASSETS = RES / "assets" / "dncity"

SIZE = 64          # icon size in pixels
MARGIN = 3         # free pixels at each icon edge
SUPERSAMPLE = 4    # samples per pixel axis

TYPE_EN = {"FMJ": "FMJ", "JHP": "JHP", "HP": "HP", "RIP": "RIP", "AP": "AP", "API": "API", "OTM": "OTM",
           "JSP": "JSP", "TRACER": "Tracer", "API_T": "API-T", "HEIAP": "HEIAP", "APHEI_T": "APHEI-T",
           "BUCK": "00 Buck", "SLUG": "Slug"}
TYPE_KO = {"FMJ": "보통탄", "JHP": "할로우포인트탄", "HP": "할로우포인트탄", "RIP": "RIP탄", "AP": "철갑탄",
           "API": "소이철갑탄", "OTM": "저격탄", "JSP": "소프트포인트탄", "TRACER": "예광탄",
           "API_T": "소이철갑예광탄", "HEIAP": "고폭소이철갑탄", "APHEI_T": "고폭소이철갑예광탄", "BUCK": "벅샷",
           "SLUG": "슬러그탄"}
TRACER_TYPES = {"TRACER", "API_T", "APHEI_T"}
NOSE_BY_TYPE = {"JHP": "hollow", "HP": "hollow", "RIP": "rip", "JSP": "soft"}

CASE_COLORS = {"brass": (212, 166, 74), "steel": (118, 112, 78), "nickel": (196, 199, 202)}
COPPER = (184, 101, 58)
LEAD = (138, 141, 147)
DARK = (40, 34, 30)


def hex_rgb(text):
    text = text.lstrip("#")
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def item_path(caliber, rnd):
    return f"cartridge_{caliber['id']}_{rnd['id']}"


def names(caliber, rnd):
    t = rnd["type"]
    parts = [caliber["name_en"]]
    if rnd.get("designation"):
        parts.append(rnd["designation"])
    parts.append(TYPE_EN[t])
    en = rnd.get("name_en") or " ".join(parts)
    ko = rnd.get("name_ko") or f"{caliber['name_ko']} {TYPE_KO[t]}"
    return en, ko


# ---------------------------------------------------------------- drawing

def profile(caliber, rnd):
    """Returns (length_mm, f) where f(u) -> (radius_mm, material) along the axis, u = 0 at the case head."""
    d = caliber["dimensions_mm"]
    oal, case_len = d["overall"], d["case_length"]
    rb, rr, rbul = d["base"] / 2, d["rim"] / 2, d["bullet"] / 2
    shape = caliber["shape"]
    nose = NOSE_BY_TYPE.get(rnd["type"], caliber["nose"])
    tip = hex_rgb(rnd["tip"]) if rnd.get("tip") else None
    ring = hex_rgb(rnd["ring"]) if rnd.get("ring") else None
    case_mat = CASE_COLORS[caliber["case"]]

    if shape == "shotshell":
        hull = hex_rgb(rnd.get("hull", "#B8322A"))
        head = 0.22 * oal

        def f_shell(u):
            if u < 1.4:
                return rr, case_mat
            if u < head:
                return rb, case_mat
            if u > oal - 1.2:
                return rb * 0.92, tuple(int(c * 0.8) for c in hull)
            if rnd["type"] == "TRACER" and u > oal - 7:
                return rb, (240, 200, 60)
            return rb, hull
        return oal, f_shell

    rim_t = max(1.0, 0.08 * rb * 2)
    rimmed = shape.startswith("rimmed") or shape == "rimfire" or rr > rb * 1.05
    groove_end = rim_t if rimmed else rim_t + 1.0
    bottleneck = shape.endswith("bottleneck")
    neck_r = rbul + 0.35
    shoulder_start = 0.80 * case_len if bottleneck else case_len
    neck_start = 0.88 * case_len if bottleneck else case_len
    body_end_r = rb * (0.93 if bottleneck else 0.98)
    exposed = oal - case_len
    band = 0.42  # painted share of the bullet, from the tip (wide enough to read at icon size)

    def f(u):
        if u < rim_t:
            return rr, case_mat
        if u < groove_end:
            return rb * 0.82, tuple(int(c * 0.75) for c in case_mat)
        if u < shoulder_start:
            t = (u - groove_end) / max(1e-6, shoulder_start - groove_end)
            return rb + (body_end_r - rb) * t, case_mat
        if u < neck_start:
            t = (u - shoulder_start) / max(1e-6, neck_start - shoulder_start)
            return body_end_r + (neck_r - body_end_r) * t, case_mat
        if u <= case_len:
            return (neck_r if bottleneck else body_end_r), case_mat
        # bullet
        t = (u - case_len) / exposed
        mat = COPPER
        if nose == "spitzer":
            ogive_start = 0.38
            r = rbul if t < ogive_start else rbul * math.sqrt(max(0.0, 1 - ((t - ogive_start) / (1 - ogive_start)) ** 2)) ** 1.25
        elif nose == "round":
            ogive_start = 0.25
            r = rbul if t < ogive_start else rbul * math.sqrt(max(0.0, 1 - ((t - ogive_start) / (1 - ogive_start)) ** 2))
            r = max(r, 0.35 * rbul) if t < 0.995 else 0
        else:  # flat / hollow / soft / rip: truncated cone
            cone_start = 0.42
            flat = {"flat": 0.55, "hollow": 0.62, "soft": 0.5, "rip": 0.6}[nose]
            r = rbul if t < cone_start else rbul * (1 - (1 - flat) * (t - cone_start) / (1 - cone_start))
            if nose == "soft" and t > 0.6:
                mat = LEAD
            if nose == "hollow" and t > 0.85 and r > 0:
                mat = DARK
            if nose == "rip" and t > cone_start and int(t * 14) % 2 == 0:
                mat = tuple(int(c * 0.6) for c in COPPER)
        if tip is not None and t > 1 - band:
            mat = tip
        elif ring is not None and t > 1 - band - 0.14:
            mat = ring
        return r, mat

    return oal, f


def shade(color, v_over_r, alpha_edge):
    # cylinder lighting, light from the upper left of the icon
    x = v_over_r
    light = 0.62 + 0.55 * max(0.0, 1 - ((x + 0.35) / 0.75) ** 2) - 0.22 * x * x
    light = max(0.35, min(1.35, light))
    return tuple(max(0, min(255, int(c * light))) for c in color)


def draw(caliber, rnd, scale):
    length, f = profile(caliber, rnd)
    # axis from bottom-left (case head) to top-right (tip); u along the axis, v across it (negative = upper left)
    cx = cy = SIZE / 2
    ax, ay = 1 / math.sqrt(2), -1 / math.sqrt(2)     # axis direction in image coordinates
    px, py = -ay, ax                                  # perpendicular (pointing down-right)
    half = length * scale / 2
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pix = img.load()
    n = SUPERSAMPLE
    for y in range(SIZE):
        for x in range(SIZE):
            acc = [0, 0, 0]
            hits = 0
            for sy in range(n):
                for sx in range(n):
                    X = x + (sx + 0.5) / n - cx
                    Y = y + (sy + 0.5) / n - cy
                    u = (X * ax + Y * ay + half) / scale
                    v = (X * px + Y * py) / scale
                    if u < 0 or u > length:
                        continue
                    r, mat = f(u)
                    if r <= 0 or abs(v) > r:
                        continue
                    c = shade(mat, -v / r, 0)
                    acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]
                    hits += 1
            if hits:
                pix[x, y] = (acc[0] // hits, acc[1] // hits, acc[2] // hits, int(255 * hits / (n * n)))
    # 1px dark outline on the silhouette, like vanilla item sprites
    out = img.copy()
    op = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = pix[x, y]
            if a == 0:
                continue
            edge = any(
                not (0 <= x + dx < SIZE and 0 <= y + dy < SIZE) or pix[x + dx, y + dy][3] < 128
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                op[x, y] = (int(r * 0.55), int(g * 0.55), int(b * 0.5), max(a, 200))
    return out


# ---------------------------------------------------------------- output

def merge_lang(path, entries):
    data = {}
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    data = {k: v for k, v in data.items() if not k.startswith("item.dncity.cartridge_")}
    data.update(entries)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    calibers = catalog["calibers"]
    longest = max(calibers, key=lambda c: c["dimensions_mm"]["overall"])["dimensions_mm"]
    # the longest cartridge spans the diagonal inside the margins; its square ends stick out by half its width
    usable = (SIZE - 2 * MARGIN) * math.sqrt(2)
    scale = usable / (longest["overall"] + longest["rim"] / 2)

    tex_dir = ASSETS / "textures" / "item" / "cartridge"
    model_dir = ASSETS / "models" / "item"
    tex_dir.mkdir(parents=True, exist_ok=True)
    model_dir.mkdir(parents=True, exist_ok=True)

    en, ko = {"itemGroup.dncity.ammo": "Ammunition"}, {"itemGroup.dncity.ammo": "탄약"}
    seen_en, seen_ko = set(), set()
    for caliber in calibers:
        if not any(r["type"] in TRACER_TYPES for r in caliber["rounds"]):
            sys.exit(f"{caliber['id']}: needs at least one tracer round")
        for rnd in caliber["rounds"]:
            path = item_path(caliber, rnd)
            name_en, name_ko = names(caliber, rnd)
            if name_en in seen_en or name_ko in seen_ko:
                sys.exit(f"{path}: duplicate name {name_en!r} / {name_ko!r}")
            seen_en.add(name_en); seen_ko.add(name_ko)
            en[f"item.dncity.{path}"] = name_en
            ko[f"item.dncity.{path}"] = name_ko

            texture = f"{caliber['id']}_{rnd['id']}"
            draw(caliber, rnd, scale).save(tex_dir / f"{texture}.png")
            model = {"parent": "minecraft:item/generated", "textures": {"layer0": f"dncity:item/cartridge/{texture}"}}
            (model_dir / f"{path}.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")

    merge_lang(ASSETS / "lang" / "en_us.json", en)
    merge_lang(ASSETS / "lang" / "ko_kr.json", ko)
    print(f"{len(en) - 1} rounds, scale {scale:.3f} px/mm")


if __name__ == "__main__":
    main()
