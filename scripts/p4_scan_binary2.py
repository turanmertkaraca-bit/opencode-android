#!/usr/bin/env python3
"""Second targeted scan: reply enum values, PermissionAskInput schema,
tool part shape (for rendering file activity in chat)."""
import re

path = "/home/z/my-project/p4-work/opencode"
data = open(path, "rb").read()


def ctx(pattern, label, span=300, limit=6):
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


ctx(rb'permission\.(asked|replied)"?\s*[,:][^;]{0,80}"always"', "REPLY-ENUM always")
ctx(rb'"once".{0,150}"always"', "ENUM once-always", limit=4)
ctx(rb"PermissionAskInput", "SCHEMA PermissionAskInput", limit=3, span=700)
ctx(rb"patterns:", "FIELD patterns", limit=5, span=450)
ctx(rb'filePath', "TOOL filePath", limit=5, span=200)
ctx(rb'"step-start"', "PART step-start", limit=2, span=250)
ctx(rb'status:"(pending|running|completed)"', "TOOL state.status", limit=4, span=300)
