"""Классификатор ударной гласной: обучение и честная оценка.
  python train.py feats.pkl results.json [быстро]
Модель оценивает каждую гласную слова, ответ — argmax по гласным слова.
Модели: listwise-логрегрессия (softmax по гласным слова), MLP 32 (то же), LightGBM lambdarank.
Разбиения:
  text  — 5 фолдов по текстам (GroupKFold): тексты теста не встречаются в обучении, голоса все;
  voice — голос теста и тексты теста не встречаются в обучении (5 голосов Seed-VC; голос v ↔ фолд текстов v);
  refs  — обучение только на эталонных движках (Silero/Vosk/RHVoice), тест на Seed-VC, тексты вне обучения.
Метрика — точность на слово (только слова с одним известным ударением)."""
import json, sys, time
import numpy as np, torch
from sklearn.model_selection import GroupKFold
from feats import load, blocks

torch.set_num_threads(16)
T = load(sys.argv[1]); B, GSD = blocks(T)
FAST = len(sys.argv) > 3
N = len(T["label"]); wg = T["wg"]; nW = wg.max() + 1
# слова: первая строка, число гласных, метка
first = np.r_[0, np.flatnonzero(np.diff(wg)) + 1]
assert (np.diff(wg) >= 0).all()
w_nv = T["nv"][first]; w_lab = T["label"][first]; w_tid = T["tid"][first]; w_voice = T["voice"][first]
w_src = T["src"][first]; w_word = T["w"][first]; w_has = w_lab >= 0
VC_VOICES = sorted(set(T["voice"][T["src"] == "vc"]))
print(N, "гласных,", nW, "слов,", w_has.sum(), "с ударением; голоса VC:", VC_VOICES, flush=True)

SETS = {
    "pros": ["pros"], "pros+pos": ["pros", "pos"],
    "pros+spec": ["pros", "spec", "ctc"], "pros+spec+pos": ["pros", "spec", "ctc", "pos"],
    "spec": ["spec", "ctc"], "pros+mel": ["pros", "spec"], "pros+mel+pos": ["pros", "spec", "pos"], "ctc": ["ctc"],
    "enc": ["enc"], "enc+pros": ["enc", "pros"],
}


def word_matrix(rows_w):
    """для набора слов -> [nw, maxv] индексы строк (-1 = нет)"""
    st = first[rows_w]; nv = w_nv[rows_w]; mv = nv.max()
    I = st[:, None] + np.arange(mv)[None, :]
    return np.where(np.arange(mv)[None, :] < nv[:, None], I, -1)


class Listwise:
    def __init__(self, hidden=0, wd=1e-3, steps=400, lr=0.02):
        self.h, self.wd, self.steps, self.lr = hidden, wd, steps, lr

    def net(self, d):
        if self.h:
            return torch.nn.Sequential(torch.nn.Linear(d, self.h), torch.nn.ReLU(), torch.nn.Dropout(0.1), torch.nn.Linear(self.h, 1))
        return torch.nn.Linear(d, 1)

    def fit(self, X, ws):
        torch.manual_seed(0)
        self.mu = X.mean(0); self.sd = X.std(0) + 1e-6
        M = word_matrix(ws); y = torch.tensor(w_lab[ws])
        rows = np.unique(M[M >= 0]); remap = np.full(N, -1); remap[rows] = np.arange(len(rows))
        Xt = torch.tensor((X[rows] - self.mu) / self.sd, dtype=torch.float32)
        Mi = torch.tensor(np.where(M >= 0, remap[np.maximum(M, 0)], 0)); mask = torch.tensor(M >= 0)
        self.m = self.net(X.shape[1]); opt = torch.optim.AdamW(self.m.parameters(), lr=self.lr, weight_decay=self.wd)
        for it in range(self.steps):
            s = self.m(Xt)[:, 0][Mi].masked_fill(~mask, -1e9)
            loss = torch.nn.functional.cross_entropy(s, y)
            opt.zero_grad(); loss.backward(); opt.step()
        self.m.eval(); return self

    def score(self, X, rows):
        with torch.no_grad():
            return self.m(torch.tensor((X[rows] - self.mu) / self.sd, dtype=torch.float32))[:, 0].numpy()


