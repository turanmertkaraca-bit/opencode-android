#!/usr/bin/env python3
"""Scan the opencode binary for permission-event names, payload shape and
the permission reply route, so the Android client can be fixed exactly."""
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else "/home/z/my-project/p4-work/opencode"
data = open(path, "rb").read()
print(f"scanned {len(data)} bytes from {path}\n")


def ctx(pattern, label, span=260, limit=10):
    rx = re.compile(pattern)
    hits, seen = 0, set()
    for m in rx.finditer(data):
        s, e = max(0, m.start() - span), min(len(data), m.end() + span)
        txt = re.sub(rb"[^\x20-\x7e]", b" ", data[s:e]).decode("ascii", "replace")
        key = txt[span : span + 40]
        if key in seen:
            continue
        seen.add(key)
        print(f"--- {label} @ {m.start()} ---")
        print(txt + "\n")
        hits += 1
        if hits >= limit:
            break
    if hits == 0:
        print(f"--- {label}: NO MATCHES ---\n")


# 1) exact SSE event type names
ctx(rb"permission\.(updated|asked|replied|deleted|created)", "EVENT-NAMES")
# 2) route paths mentioning permission
ctx(rb"/permission", "ROUTE /permission", limit=14)
ctx(rb"permissionID", "KEY permissionID", limit=8)
# 3) reply response enum in context
ctx(rb'"once"', "ENUM once", limit=6)
ctx(rb'"reject"', "ENUM reject", limit=6)
# 4) permission object keys (title/type/metadata/pattern)
ctx(rb'pattern', "KEY pattern", limit=4, span=120)
