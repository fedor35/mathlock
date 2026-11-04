"""Общие функции для классификатора ударного слога (этап 4).

Всё, что повторяется на телефоне (StressClassifier.java), сделано здесь явно и без torchaudio:
  * разбор текста на слова и ударные гласные;
  * CTC forced alignment (Витерби) по лог-вероятностям GigaAM, кадр 40 мс;
  * признаки гласной из лог-мел (10 мс), PCM (F0 автокорреляцией) и логитов CTC.
Код в Java повторяет эти функции буквально; фикстура JUnit строится из них же (make_fixture.py)."""
import re
import numpy as np

VOCAB = list(" абвгдежзийклмнопрстуфхцчшщъыьэюя")
BLANK = len(VOCAB)  # 33
VOWELS = "аеиоуыэюя"
VOW_IDS = [VOCAB.index(c) for c in VOWELS]
ACUTE = "́"
WORD = re.compile(r"[а-яёА-ЯЁ][а-яёА-ЯЁ́]*(?:-[а-яёА-ЯЁ][а-яёА-ЯЁ́]*)*")
SENT_END = re.compile(r"[.!?…]")
SR, HOP, F0_WIN = 16000, 160, 640
F0_LAG_MIN, F0_LAG_MAX, F0_VOICED = 16000 // 600, 16000 // 60, 0.3
SEG_MAX = 8  # максимум кадров (40 мс) на «гласная + пустые за ней»
MEL_RANGE = 10.0  # пол лог-мел: максимум записи − 10 (≈43 дБ); ниже — шум округления БПФ, на телефоне — шум микрофона


def parse_words(text):
    """-> список (буквы слова без ударения, ё->е, без дефиса; индекс ударной гласной среди гласных или -1;
    номер предложения). Ударная: перед акутом или «ё». Слово с 0 или >1 ударением -> -1."""
    out, sent = [], 0
    pos = 0
    for mt in WORD.finditer(text):
        if SENT_END.search(text[pos:mt.start()]) and out:
            sent += 1
        pos = mt.end()
        raw = mt.group(0).lower().replace("-", "")
        letters, stressed = [], []
        for ch in raw:
            if ch == ACUTE:
                if letters:
                    stressed.append(len(letters) - 1)
                continue
            if ch == "ё":
                stressed.append(len(letters)); ch = "е"
            letters.append(ch)
        w = "".join(letters)
        vpos = [i for i, c in enumerate(w) if c in VOWELS]
        st = sorted(set(stressed))
        lab = vpos.index(st[0]) if len(st) == 1 and st[0] in vpos else -1
        out.append((w, lab, sent))
    return out


def forced_align(lp, tokens):
    """CTC Витерби. lp [T][V+1] (лог-вероятности), tokens — индексы.
    -> start[], end[] (включительно) кадров каждого токена или None, если не помещается.
    Порядок предпочтения при равенстве: остаться, s-1, s-2 (как в Java)."""
    T, L = lp.shape[0], len(tokens)
    S = 2 * L + 1
    lab = np.full(S, BLANK, np.int64); lab[1::2] = tokens
    skip = np.zeros(S, bool)
    for s in range(3, S, 2):
        skip[s] = lab[s] != lab[s - 2]
    NEG = -1e30
    a = np.full(S, NEG); a[0] = lp[0, BLANK]
    if S > 1: a[1] = lp[0, lab[1]]
    bp = np.zeros((T, S), np.int8)
    lp64 = lp.astype(np.float64)
    for t in range(1, T):
        c0 = a
        c1 = np.concatenate([[NEG], a[:-1]])
        c2 = np.where(skip, np.concatenate([[NEG, NEG], a[:-2]]), NEG)
        st = np.stack([c0, c1, c2])
        k = np.argmax(st, 0)
        best = st[k, np.arange(S)]
        a = np.where(best <= NEG / 2, NEG, best + lp64[t, lab])
        bp[t] = k
    if S >= 2 and a[S - 2] > a[S - 1]:
        s = S - 2
    else:
        s = S - 1
    if a[s] <= NEG / 2:
        return None
    path = np.zeros(T, np.int64)
    for t in range(T - 1, -1, -1):
        path[t] = s
        s -= int(bp[t, s])
    if path[0] > 1:
        return None
    start = np.full(L, -1); end = np.full(L, -1)
    for t in range(T):
        s = int(path[t])
        if s % 2 == 1:
            i = s // 2
            if start[i] < 0: start[i] = t
            end[i] = t
    return start, end


