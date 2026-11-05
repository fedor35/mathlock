"""Синтетические ошибки чтения: тексты корпуса -> «прочитанный» вариант с подставленными ошибками + метки по словам.

Типы (метка — что должна показать сверка):
  end   — неверное окончание (кошка → кошку)                      W (сейчас: мягкая подсказка, со strictEndings — W)
  sub   — другое похожее слово (дом → дым, лиса → липа)            W
  skip  — пропуск слова                                            W
  rep   — повтор слова (кошка кошка)                               O
  start — фальстарт с исправлением (ко… кошка)                     O
  syl   — по слогам (ма, ши, на)                                   O
  fix   — неверное окончание и сразу исправление (кошку… кошка)   O
Похожие безударные пары (а-о, е-и, е-я, и-ы) в окончаниях не подставляются — их сверка прощает намеренно.

python3 make_jobs.py texts.jsonl jobs.jsonl [вариантов_на_текст=2]"""
import json, random, re, sys

ACUTE = "́"
WORD = re.compile(r"[А-Яа-яЁёA-Za-z](?:[А-Яа-яЁёA-Za-ź]|-(?=[А-Яа-яЁёA-Za-z]))*")
VOW = set("аеёиоуыэюя")
SIMILAR = {frozenset(p) for p in ("ао", "еи", "ея", "иы")}
# окончание -> правдоподобные замены (дети угадывают слово по началу и «дочитывают» не то)
ENDS = [("ами", ["ах", "ой"]), ("ого", ["ому", "ой"]), ("ему", ["его", "ей"]), ("ому", ["ого", "ой"]), ("его", ["ему"]),
        ("ая", ["ую", "ой"]), ("яя", ["юю"]), ("ой", ["ую", "ые"]), ("ую", ["ой", "ые"]), ("ые", ["ую", "ой"]),
        ("ый", ["ую", "ые"]), ("ий", ["ую", "ие"]), ("ие", ["ую", "ий"]), ("ых", ["ую", "ые"]), ("их", ["ую"]),
        ("ом", ["у", "ы"]), ("ем", ["ю"]), ("ет", ["ут", "ло"]), ("ит", ["ят", "ла"]), ("ут", ["ет", "ли"]),
        ("ят", ["ит", "ли"]), ("ла", ["ли", "лу"]), ("ли", ["ла", "лу"]), ("ть", ["ла", "ли"]), ("ка", ["ку", "ке"]),
        ("ки", ["ку", "ке"]), ("а", ["у", "ой"]), ("у", ["а", "ой"]), ("ы", ["у", "ой"]), ("и", ["у", "ей"]),
        ("е", ["у", "ой"]), ("о", ["у"]), ("я", ["ю", "ей"]), ("ю", ["я", "ей"]), ("ь", ["ю", "ем"])]


def plain(w): return w.replace(ACUTE, "").lower().replace("ё", "е")


def lev(a, b):
    d = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        q, d[0] = d[0], i
        for j, cb in enumerate(b, 1): q, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, q + (ca != cb))
    return d[-1]


def similar_swap(a, b):
    """Замена отличается только похожими безударными гласными — такое не подставляем."""
    if len(a) != len(b): return False
    diff = [frozenset((x, y)) for x, y in zip(a, b) if x != y]
    return all(p in SIMILAR for p in diff)


def wrong_ending(w, rnd):
    """Слово с акутом -> слово с другим окончанием (акут сохраняется, если он в основе), либо None."""
    p = w.replace(ACUTE, "")
    low = p.lower()
    for suf, alts in ENDS:
        if low.endswith(suf) and len(low) - len(suf) >= 2:
            alts = [a for a in alts if not similar_swap(suf, a)]
            if not alts: return None
            alt = rnd.choice(alts)
            stem_len = len(p) - len(suf)
            # акут: позиция в исходном слове с акутом
            out, k = [], 0
            for ch in w:
                if ch == ACUTE:
                    if k <= stem_len: out.append(ch)
                    continue
                if k < stem_len: out.append(ch)
                k += 1
            return "".join(out) + alt
    return None


def syl_split(p):
    """Слово без акута -> слоги, склеенные обратно ровно в слово."""
    out = []
    vow = [k for k, c in enumerate(p) if c.lower() in VOW]
    if len(vow) < 2: return None
    start = 0
    for a, b in zip(vow, vow[1:]):
        cons = b - a - 1
        cut = a + 1 if cons <= 1 else a + 2  # «ма|ши|на», «кош|ка»
        if cut < len(p) and p[cut].lower() in "ьъ": cut += 1
        out.append(p[start:cut]); start = cut
    out.append(p[start:])
    return out if "".join(out) == p and all(out) else None


