import json, re, sys, collections, unicodedata
A = '́'; V = set('аеёиоуыэюяАЕЁИОУЫЭЮЯ')
ALLOWED = re.compile(r'^[а-яёА-ЯЁ́ .,!?—:;]+$')
errs = []; cnt = collections.Counter(); ids = set()
WR = {3: (5, 8), 4: (15, 30), 5: (35, 60), 6: (50, 90), 7: (80, 140), 8: (120, 200), 9: (170, 280)}
def words(s): return re.findall(r'[а-яёА-ЯЁ́]+', s)
def chk(where, s):
    if unicodedata.normalize('NFC', s.replace(A, '')) != s.replace(A, ''): errs.append(f'{where}: not NFC')
    if not ALLOWED.match(s): errs.append(f'{where}: bad chars {set(c for c in s if not ALLOWED.match(c))} in {s!r}')
    if re.search(r'[A-Za-z0-9]', s): errs.append(f'{where}: latin/digit')
    for i, c in enumerate(s):
        if c == A and (i == 0 or s[i-1] not in V): errs.append(f'{where}: acute not after vowel in {s!r}')
    for w in words(s):
        nv = sum(c in V for c in w); na = w.count(A); ne = sum(c in 'ёЁ' for c in w)
        if nv >= 2 and na + ne != 1: errs.append(f'{where}: {w!r} stress marks={na}+ё{ne}')
        if nv <= 1 and na: errs.append(f'{where}: monosyllable with acute {w!r}')
for n, line in enumerate(open(sys.argv[1], encoding='utf-8'), 1):
    try: o = json.loads(line)
    except Exception as e: errs.append(f'line {n}: {e}'); continue
    lv = o['level']; cnt[lv] += 1
    if o['id'] != f'ru-L{lv}-{cnt[lv]:03d}' or o['id'] in ids: errs.append(f"{o['id']}: bad id")
    ids.add(o['id'])
    if not re.fullmatch(r'[а-яё]+', o['theme']): errs.append(f"{o['id']}: theme")
    chk(o['id'], o['text'])
    nwc = len(words(o['text']))
    if lv in WR and not WR[lv][0] <= nwc <= WR[lv][1]: errs.append(f"{o['id']}: {nwc} words")
    if lv == 4 and not re.search('[?!]', o['text']): errs.append(f"{o['id']}: no ?/!")
    if lv == 1:
        ws = words(o['text'])
        if not 3 <= len(ws) <= 4: errs.append(f"{o['id']}: L1 count")
    if lv == 2 and not 2 <= nwc <= 4: errs.append(f"{o['id']}: L2 {nwc} words")
    if lv == 8 and '—' not in o['text']: errs.append(f"{o['id']}: L8 без прямой речи")
    if lv >= 6 and re.search(r'[:;]\s*[:;]|\s[.,!?:;]', o['text']): errs.append(f"{o['id']}: пробел перед знаком")
    if 2 <= lv <= 5 and not 1 <= len(o['new_words']) <= 2: errs.append(f"{o['id']}: new_words")
    if lv >= 6 and not 0 <= len(o['new_words']) <= 3: errs.append(f"{o['id']}: new_words")
    if 'quiz' in o:
        q = o['quiz']   # без ударений: показывается после чтения, уровни L6+ ударений не показывают
        if not ALLOWED.match(q['q']): errs.append(f"{o['id']}: quiz chars {q['q']!r}")
        if len(q['options']) != 3 or len(set(q['options'])) != 3: errs.append(f"{o['id']}: quiz options")
        for x in q['options']:
            if not ALLOWED.match(x): errs.append(f"{o['id']}: quiz chars {x!r}")
    for w in o['new_words']:
        chk(o['id'] + ' nw', w['word']); chk(o['id'] + ' ex', w['explain'])
print(dict(sorted(cnt.items())))
want = {1: 35, 2: 35, 3: 30, 4: 30, 5: 50, 6: 80, 7: 75, 8: 65, 9: 62}
if dict(cnt) != want: errs.append(f'counts {dict(cnt)}')
print('\n'.join(errs) or 'OK'); sys.exit(1 if errs else 0)
