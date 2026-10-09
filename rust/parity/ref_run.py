"""Run the Python predictor headless on a litematic and record intermediate state.

usage: OBSIDIAN_PREDICTOR_SRC=<checkout of EQUENOS-2/Obsidian-Predictor>/src \
       ref_run.py MODE LITEMATIC OUT_DIR X0 Y0 Z0
  MODE = orig     -> unmodified src/obi_predict.py
  MODE = ordered  -> same source, but every `set()` / `defaultdict(set)` container
                     replaced with an insertion-ordered set (the only change)
Records per-layer flooded lava sets (right before resolution) and the full set of
blast-resistant positions, so runs can be compared semantically.
"""

import json
import os
import resource
import sys
import time
import types

SRC = os.path.abspath(os.environ["OBSIDIAN_PREDICTOR_SRC"])
mode = sys.argv[1]
lit_path, out_dir = os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3])
x0, y0, z0 = map(int, sys.argv[4:7])
os.makedirs(out_dir, exist_ok=True)
os.chdir(SRC)  # resource_path() resolves airlike_blocks.json relative to cwd
sys.path.insert(0, SRC)

_TOMB = object()


class OrderedSet:
    """Set with deterministic insertion order. pop() removes the oldest element;
    re-adding a removed element appends it at the end (same as a dict)."""

    __slots__ = ("_items", "_idx", "_head")

    def __init__(self, it=()):
        self._items = []
        self._idx = {}
        self._head = 0
        for x in it:
            self.add(x)

    def add(self, k):
        if k not in self._idx:
            self._idx[k] = len(self._items)
            self._items.append(k)

    def __contains__(self, k):
        return k in self._idx

    def __len__(self):
        return len(self._idx)

    def __iter__(self):
        items = self._items
        for i in range(self._head, len(items)):
            k = items[i]
            if k is not _TOMB:
                yield k

    def discard(self, k):
        pos = self._idx.pop(k, None)
        if pos is not None:
            self._items[pos] = _TOMB

    def remove(self, k):
        pos = self._idx.pop(k)
        self._items[pos] = _TOMB

    def pop(self):
        items = self._items
        while items[self._head] is _TOMB:
            self._head += 1
        k = items[self._head]
        self._head += 1
        del self._idx[k]
        if not self._idx:
            self._items = []
            self._head = 0
        return k


if mode == "orig":
    import obi_predict as op
elif mode == "ordered":
    with open(os.path.join(SRC, "obi_predict.py")) as fp:
        code = fp.read()
    for old, new, count in (
        ("defaultdict(set)", "defaultdict(OrderedSet)", 2),
        ("= set()", "= OrderedSet()", 3),
    ):
        assert code.count(old) == count, (old, code.count(old))
        code = code.replace(old, new)
    op = types.ModuleType("obi_predict_ordered")
    op.__dict__["OrderedSet"] = OrderedSet
    exec(compile(code, "obi_predict_ordered.py", "exec"), op.__dict__)
else:
    raise SystemExit(f"bad mode {mode}")

flooded_record = {}
_orig_resolve = op.ObsidianPredictor.resolve_lava_markers


def recording_resolve(self, y):
    flooded_record[y] = sorted(self.flooded_lava[y])
    _orig_resolve(self, y)


op.ObsidianPredictor.resolve_lava_markers = recording_resolve

t0 = time.perf_counter()
region = op.RegionProxy.from_file(lit_path)
t1 = time.perf_counter()
pred = op.ObsidianPredictor(x0, y0, z0, region)
for y in range(pred.size_y - 1, -1, -1):
    pred.process_layer(y)
t2 = time.perf_counter()
blast = sorted(pred.blast_resistant_markers)
name = os.path.basename(lit_path).rsplit(".", 1)[0]
out_file = os.path.join(out_dir, f"{name} waypoints.txt")
pred.save_waypoints(out_file)
t3 = time.perf_counter()

with open(os.path.join(out_dir, "record.json"), "w") as fp:
    json.dump(
        {
            "origin": [pred.origin_x, pred.origin_y, pred.origin_z],
            "size": [pred.size_x, pred.size_y, pred.size_z],
            "flooded": {str(k): v for k, v in flooded_record.items() if v},
            "blast": blast,
        },
        fp,
    )
rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
stats = {
    "mode": mode,
    "file": lit_path,
    "blocks": pred.size_x * pred.size_y * pred.size_z,
    "load_s": t1 - t0,
    "simulate_s": t2 - t1,
    "save_s": t3 - t2,
    "total_s": t3 - t0,
    "peak_rss_mb": rss_mb,
}
with open(os.path.join(out_dir, "stats.json"), "w") as fp:
    json.dump(stats, fp, indent=1)
print(json.dumps(stats))
