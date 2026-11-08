#!/usr/bin/env python3
# Usage: python3 -I tools/settings-search-index.py <repo root>  -> JSON of row -> string resources (for SettingsSearchIndex.kt)
"""Builds row -> string resource names for the settings search index, by reading the Compose source."""
import re, glob, sys, json
root = sys.argv[1]
src = {}
for f in glob.glob(root + '/app/src/main/java/com/baba/callvault/ui/**/*.kt', recursive=True):
    src[f] = open(f).read()
# function name -> body text (brace matched)
funcs = {}
for f, s in src.items():
    for m in re.finditer(r'fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_.]+\.)?([A-Z][A-Za-z0-9_]*)\s*\(', s):
        name = m.group(1)
        i = s.find('{', m.end())
        # skip expression-bodied functions
        eq = s.find('=', m.end())
        if i < 0: continue
        depth = 0; j = i
        while j < len(s):
            c = s[j]
            if c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        funcs.setdefault(name, s[i:j])
scope = open(root + '/app/src/main/java/com/baba/callvault/ui/screens/SettingsScope.kt').read()
block = scope[scope.index('fun rowsOf'):scope.index('fun settingKeysOf')]
rows = re.findall(r'"([A-Z][A-Za-z0-9]+)"', block)
SKIP = {'SettingsDivider','SettingsSubHeader','ShownWhen','Column','Row','Text','Icon','Spacer','Box'}
def strings_of(name, seen, depth):
    if name in seen or depth > 4 or name not in funcs: return set()
    seen.add(name)
    body = funcs[name]
    out = set(re.findall(r'R\.string\.([a-z0-9_]+)', body))
    for callee in set(re.findall(r'\b([A-Z][A-Za-z0-9]+)\(', body)) - SKIP:
        out |= strings_of(callee, seen, depth + 1)
    return out
res = {r: sorted(strings_of(r, set(), 0)) for r in rows}
json.dump(res, sys.stdout, indent=1)
