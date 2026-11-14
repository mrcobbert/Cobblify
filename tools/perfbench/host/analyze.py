"""Summarise cobbench results: per run and per config (median across runs).

avg FPS = frames / total frame time; 1% / 0.1% low = FPS at the 99th / 99.9th percentile frame time.
"""
import glob
import json
import os
import statistics
import sys

RES = sys.argv[1]
SKIP_PREFIX = ("warm-", "smoke-prepare", "prepare")


def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * p
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


rows = []
for meta_path in sorted(glob.glob(os.path.join(RES, "*-meta.json"))):
    label = os.path.basename(meta_path)[: -len("-meta.json")]
    meta = json.load(open(meta_path))
    cfg = label.rsplit("-", 1)[0]
    for sc in meta.get("scenes", []):
        csv = os.path.join(RES, f"{label}-frames-{sc['scene']}.csv")
        if not os.path.exists(csv):
            continue
        ns = [int(x) for x in open(csv).read().split()]
        ns = ns[1:]  # the first delta spans the scene switch
        ms = sorted(v / 1e6 for v in ns)
        total = sum(ms)
        active = sc["activeTrue"] / sc["activeSamples"] if sc["activeSamples"] else float("nan")
        rows.append(dict(label=label, cfg=cfg, scene=sc["scene"], frames=len(ms), avg=len(ms) / (total / 1000),
                         med=pct(ms, 0.5), p99=pct(ms, 0.99), p999=pct(ms, 0.999), gc=sc["gcCount"], gcms=sc["gcMs"],
                         active=active, drift=sc["maxRotDrift"], moved=sc["moved"], ents=sc["entities"],
                         first=meta.get("firstFrameMs"), ready=meta.get("worldReadyMs"), notes=meta.get("notes")))

print(f"{'run':14} sc {'avgFPS':>7} {'med ms':>7} {'1%low':>6} {'.1%low':>6} {'GCs':>4} {'GCms':>5} {'focus':>5} {'ents':>4} {'boot s':>6}")
for r in rows:
    print(f"{r['label']:14} {r['scene']}  {r['avg']:7.0f} {r['med']:7.3f} {1000 / r['p99']:6.0f} {1000 / r['p999']:6.0f} "
          f"{r['gc']:4d} {r['gcms']:5d} {r['active']:5.2f} {r['ents']:4d} {r['ready'] / 1000:6.1f}")

print("\nper config (median of runs; [min-max] avg FPS), warm-up runs excluded")
cfgs = []
for r in rows:
    if r["label"].startswith(SKIP_PREFIX):
        continue
    if r["cfg"] not in cfgs:
        cfgs.append(r["cfg"])
for sc in ("A", "B"):
    print(f"scene {sc}")
    for c in cfgs:
        rs = [r for r in rows if r["cfg"] == c and r["scene"] == sc and not r["label"].startswith(SKIP_PREFIX)]
        if not rs:
            continue
        avg = [r["avg"] for r in rs]
        print(f"  {c:9} n={len(rs)} avg {statistics.median(avg):6.0f} [{min(avg):.0f}-{max(avg):.0f}]  "
              f"med {statistics.median([r['med'] for r in rs]):.3f} ms  "
              f"1%low {statistics.median([1000 / r['p99'] for r in rs]):5.0f}  "
              f".1%low {statistics.median([1000 / r['p999'] for r in rs]):5.0f}  "
              f"GC/run {statistics.median([r['gc'] for r in rs]):.0f}  "
              f"boot {statistics.median([r['ready'] for r in rs]) / 1000:.1f}s")
