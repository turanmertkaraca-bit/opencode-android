#!/usr/bin/env python3
"""P5 API surface scan: strings-scan the shipped opencode v1.18.25 arm64 binary
for endpoints/fields needed by P5 features (abort, models, session delete)."""
import re, sys, os

BIN = sys.argv[1]
data = open(BIN, 'rb').read()
print(f"binary: {os.path.basename(BIN)} {len(data)/1e6:.1f} MB")

def scan(name, pat, flags=0, limit=40):
    hits = sorted(set(m.decode('utf-8', 'replace') for m in re.findall(pat, data, flags)))
    print(f"\n== {name} ({len(hits)} unique) ==")
    for h in hits[:limit]:
        print("  ", h[:160])
    if len(hits) > limit:
        print(f"   ... +{len(hits)-limit} more")

# route patterns (opencode uses :param or {param} styles)
scan("abort routes", rb'[/a-zA-Z0-9_.:{}-]*abort[/a-zA-Z0-9_.:{}-]*')
scan("session routes", rb'/session[/a-zA-Z0-9_.:{}-]{0,60}')
scan("provider/model routes", rb'/(?:config|provider|model|app)[/a-zA-Z0-9_.:{}-]{0,60}')
scan("providerID/modelID fields", rb'[a-zA-Z0-9_]{0,20}(?:providerID|modelID)[a-zA-Z0-9_]{0,20}')
scan("sessionID fields in bodies", rb'(?:sessionID|requestID|callID)[a-zA-Z]{0,15}')
scan("HTTP methods near routes", rb'(?:DELETE|PATCH|PUT)[a-zA-Z \x00]{0,5}/[a-zA-Z0-9/_.:{}-]{0,50}')
scan("revert/summarize/children", rb'/session/[a-zA-Z0-9_.:{}$/-]{0,50}(?:revert|summarize|children|share|unshare)[a-zA-Z0-9/_.:{}-]{0,30}')
