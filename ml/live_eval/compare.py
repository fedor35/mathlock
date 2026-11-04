"""Сверка решений приложения (states в recordings/*.json) с независимой расшифровкой Whisper по словам.
python compare.py <каталог данных: recordings/, whisper_*.jsonl, progress-*.tsv>
Печатает: по чтецам — слов, красных у приложения, из них Whisper слышит верно (кандидаты в ложные красные);
зелёных у приложения, которые Whisper слышит иначе (кандидаты в ложные зелёные); и списки примеров."""
import difflib, glob, json, re, sys, collections
from pathlib import Path

D = Path(sys.argv[1])
def norm(w): return re.sub(r"[^а-яa-z0-9-]", "", w.lower().replace("ё", "е").replace("́", ""))
def words(t): return [x for x in (norm(w) for w in re.split(r"[\s—–]+", t)) if x and x != "-"]

wh = {}
for f in D.glob("whisper_*.jsonl"):
    for l in f.read_text().splitlines():
        if l.strip(): j = json.loads(l); wh[j["rec"]] = j["whisper"]
journal = {}
for f in D.glob("progress-*.tsv"):
    who = f.stem.replace("progress-", "")
    for l in f.read_text().splitlines():
        p = l.split("\t")
        if len(p) > 6: journal[p[6]] = who
names = {}
for f in (D / "profiles").glob("p*.json") if (D / "profiles").exists() else []:
    j = json.loads(f.read_text()); names[j["id"]] = j["name"]

tot = collections.Counter(); ex_red, ex_green = [], []
for rec, who in sorted(journal.items()):
    jf = D / "recordings" / f"{rec}.json"
    if not jf.exists() or rec not in wh: continue
    j = json.loads(jf.read_text()); st = j["states"]; tw = words(j["text"]); hw = words(wh[rec])
    if len(tw) != len(st): print("длины не совпали", rec, len(tw), len(st)); continue
    sm = difflib.SequenceMatcher(a=tw, b=hw, autojunk=False)
    heard = [None] * len(tw)   # что Whisper поставил на место слова: то же / другое / пропуск
    for op, a0, a1, b0, b1 in sm.get_opcodes():
        if op == "equal":
            for k in range(a1 - a0): heard[a0 + k] = tw[a0 + k]
        elif op == "replace":
            for k in range(a1 - a0): heard[a0 + k] = hw[b0 + k] if b0 + k < b1 else "∅"
        elif op == "delete":
            for k in range(a0, a1): heard[k] = "∅"
    # дальше последнего слова, которое Whisper услышал, ребёнок не читал — не оцениваем
    last = max([i for i, h in enumerate(heard) if h not in (None, "∅")], default=-1)
    for i in range(last + 1):
        s, ok_w = st[i], heard[i] == tw[i]
        tot[who, "words"] += 1
        if s == "W":
            tot[who, "red"] += 1
            if ok_w: tot[who, "red_but_whisper_ok"] += 1; ex_red.append((who, rec, i, tw[i], " ".join(tw[max(0, i-2):i+3])))
        elif s == "O":
            tot[who, "green"] += 1
            if not ok_w and heard[i] != "∅" and heard[i] is not None:
                tot[who, "green_but_whisper_diff"] += 1; ex_green.append((who, rec, i, tw[i], heard[i]))
            elif heard[i] == "∅": tot[who, "green_whisper_skip"] += 1
for who in sorted({k[0] for k in tot}):
    g = lambda k: tot[who, k]
    print(f"{who} {names.get(who, '')}: слов {g('words')}, красных {g('red')} (из них Whisper слышит верно {g('red_but_whisper_ok')}), "
          f"зелёных {g('green')} (Whisper слышит иначе {g('green_but_whisper_diff')}, пропускает {g('green_whisper_skip')})")
print("\nКрасные, где Whisper слышит верно:")
for e in ex_red: print("  ", *e)
print("\nЗелёные, где Whisper слышит другое слово:")
for e in ex_green: print("  ", *e)