def main():
    src, dst = sys.argv[1], sys.argv[2]
    nvar = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    texts = [json.loads(l) for l in open(src) if l.strip()]
    pool = {}
    for t in texts:
        for m in WORD.finditer(t["text"]):
            w = m.group()
            if len(plain(w)) >= 3 and "-" not in w: pool.setdefault(plain(w)[0], set()).add(w.replace(ACUTE, "").lower())
    pool = {k: sorted(v) for k, v in pool.items()}
    voices = ["baya", "kseniya", "xenia", "aidar", "eugene"]
    stats = {}
    with open(dst, "w") as f:
        for ti, t in enumerate(texts):
            words = list(WORD.finditer(t["text"]))
            for v in range(nvar):
                rnd = random.Random(f"{t['id']}#{v}")
                n = len(words)
                k = max(1, round(n / 7))
                cand = [i for i, m in enumerate(words)]
                rnd.shuffle(cand)
                chosen, used = {}, set()
                for i in cand:
                    if len(chosen) >= k: break
                    if i in used or i - 1 in used or i + 1 in used: continue  # ошибки не подряд
                    w = words[i].group(); p = plain(w)
                    # реже использованный тип — первым (поровну по типам; «end» вдвое чаще — он главный вопрос)
                    types = sorted(["end", "sub", "skip", "rep", "start", "syl", "fix"],
                                   key=lambda x: (stats.get(x, 0) / (2 if x == "end" else 1), rnd.random()))
                    for ty in types:
                        rep = None
                        if ty in ("end", "fix") and len(p) >= 3 and "-" not in w:
                            e = wrong_ending(w, rnd)
                            if e: rep = (e if ty == "end" else e + "… " + w)
                        elif ty == "sub" and len(p) >= 3 and "-" not in w:
                            c = [x for x in pool.get(p[0], []) if abs(len(x) - len(p)) <= 1 and 2 <= lev(x, p) <= max(2, len(p) // 2)
                                 and not similar_swap(x, p) and x[:2] == p[:2] if len(p) >= 4] or \
                                [x for x in pool.get(p[0], []) if abs(len(x) - len(p)) <= 1 and 2 <= lev(x, p) <= 2 and not similar_swap(x, p)]
                            if c:
                                x = rnd.choice(c)
                                rep = x.capitalize() if w[0].isupper() else x
                        elif ty == "skip" and 0 < i < n - 1 and len(p) >= 3:  # короткие предлоги сверка прощает намеренно  # не первое и не последнее: хвост сверка трактует как «не дочитал»
                            rep = ""
                        elif ty == "rep" and len(p) >= 3:
                            rep = w + ", " + w.lower()
                        elif ty == "start" and len(p) >= 4:
                            s = syl_split(w.replace(ACUTE, ""))
                            if s: rep = s[0] + "… " + w
                        elif ty == "syl" and len(p) >= 4:
                            s = syl_split(w.replace(ACUTE, ""))
                            if s and len(s) >= 2: rep = ", ".join(s)
                        if rep is not None:
                            chosen[i] = (ty, rep); used.add(i); break
                spoken, last = [], 0
                labels, kinds = [], []
                for i, m in enumerate(words):
                    spoken.append(t["text"][last:m.start()])
                    if i in chosen:
                        ty, rep = chosen[i]; spoken.append(rep)
                        labels.append("W" if ty in ("end", "sub", "skip") else "O"); kinds.append(ty)
                        stats[ty] = stats.get(ty, 0) + 1
                    else:
                        spoken.append(m.group()); labels.append("O"); kinds.append("-")
                    last = m.end()
                spoken.append(t["text"][last:])
                s = re.sub(r"\s+([,.!?…])", r"\1", re.sub(r" {2,}", " ", "".join(spoken)))
                f.write(json.dumps({"id": f"{t['id']}.e{v}", "text_id": t["id"], "level": t["level"], "voice": voices[(ti * nvar + v) % len(voices)],
                                    "child": (ti + v) % 2 == 0, "spoken": s, "labels": "".join(labels), "kinds": kinds},
                                   ensure_ascii=False) + "\n")
    print("подставлено:", stats, file=sys.stderr)


if __name__ == "__main__":
    main()