class GBM:
    def __init__(self, trees=300, leaves=31):
        self.trees, self.leaves = trees, leaves

    def fit(self, X, ws):
        import lightgbm as lgb
        M = word_matrix(ws); rows = M[M >= 0]  # по словам подряд
        y = np.zeros(N); y[first[ws] + w_lab[ws]] = 1
        self.m = lgb.LGBMRanker(n_estimators=self.trees, learning_rate=0.05 * 300 / self.trees, num_leaves=self.leaves, min_child_samples=50,
                                subsample=0.8, subsample_freq=1, colsample_bytree=0.5, verbose=-1, n_jobs=16)
        self.m.fit(X[rows], y[rows], group=w_nv[ws]); return self

    def score(self, X, rows):
        return self.m.predict(X[rows])


def predict(model, X, ws):
    M = word_matrix(ws); rows = M[M >= 0]
    sc = np.full(M.shape, -np.inf); sc[M >= 0] = model.score(X, rows)
    return sc.argmax(1)


def frequent_pos(train_ws):
    """самая частая позиция ударения для данного числа гласных (из обучения); при нехватке — 0"""
    best = {}
    for n in np.unique(w_nv[train_ws]):
        lab = w_lab[train_ws][w_nv[train_ws] == n]
        best[int(n)] = int(np.bincount(lab, minlength=n).argmax())
    return best


def formula(ws):
    """прототип: argmax z(длит. центр-центр) + z(энергия) + z(F0) внутри слова"""
    raw = T["raw"]; M = word_matrix(ws)
    out = []
    for r in M:
        r = r[r >= 0]; x = raw[r][:, [2, 3, 5]].copy()
        x = np.where(np.isnan(x), np.nanmean(x, 0) if np.any(~np.isnan(x)) else 0, x)
        x = np.nan_to_num(x)
        z = (x - x.mean(0)) / (x.std(0) + 1e-6)
        out.append(int(z.sum(1).argmax()))
    return np.array(out)


def evaluate(pred, ws, fp, seen_words):
    ok = pred == w_lab[ws]; nv = w_nv[ws]
    fpos = np.array([fp.get(int(n), 0) for n in nv])
    unseen = np.array([w not in seen_words for w in w_word[ws]])
    r = dict(n=int(len(ws)), all=ok.mean(), v2=ok[nv == 2].mean(), v3=ok[nv == 3].mean(), v4=ok[nv >= 4].mean(),
             nonfreq=ok[w_lab[ws] != fpos].mean(), n_nonfreq=int((w_lab[ws] != fpos).sum()),
             unseen=ok[unseen].mean() if unseen.any() else np.nan, n_unseen=int(unseen.sum()))
    return {k: (float(v) if not isinstance(v, int) else v) for k, v in r.items()}, ok


def splits():
    tids = np.array(sorted(set(T["tid"])))
    gkf = GroupKFold(n_splits=5)
    folds = [tids[te] for _, te in gkf.split(tids, groups=tids)]
    vc = w_has & (w_src == "vc")
    for k, ft in enumerate(folds):
        te = np.isin(w_tid, ft)
        yield "text", k, np.flatnonzero(w_has & ~te), np.flatnonzero(vc & te), np.flatnonzero(w_has & (w_src != "vc") & te)
    for k, v in enumerate(VC_VOICES):
        te = np.isin(w_tid, folds[k])
        yield "voice", k, np.flatnonzero(w_has & ~te & (w_voice != v)), np.flatnonzero(vc & te & (w_voice == v)), None
    for k, ft in enumerate(folds):
        te = np.isin(w_tid, ft)
        yield "refs", k, np.flatnonzero(w_has & ~te & (w_src != "vc")), np.flatnonzero(vc & te), None


