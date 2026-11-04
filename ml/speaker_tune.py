#!/usr/bin/env python3
"""Пороги опознания чтеца и классификатор «ребёнок/взрослый» для выбранной модели (CAM++ zh_en advanced).

Сценарий как на телефоне: профиль — нормированное среднее нескольких попыток, новая попытка сравнивается со всеми
профилями. Печатает распределения «свой профиль» / «лучший чужой» и долю решений при порогах.
Классификатор ребёнок/взрослый: логистическая регрессия на эмбеддингах speechocean762 (дети 6–10 против
взрослых ≥ 19), проверка с разбиением по людям и на чужих голосах (TTS-персонажи, Фёдор).
Веса пишутся в JSON для android/reading (SpeakerId.KID_W).

python -I ml/speaker_tune.py --root /mnt/2tb/mathlock-ml/spk
"""
import argparse, glob, json, os, random, sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from speaker_eval import Model, load, SR, voiced  # noqa: E402

MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="/mnt/2tb/mathlock-ml/spk")
    ap.add_argument("--tts", default="/mnt/2tb/mathlock-ml/ttscmp")
    a = ap.parse_args()
    cache = os.path.join(a.root, "emb_campplus.npz")
    so = os.path.join(a.root, "so")
    if not os.path.exists(cache):
        m = Model(os.path.join(a.root, "models", MODEL))
        age = {}
        for part in ("train", "test"):
            for l in open(os.path.join(so, part, "spk2age")):
                s, y = l.split(); age[s] = int(y)
        rows = []
        for s in sorted(age):
            for f in sorted(glob.glob(os.path.join(so, "WAVE", "SPEAKER%04d" % int(s), "*.WAV"))):
                rows.append((f, "so:" + s, age[s]))
        for d in ("out_ru3", "out_ru4"):
            for v in ("devochka", "malchik", "skazochnik", "feya", "velikan"):
                fs = sorted(glob.glob(os.path.join(a.tts, d, "*.%s.ogg" % v))); random.Random(1).shuffle(fs)
                rows += [(f, "tts:" + v, -1) for f in fs[:30]]
        rows += [(f, "fedor", 45) for f in sorted(glob.glob(os.path.join(a.root, "fedor", "*.wav")))]
        E, D = [], []
        for i, (f, s, y) in enumerate(rows):
            x = load(f); E.append(m.embed(x)); D.append(voiced(x).sum() / 100.0)
            if i % 500 == 0: print(i, len(rows), flush=True)
        np.savez(cache, emb=np.stack(E), dur=np.array(D), spk=np.array([r[1] for r in rows]), age=np.array([r[2] for r in rows]),
                 files=np.array([r[0] for r in rows]))
    z = np.load(cache)
    emb, spk, age, dur = z["emb"], z["spk"], z["age"], z["dur"]
    print("эмбеддингов %d" % len(emb))

    # --- Фёдор: попарные косинусы и длительность речи
    fi = np.where(spk == "fedor")[0]
    print("\nФёдор: речь, с:", " ".join("%.1f" % dur[i] for i in fi))
    C = emb[fi] @ emb[fi].T
    for k, i in enumerate(fi): print("  %-34s %4.1f с  " % (os.path.basename(z["files"][i]), dur[i]) + " ".join("%5.2f" % c for c in C[k]))
    good = [i for i in fi if dur[i] >= 1.0]
    P = emb[good].mean(0); P /= np.linalg.norm(P)
    print("  профиль Фёдора (все записи ≥1 с речи) ↔ каждая:", " ".join("%.2f" % (emb[i] @ P) for i in fi))
    so_i = np.where(np.char.startswith(spk.astype(str), "so:"))[0]
    print("  профиль Фёдора ↔ so: max %.2f, p99 %.2f; ↔ tts max %.2f" % (
        np.max(emb[so_i] @ P), np.percentile(emb[so_i] @ P, 99), np.max(emb[np.char.startswith(spk.astype(str), "tts:")] @ P)))

    # --- «Семьи»: профиль = среднее k попыток, распределение лучшего своего/чужого
    by = {}
    for i in so_i: by.setdefault(spk[i], []).append(i)
    kids = [s for s in by if age[by[s][0]] <= 10]; adults = [s for s in by if age[by[s][0]] >= 19]
    rnd = random.Random(0)
    for k_en in (1, 3, 8):
        own, oth = [], []
        for _ in range(400):
            fam = rnd.sample(kids, 3) + rnd.sample(adults, 2)
            prof, tests = [], []
            for j, s in enumerate(fam):
                u = by[s][:]; rnd.shuffle(u)
                p = emb[u[:k_en]].mean(0); prof.append(p / np.linalg.norm(p))
                tests += [(j, i) for i in u[k_en:]]
            Pm = np.stack(prof)
            for j, i in tests:
                c = Pm @ emb[i]; own.append(c[j]); oth.append(np.max(np.delete(c, j)))
            # «незнакомец»: ещё один ребёнок не из семьи
            for s in rnd.sample([s for s in kids if s not in fam], 1):
                for i in by[s]: oth.append(np.max(Pm @ emb[i])); own.append(np.nan)
        own, oth = np.array(own), np.array(oth)
        mk = ~np.isnan(own)
        print("\nпрофиль из %d попыток: свой p1/p5/p50 %.2f/%.2f/%.2f; лучший чужой p50/p95/p99 %.2f/%.2f/%.2f; незнакомец max p50/p95/p99 %.2f/%.2f/%.2f" % (
            k_en, *np.percentile(own[mk], [1, 5, 50]), *np.percentile(oth[mk], [50, 95, 99]), *np.percentile(oth[~mk], [50, 95, 99])))
        for acc, mar, new in ((0.45, 0.10, 0.30), (0.50, 0.12, 0.30), (0.40, 0.10, 0.25), (0.50, 0.15, 0.32)):
            best_is_own = own[mk] > oth[mk]
            ok = (own[mk] >= acc) & (own[mk] - oth[mk] >= mar)
            wrong = (oth[mk] >= acc) & (oth[mk] - own[mk] >= mar)
            newp = np.maximum(own[mk], oth[mk]) < new
            stranger_new = (oth[~mk] < new).mean(); stranger_assigned = (oth[~mk] >= acc).mean()
            print("  порог %.2f отрыв %.2f новый<%.2f: свой верно %.1f%%, к чужому %.2f%%, ложный новый %.1f%%, не определён %.1f%% | незнакомец → новый %.0f%%, → в чужой профиль %.1f%%" % (
                acc, mar, new, 100 * ok.mean(), 100 * wrong.mean(), 100 * newp.mean(), 100 * (1 - ok.mean() - wrong.mean() - newp.mean()),
                100 * stranger_new, 100 * stranger_assigned))

    # --- ребёнок / взрослый: логистическая регрессия, разбиение по людям
    sel = [i for i in so_i if age[i] <= 10 or age[i] >= 19]
    X = emb[sel]; y = (age[sel] <= 10).astype(float); g = spk[sel]
    people = sorted(set(g)); rnd.shuffle(people); fold = {p: k % 5 for k, p in enumerate(people)}
    def fit(X, y, l2=1e-2, it=3000, lr=0.5):
        w = np.zeros(X.shape[1]); b = 0.0
        for _ in range(it):
            p = 1 / (1 + np.exp(-(X @ w + b))); gw = X.T @ (p - y) / len(y) + l2 * w; gb = np.mean(p - y)
            w -= lr * gw; b -= lr * gb
        return w, b
    accs = []
    for k in range(5):
        tr = np.array([fold[p] != k for p in g])
        w, b = fit(X[tr], y[tr]); pr = (X[~tr] @ w + b) > 0
        accs.append(np.mean(pr == (y[~tr] > 0)))
    w, b = fit(X, y)
    print("\nребёнок/взрослый по одной фразе, 5 фолдов по людям: %.1f%%" % (100 * np.mean(accs)))
    for s in sorted({x for x in spk if not str(x).startswith("so:")}):
        idx = np.where(spk == s)[0]; p = 1 / (1 + np.exp(-(emb[idx] @ w + b)))
        print("  %-16s P(ребёнок) медиана %.2f, доля > 0.5: %.0f%%" % (s, np.median(p), 100 * np.mean(p > 0.5)))
    teen = np.where((age >= 11) & (age <= 18))[0]
    print("  подростки 11–18 (не в обучении): P(ребёнок) медиана %.2f" % np.median(1 / (1 + np.exp(-(emb[teen] @ w + b)))))
    json.dump({"w": [round(float(v), 5) for v in w], "b": round(float(b), 5), "cv_acc": float(np.mean(accs))},
              open(os.path.join(a.root, "kid_adult_lr.json"), "w"))


if __name__ == "__main__":
    main()
