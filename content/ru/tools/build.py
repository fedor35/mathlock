import json, sys
sys.path.insert(0, '.')
from data import L1, L2, L3, L4, L5, L6, L7, L8, L9
A = '́'
def conv(s): return s.replace("'", A)
out = []
for lvl, items in enumerate([L1, L2, L3, L4, L5, L6, L7, L8, L9], 1):
    for i, (theme, text, nw) in enumerate(items, 1):
        out.append({"id": f"ru-L{lvl}-{i:03d}", "level": lvl, "theme": theme, "text": conv(text),
                    "new_words": [{"word": conv(w), "explain": conv(e)} for w, e in nw]})
with open(sys.argv[1], 'w', encoding='utf-8') as f:
    for o in out: f.write(json.dumps(o, ensure_ascii=False) + '\n')
print(len(out))