MODELS = {"logreg": lambda: Listwise(0, 1e-3, 400, 0.02), "mlp": lambda: Listwise(32, 1e-3, 600, 0.01), "gbm": GBM,
          "gbm_s": lambda: GBM(150, 15)}
acc = {}


def add(split, name, part, ws, pred):
    acc.setdefault((split, name, part), []).append((ws, pred))


if __name__ == "__main__":
    Xs = {name: np.hstack([B[b] for b in bl]).astype(np.float32) for name, bl in SETS.items()}
    plan = [(s, m) for s in SETS for m in MODELS]
    if FAST:
        plan = [p for p in plan if not (p[1].startswith("gbm") and "enc" in p[0])
                and not (p[1] == "gbm_s" and p[0] not in ("pros+mel", "pros+spec"))]
    t0 = time.time()
    for split, k, tr, te, te_ref in splits():
        fp = frequent_pos(tr)
        rng = np.random.default_rng(k)
        for part, ws in [("vc", te), ("ref", te_ref)]:
            if ws is None: continue
            add(split, "случайная", part, ws, (rng.random(len(ws)) * w_nv[ws]).astype(int))
            add(split, "частая позиция", part, ws, np.array([fp.get(int(n), 0) for n in w_nv[ws]]))
            add(split, "формула (прототип)", part, ws, formula(ws))
        for sname, mname in plan:
            if split != "text" and sname not in ("pros", "pros+spec", "pros+spec+pos", "enc", "pros+pos", "pros+mel"):
                continue
            model = MODELS[mname]().fit(Xs[sname], tr)
            for part, ws in [("vc", te), ("ref", te_ref)]:
                if ws is None: continue
                add(split, f"{mname}:{sname}", part, ws, predict(model, Xs[sname], ws))
        print(split, k, f"{time.time() - t0:.0f}s", flush=True)

    # сводка: склеиваем фолды; «частая позиция» и «невиданные слова» — по обучению своего фолда
    out = {}
    fold_info = {}
    for split, k, tr, te, te_ref in splits():
        fold_info.setdefault(split, []).append((frequent_pos(tr), set(w_word[tr])))
    for (split, name, part), lst in acc.items():
        oks, wss, fps, unseen = [], [], [], []
        for (ws, pred), (fp, seen) in zip(lst, fold_info[split]):
            r, ok = evaluate(pred, ws, fp, seen); oks.append(ok); wss.append(ws)
            fps.append(np.array([fp.get(int(n), 0) for n in w_nv[ws]])); unseen.append(np.array([w not in seen for w in w_word[ws]]))
        ok = np.concatenate(oks); ws = np.concatenate(wss); fpos = np.concatenate(fps); un = np.concatenate(unseen)
        nv = w_nv[ws]; nf = w_lab[ws] != fpos
        out[f"{split}|{part}|{name}"] = dict(n=int(len(ok)), all=float(ok.mean()), v2=float(ok[nv == 2].mean()),
                                             v3=float(ok[nv == 3].mean()), v4=float(ok[nv >= 4].mean()),
                                             nonfreq=float(ok[nf].mean()), n_nonfreq=int(nf.sum()),
                                             unseen=float(ok[un].mean()) if un.any() else None, n_unseen=int(un.sum()),
                                             seen=float(ok[~un].mean()) if (~un).any() else None)
    json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
    for k in sorted(out):
        r = out[k]
        print(f"{k:45s} n={r['n']:6d} все {r['all']:.3f}  2сл {r['v2']:.3f} 3сл {r['v3']:.3f} 4+ {r['v4']:.3f}  "
              f"не-частая {r['nonfreq']:.3f} ({r['n_nonfreq']})  новые слова {r['unseen'] if r['unseen'] is None else round(r['unseen'], 3)} ({r['n_unseen']})")
