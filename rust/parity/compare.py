"""Semantic comparison of two reference runs (orig vs ordered) of the same litematic.

usage: compare.py OUT_DIR_A OUT_DIR_B
Checks that the order-independent state is identical and that waypoints differ only in
which point of the same lava patch / blast-resistant group was picked.
"""

import glob
import json
import os
import sys
from collections import Counter, deque


def load(d):
    rec = json.load(open(os.path.join(d, "record.json")))
    (wp_file,) = glob.glob(os.path.join(d, "* waypoints.txt"))
    lava, black = [], []
    for line in open(wp_file):
        parts = line.strip().split(":")
        x, y, z, color = int(parts[3]), int(parts[4]), int(parts[5]), parts[6]
        (lava if color == "12" else black).append((x, y, z))
    return rec, lava, black


def components(cells, neighbours):
    comp = {}
    n = 0
    for c in cells:
        if c in comp:
            continue
        comp[c] = n
        q = deque([c])
        while q:
            p = q.popleft()
            for nb in neighbours(p):
                if nb in cells and nb not in comp:
                    comp[nb] = n
                    q.append(nb)
        n += 1
    return comp, n


def n4(p):
    x, z = p
    return ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1))


def n6(p):
    x, y, z = p
    return ((x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1))


ra, lava_a, black_a = load(sys.argv[1])
rb, lava_b, black_b = load(sys.argv[2])
ok = True


def check(cond, msg):
    global ok
    print(("  OK   " if cond else "  FAIL ") + msg)
    ok &= cond


check(ra["origin"] == rb["origin"] and ra["size"] == rb["size"], "same origin/size")
check(ra["flooded"] == rb["flooded"], f"flooded lava identical on all layers ({len(ra['flooded'])} layers with lava)")
check(ra["blast"] == rb["blast"], f"blast-resistant positions identical ({len(ra['blast'])} blocks)")

ox, oy, oz = ra["origin"]
to_local = lambda p: (p[0] - ox, p[1] - oy, p[2] - oz)

# blast-resistant groups: every group must get exactly one waypoint in both runs
blast = {tuple(p) for p in ra["blast"]}
group_of, n_groups = components(blast, n6)
for name, black in (("A", black_a), ("B", black_b)):
    hit = Counter(group_of.get(to_local(p)) for p in black)
    check(None not in hit and len(hit) == n_groups and all(v == 1 for v in hit.values()),
          f"{name}: one black waypoint per group ({len(black)} waypoints, {n_groups} groups)")
same_black = len(set(black_a) & set(black_b))
print(f"       black waypoints at identical positions: {same_black}/{n_groups}")

# lava: every flooded component of every layer must get >= 1 marker, all markers inside
comp_ids = {}
total_components = 0
for y, cells in ra["flooded"].items():
    comp, n = components({tuple(c) for c in cells}, n4)
    for c, i in comp.items():
        comp_ids[(c[0], int(y), c[1])] = (int(y), i)
    total_components += n
counts = {}
for name, lava in (("A", lava_a), ("B", lava_b)):
    hit = Counter(comp_ids.get(to_local(p)) for p in lava)
    counts[name] = hit
    check(None not in hit and len(hit) == total_components,
          f"{name}: every flooded lava patch has a marker ({len(lava)} markers, {total_components} patches)")
diff = [k for k in counts["A"] if counts["A"][k] != counts["B"].get(k)]
same_lava = len(set(lava_a) & set(lava_b))
print(f"       lava markers at identical positions: {same_lava} (A={len(lava_a)}, B={len(lava_b)})")
print(f"       patches where marker count differs: {len(diff)}/{total_components}")
print("RESULT:", "PASS" if ok else "FAIL")
