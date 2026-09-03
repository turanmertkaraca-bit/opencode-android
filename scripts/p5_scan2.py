#!/usr/bin/env python3
"""P5 scan #2: precise provider/model endpoints + message body fields + DELETE."""
import re, sys, os

BIN = sys.argv[1]
data = open(BIN, 'rb').read()

def scan(name, pat, flags=0, limit=50):
    hits = sorted(set(m.decode('utf-8', 'replace') for m in re.findall(pat, data, flags)))
    print(f"\n== {name} ({len(hits)} unique) ==")
    for h in hits[:limit]:
        print("  ", h[:170])
    if len(hits) > limit:
        print(f"   ... +{len(hits)-limit} more")

scan("config/provider routes", rb'/config[/a-zA-Z0-9_.:{}-]{0,50}')
scan("provider routes", rb'/provider[/a-zA-Z0-9_.:{}-]{0,50}')
scan("project routes", rb'/project[/a-zA-Z0-9_.:{}-]{0,50}')
scan("model routes/fields", rb'/session/[:{ ]{0,3}[a-zA-Z]+/model[a-zA-Z0-9/_.:{}-]{0,40}')
scan("providerID context", rb'[a-zA-Z0-9_" ,:{}\[\]]{0,30}providerID[a-zA-Z0-9_" ,:{}\[\]]{0,60}', 0, 30)
scan("modelID context", rb'[a-zA-Z0-9_" ,:{}\[\]]{0,30}modelID[a-zA-Z0-9_" ,:{}\[\]]{0,60}', 0, 30)
scan("session delete hints", rb'(?:deleteSession|sessionDelete|removeSession|session\.delete|session_remove)[a-zA-Z0-9_.]{0,30}')
scan("delete route strings", rb'DELETE["\x27` ]{0,3}[,:(]{0,3}["\x27`/a-zA-Z0-9_.:{}-]{0,60}', 0, 30)
scan("models list shape", rb'(?:models|providers)[a-zA-Z0-9_]{0,10}["\x27]?\s*[:=]', 0, 25)
scan("tui/model dialog hints", rb'(?:model\.list|listModels|getProviders|providers\.list|models\.list)[a-zA-Z0-9_.]{0,30}')