def f0_track(pcm, n_mel):
    """F0 (Гц, 0 = невокализовано) на каждый кадр мел (10 мс): окно 640 отсчётов с 160*f,
    нормированная автокорреляция, порог 0.3. Прямой счёт, как в Java (векторизовано через FFT)."""
    nf = n_mel
    out = np.zeros(nf)
    pad = np.concatenate([pcm.astype(np.float64), np.zeros(F0_WIN)])
    idx = np.arange(nf)[:, None] * HOP + np.arange(F0_WIN)[None, :]
    X = pad[idx]
    X = X - X.mean(1, keepdims=True)
    F = np.fft.rfft(X, 2048, axis=1)
    ac = np.fft.irfft(F * np.conj(F), 2048, axis=1)[:, :F0_LAG_MAX + 1]
    r0 = ac[:, 0]
    seg = ac[:, F0_LAG_MIN:F0_LAG_MAX + 1]
    k = np.argmax(seg, 1)
    pk = seg[np.arange(nf), k]
    ok = (r0 > 1e-10) & (pk > F0_VOICED * r0)
    out[ok] = SR / (k[ok] + F0_LAG_MIN)
    return out


def f0_frame(pcm, f):
    """Один кадр F0 прямым счётом (эталон для Java и проверки f0_track)."""
    x = np.zeros(F0_WIN)
    seg = pcm[f * HOP: f * HOP + F0_WIN].astype(np.float64)
    x[:len(seg)] = seg
    x -= x.mean()
    r0 = float(np.dot(x, x))
    best, bk = -1e30, 0
    for k in range(F0_LAG_MIN, F0_LAG_MAX + 1):
        r = float(np.dot(x[:F0_WIN - k], x[k:]))
        if r > best: best, bk = r, k
    return SR / bk if (r0 > 1e-10 and best > F0_VOICED * r0) else 0.0


RAW_NAMES = ["ldur_v", "ldur_syl", "ldur_cc", "en_mean", "en_max", "lf0_mean", "lf0_max", "f0_slope", "voiced"]


def vowel_features(words, start, end, n_tok_frames, mel, f0, lp):
    """words — parse_words; start/end — выравнивание токенов цели " ".join(words).
    mel [64][Tm] (лог-мел 10 мс), f0 [Tm] (Гц), lp [T][34].
    -> список гласных: dict(word, vi, nv, label, sent, raw[9], mel[64], ctc[34], seg=(a,b) в кадрах 40 мс).
    Только слова с >=2 гласными."""
    Tm = mel.shape[1]
    mel = mel.astype(np.float64)
    mel = np.maximum(mel, mel.max() - MEL_RANGE)
    energy = np.log(np.exp(mel).sum(0) + 1e-12)
    rows = []
    k = 0
    L = len(start)
    for wi, (w, lab, sent) in enumerate(words):
        tok = list(range(k, k + len(w)))
        k += len(w) + 1
        nxt = lambda i: start[i + 1] if i + 1 < L else n_tok_frames
        vi = [tok[j] for j, c in enumerate(w) if c in VOWELS]
        if len(vi) < 2:
            continue
        wend = min(nxt(tok[-1]), end[tok[-1]] + 1 + SEG_MAX)
        segs = [(start[i], min(nxt(i), start[i] + SEG_MAX)) for i in vi]
        cen = [(start[i] + end[i] + 1) / 2 for i in vi]
        for j, i in enumerate(vi):
            a, b = segs[j]
            sb = start[vi[j + 1]] if j + 1 < len(vi) else wend
            cc = (cen[j + 1] if j + 1 < len(vi) else wend) - cen[j]
            ma, mb = 4 * a, min(4 * b, Tm)
            if mb <= ma: ma, mb = min(ma, Tm - 1), min(ma, Tm - 1) + 1
            e = energy[ma:mb]
            fz = f0[ma:mb]
            v = fz > 0
            if v.any():
                lf = np.log(fz[v]); tt = np.arange(ma, mb)[v] * 0.01
                slope = float(np.polyfit(tt - tt.mean(), lf, 1)[0]) if v.sum() >= 3 else 0.0
                lf0m, lf0x = float(lf.mean()), float(lf.max())
            else:
                lf0m = lf0x = np.nan; slope = 0.0
            raw = [np.log((b - a) * 0.04), np.log(max(sb - a, 1) * 0.04), np.log(max(cc, 0.5) * 0.04),
                   float(e.mean()), float(e.max()), lf0m, lf0x, slope, float(v.mean())]
            rows.append(dict(word=wi, w=w, vi=j, nv=len(vi), label=lab, sent=sent, raw=np.array(raw),
                             mel=mel[:, ma:mb].mean(1), ctc=lp[a:b].mean(0), seg=(int(a), int(b))))
    return rows
