#!/usr/bin/env python3
"""Reproduces every derived number quoted in ../full-caliber-ap-sources.md from the CSV in this folder.
Not a calibration: the inputs are acceptance MINIMA from MIL-DTL-12560K, not measured V50s (see the dossier)."""
import csv, math, sys, pathlib

rows = [r for r in csv.reader(l for l in open(pathlib.Path(__file__).with_name('mil-dtl-12560k-appendix-a-ap-tables.csv')) if not l.startswith('#'))]
hdr, rows = rows[0], rows[1:]
tables = {}
for t, proj, ob, th, mn, mx in rows:
    tables.setdefault(t, {})[float(th)] = int(mn)
log10 = math.log10
sec = lambda deg: 1 / math.cos(math.radians(deg))

print("== 1. Naval Ordnance 1937 eq (1),(2),(4) reproduce the manual's own worked examples ==")
print("Problem I  (eq 1): v = %.1f ft/s (manual: 1772)" % 10 ** (2.9616 + .75 * log10(10) + .65 * log10(23.1) - .5 * log10(500)))
print("Problem II (eq 2): v = %.1f ft/s (manual: 1772)" % 10 ** (3.00945 + .75 * log10(10) + .70 * log10(15.77) - .5 * log10(500)))
print("Problem IV (eq 4): K = %.3f (manual: 1.23)" % 10 ** (log10(1836) - .75 * log10(6) - .70 * log10(7) + .5 * log10(105) - 3.00945))

print("\n== 2. Tables A-V (M82 APC, 45 deg) and A-VI (M82 APC, 30 deg): same projectile, same plate class, overlapping thickness ==")
a, b = tables['A-V'], tables['A-VI']
common = sorted(set(a) & set(b))
print("overlap: %.3f..%.3f in, %d rows" % (common[0], common[-1], len(common)))
ratios = [a[t] / b[t] for t in common]
ns = [math.log(r) / math.log(sec(45) / sec(30)) for r in ratios]
print("BL(45)/BL(30): %.4f .. %.4f" % (min(ratios), max(ratios)))
print("implied exponent n in v ~ sec(theta)^n : %.3f .. %.3f" % (min(ns), max(ns)))
print("for comparison: sec^4 predicts ratio %.3f; thickness-only (LOS) with b=0.70 predicts %.3f" % ((sec(45) / sec(30)) ** 4, (sec(45) / sec(30)) ** .7))

print("\n== 3. Table A-VII (90 mm M318A1 AP, 0 deg, 3.94-6.06 in): shape of the minimum curve ==")
pts = sorted(tables['A-VII'].items())
n = len(pts)
def fit(b):
    lnC = sum(math.log(v) - b * math.log(t) for t, v in pts) / n
    res = [math.exp(lnC) * t ** b / v - 1 for t, v in pts]
    return math.exp(lnC), res
C, res = fit(0.70)
print("%d rows. b fixed at 0.70, one coefficient C=%.1f: residual %.1f%% .. %+.1f%%, rms %.2f%%" % (n, C, min(res) * 100, max(res) * 100, math.sqrt(sum(r * r for r in res) / n) * 100))
mx_ = sum(math.log(t) for t, _ in pts) / n; my_ = sum(math.log(v) for _, v in pts) / n
bf = sum((math.log(t) - mx_) * (math.log(v) - my_) for t, v in pts) / sum((math.log(t) - mx_) ** 2 for t, _ in pts)
lnC = my_ - bf * mx_
res = [math.exp(lnC) * t ** bf / v - 1 for t, v in pts]
print("free exponent b=%.3f: residual %.2f%% .. %+.2f%%, rms %.2f%%" % (bf, min(res) * 100, max(res) * 100, math.sqrt(sum(r * r for r in res) / n) * 100))
# restricted to the 1937 manual's stated d/T validity band (0.7-1.2) using the NOMINAL 90 mm = 3.543 in diameter
d_in = 90 / 25.4
sub = [(t, v) for t, v in pts if d_in / t >= 0.7]
m = len(sub)
lnC = sum(math.log(v) - 0.70 * math.log(t) for t, v in sub) / m
res = [math.exp(lnC) * t ** 0.70 / v - 1 for t, v in sub]
print("d/T >= 0.7 only (T <= %.2f in, %d rows, %.2f..%.2f in): b=0.70 fixed, own coefficient: residual %.1f%% .. %+.1f%%" % (d_in / 0.7, m, sub[0][0], sub[-1][0], min(res) * 100, max(res) * 100))
mid = pts[n // 2]
slope = lambda p, q: math.log(q[1] / p[1]) / math.log(q[0] / p[0])
print("local exponent: first half %.3f, second half %.3f" % (slope(pts[0], mid), slope(mid, pts[-1])))
print("NOTE: 212 rows are a piecewise-linear table of a requirement, not 212 independent measurements.")
