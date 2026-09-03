#!/usr/bin/env python3
"""p6_scan2.py — targeted scans: auth.json shape, custom provider schema,
part types, config model keys, tool status enum."""
import re

BIN = "/home/z/my-project/p6-work/opencode"
data = open(BIN, "rb").read()

def raw_ctx(needle, span=240, limit=5):
    out, start = [], 0
    nb = needle.encode()
    while len(out) < limit:
        i = data.find(nb, start)
        if i < 0: break
        out.append(data[max(0,i-span):i+len(nb)+span].decode("ascii", "replace"))
        start = i + 1
    return out

def show(title, needle, span=240, limit=4):
    print(f"\n=== {title} ===")
    hits = raw_ctx(needle, span, limit)
    if not hits: print("  (no hits)")
    for c in hits:
        print("   ", c.replace("\n", " ")[:400])

show("AUTH type:api", '"type":"api"', 260, 4)
show("AUTH type api spaced", '"type": "api"', 200, 2)
show("CUSTOM PROVIDER npm", '@ai-sdk/openai-compatible', 260, 4)
show("PROVIDER CONFIG npm key", '"npm"', 260, 4)
show("CONFIG small_model", 'small_model', 240, 3)
show("CONFIG model key", '"model"', 200, 3)
show("TOOL STATUS pending/running", 'pending",running', 240, 3)
show("PART step-start", 'step-start', 200, 2)
show("PART file", '"file-parts"', 200, 2)
show("TODO part", '"todo"', 200, 2)

# part type enum: look for zod-ish enum lists of part types
print("\n=== PART TYPE ENUM candidates ===")
for m in re.finditer(rb'"text","reasoning"[^\]]{0,120}', data):
    print("   ", m.group().decode("ascii", "replace")[:200])
for m in list(re.finditer(rb'type:[a-z]\("(text|tool|reasoning|file|step-start)[^"]{0,80}', data))[:8]:
    print("   ", m.group().decode("ascii", "replace")[:200])
# schema-ish: "tool","text" sequences
for m in list(re.finditer(rb'enum\(\["text"[^\]]{0,150}', data))[:5]:
    print("   E:", m.group().decode("ascii", "replace")[:220])

# tool state schema
print("\n=== TOOL STATE schema ===")
for m in list(re.finditer(rb'status:[a-z]\("pending"[^\)]{0,120}\)', data))[:5]:
    print("   ", m.group().decode("ascii", "replace")[:220])
for m in list(re.finditer(rb'"pending"[^"]{0,10}"running"[^"]{0,10}"completed"[^"]{0,10}"error"', data))[:3]:
    print("   ", m.group().decode("ascii", "replace")[:160])

# provider list / model fields
print("\n=== MODEL LIST fields ===")
for m in list(re.finditer(rb'\{id:[a-z]\.string\(\),name:[a-z]\.string\(\)[^}]{0,200}', data))[:6]:
    print("   ", m.group().decode("ascii", "replace")[:260])
