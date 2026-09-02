#!/usr/bin/env python3
"""p6_scan_binary.py — verify opencode v1.18.25 API shapes from the ELF itself.
Scans raw strings + context windows around key hits."""
import re, sys, json

BIN = "/home/z/my-project/p6-work/opencode"
data = open(BIN, "rb").read()
print(f"binary: {len(data):,} bytes")

# printable ascii strings >= 6 chars
STR = re.compile(rb"[\x20-\x7e]{6,}")
strings = None
def get_strings():
    global strings
    if strings is None:
        strings = [m.group().decode() for m in STR.finditer(data)]
    return strings

def find(pat, limit=12, ctx=0):
    """regex over strings; returns matches with optional context window"""
    out = []
    rx = re.compile(pat)
    for s in get_strings():
        if rx.search(s):
            out.append(s)
            if len(out) >= limit:
                break
    return out

def raw_ctx(needle, span=160, limit=4):
    """raw byte context around a needle"""
    out, start = [], 0
    nb = needle.encode()
    while len(out) < limit:
        i = data.find(nb, start)
        if i < 0: break
        out.append(data[max(0,i-span):i+len(nb)+span].decode("ascii", "replace"))
        start = i + 1
    return out

def section(title):
    print(f"\n=== {title} ===")

# 1. reasoning/thinking parts
section("1. REASONING / THINKING parts")
for s in find(r"^reasoning$|reasoning[A-Z_]|thinking|\"reasoning\"", 20): print(" ", s[:150])
print(" -- raw ctx 'reasoning':")
for c in raw_ctx("reasoning", 200, 3): print("   ", c.replace("\n"," ")[:280])

# 2. tool part shape
section("2. TOOL part state fields")
for pat in [r"callID", r"pending.*running.*completed", r"\"state\"", r"status.*(pending|running)"]:
    for s in find(pat, 6): print(" ", s[:200])
print(" -- raw ctx 'callID':")
for c in raw_ctx("callID", 220, 3): print("   ", c.replace("\n"," ")[:320])

# 3. auth.json
section("3. AUTH.JSON format")
print(" -- raw ctx 'auth.json':")
for c in raw_ctx("auth.json", 200, 4): print("   ", c.replace("\n"," ")[:300])
for s in find(r"auth\.json|\"api\"|apiKey", 10): print(" ", s[:160])

# 4. custom provider config
section("4. CUSTOM PROVIDER / @ai-sdk")
for s in find(r"@ai-sdk/[a-z-]+", 25): print(" ", s[:160])
print(" -- raw ctx 'baseURL':")
for c in raw_ctx("baseURL", 200, 3): print("   ", c.replace("\n"," ")[:300])
for s in find(r"openai-compatible", 6): print(" compat:", s[:160])

# 5. config keys
section("5. CONFIG keys (model / small_model / provider)")
for s in find(r"small_model|\"model\"|defaultModel|\"provider\"", 14): print(" ", s[:180])

# 6. model info fields
section("6. MODEL fields")
for s in find(r"contextWindow|context_window|modalities|tool_call|interleaved", 20): print(" ", s[:160])
print(" -- raw ctx 'contextWindow':")
for c in raw_ctx("contextWindow", 260, 2): print("   ", c.replace("\n"," ")[:380])

# 7. tokens / cost
section("7. TOKENS / COST fields")
for s in find(r"cacheRead|cacheWrite|cache_read|cache_write", 10): print(" ", s[:160])
print(" -- raw ctx '\"cost\"':")
for c in raw_ctx('"cost"', 220, 3): print("   ", c.replace("\n"," ")[:320])
print(" -- raw ctx 'cacheRead':")
for c in raw_ctx("cacheRead", 260, 2): print("   ", c.replace("\n"," ")[:380])

# 8. providers response
section("8. PROVIDERS endpoint fields")
print(" -- raw ctx 'defaultProviderID':")
for c in raw_ctx("defaultProviderID", 200, 2): print("   ", c.replace("\n"," ")[:300])
for s in find(r"providerID|\"providers\"|defaultModel", 10): print(" ", s[:160])

# 9. part types sweep
section("9. PART TYPES (type\":\"xxx strings)")
for s in sorted(set(re.findall(r'type\\?":\\?"([a-z_]{3,20})', " ".join(get_strings()))))[:60]:
    print(" ", s)
