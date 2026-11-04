"""Финальная модель для телефона + фикстура для JUnit.
  python export.py feats.pkl texts.jsonl <набор> <модель> out_dir audio1 [audio2 ...]
набор: pros | pros+mel | pros+spec | ... (без enc — на телефоне энкодер не отдаётся);
модель: 0 — логрегрессия, N — MLP со скрытым слоем N, gbm / gbm_s — LightGBM lambdarank (300×31 / 150×15).
Пишет out_dir/stress_model.json (веса) и out_dir/stress_fixture/<имя>.{pcm.f32,lp.f32,json}:
PCM 16 кГц, лог-вероятности CTC [T][34] (PyTorch fp32), признаки, оценки и ответы Python."""
import json, sys
from pathlib import Path
import numpy as np
from feats import load, blocks
from train import Listwise, GBM, SETS, first, w_has, B, GSD  # обучающие данные train.py (без оценки)

feats_pkl, texts_f, sname, kind, out = sys.argv[1:6]
out = Path(out); (out / "stress_fixture").mkdir(parents=True, exist_ok=True)
bl = SETS[sname]; assert "enc" not in bl
X = np.hstack([B[b] for b in bl]).astype(np.float32)
ws = np.flatnonzero(w_has)
r = lambda a: [float(f"{x:.6g}") for x in np.ravel(a)]

if kind.startswith("gbm"):
    model = (GBM() if kind == "gbm" else GBM(150, 15)).fit(X, ws)
    feat, thr, left, right, leaf, noff, loff = [], [], [], [], [], [], []

    def walk(nd):
        """-> ссылка: >=0 внутренний узел (номер в дереве), <0 — лист -(номер+1)"""
        if "leaf_value" in nd:
            leaf.append(nd["leaf_value"]); return -(len(leaf) - loff[-1])
        assert nd["decision_type"] == "<="
        i = len(feat) - noff[-1]
        feat.append(nd["split_feature"]); thr.append(nd["threshold"]); left.append(0); right.append(0)
        a = walk(nd["left_child"]); b = walk(nd["right_child"])
        left[noff[-1] + i] = a; right[noff[-1] + i] = b
        return i

    for t in model.m.booster_.dump_model()["tree_info"]:
        noff.append(len(feat)); loff.append(len(leaf))
        walk(t["tree_structure"])
    W = dict(version=1, blocks=bl, hidden=-1, gsd=r(GSD), mu=[], sd=[], w2=[], b2=0.0,
             gbm_feat=feat, gbm_thr=thr, gbm_left=left, gbm_right=right, gbm_leaf=leaf, gbm_noff=noff, gbm_loff=loff)
    print("деревьев", len(noff), "узлов", len(feat), "листьев", len(leaf))

    def score(x):
        """обход деревьев; признак сначала приводится к float32 — как при обучении и в Java"""
        s = 0.0; xf = np.asarray(x, np.float32).astype(np.float64)
        for k in range(len(noff)):
            i = 0
            while True:
                j = left[noff[k] + i] if xf[feat[noff[k] + i]] <= thr[noff[k] + i] else right[noff[k] + i]
                if j < 0:
                    s += leaf[loff[k] - j - 1]; break
                i = j
        return s

    rows = np.concatenate([first[c] + np.arange(2) for c in ws[:300]])
    print("обход деревьев vs LightGBM: max |d| =", float(np.abs(model.m.predict(X[rows]) - [score(x) for x in X[rows]]).max()))
else:
    hidden = int(kind)
    model = Listwise(hidden, 1e-3, 600 if hidden else 400, 0.01 if hidden else 0.02).fit(X, ws)
    net = model.m
    if hidden:
        w1, b1 = net[0].weight.detach().numpy(), net[0].bias.detach().numpy()
        w2, b2 = net[3].weight.detach().numpy()[0], float(net[3].bias.detach().numpy()[0])
    else:
        w2, b2 = net.weight.detach().numpy()[0], float(net.bias.detach().numpy()[0])
    W = dict(version=1, blocks=bl, hidden=hidden, gsd=r(GSD), mu=r(model.mu), sd=r(model.sd), w2=r(w2), b2=b2)
    if hidden:
        W.update(w1=r(w1), b1=r(b1))

    def score(x):
        """модель из округлённых весов — именно её повторяет Java"""
        z = (x - np.array(W["mu"])) / np.array(W["sd"])
        if hidden:
            h = np.maximum(np.array(W["w1"]).reshape(hidden, -1) @ z + np.array(W["b1"]), 0)
            return float(np.array(W["w2"]) @ h + b2)
        return float(np.array(W["w2"]) @ z + b2)

json.dump(W, open(out / "stress_model.json", "w"), ensure_ascii=False, separators=(",", ":"))

import extract  # GigaAM
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open(texts_f) if l.strip()}
for f in sys.argv[6:]:
    name = Path(f).name.rsplit(".", 1)[0]; tid = name.split(".")[0]
    rec = extract.process(f, texts[tid], keep=True)
    rec.update(file=f, tid=tid, voice=name.split(".")[1], src="vc")
    T1 = load([rec]); B1, _ = blocks(T1, np.array(W["gsd"]))
    X1 = np.hstack([B1[b] for b in bl])
    sc = [score(x) for x in X1]
    words, i = [], 0
    while i < len(X1):
        nv = int(T1["nv"][i]); s = np.array(sc[i:i + nv]); p = np.exp(s - s.max()); p /= p.sum()
        words.append(dict(word=int(T1["word"][i] % 100000), letters=str(T1["w"][i]), expected=int(T1["label"][i]),
                          predicted=int(p.argmax()), prob=r(p)))
        i += nv
    rec["pcm"].astype("<f4").tofile(out / "stress_fixture" / f"{name}.pcm.f32")
    rec["lp"].astype("<f4").tofile(out / "stress_fixture" / f"{name}.lp.f32")
    json.dump(dict(text=texts[tid], samples=len(rec["pcm"]), T=int(rec["lp"].shape[0]),
                   segs=[list(x["seg"]) for x in rec["rows"]], features=[r(x) for x in X1], scores=r(sc), words=words),
              open(out / "stress_fixture" / f"{name}.json", "w"), ensure_ascii=False)
    print(name, f"слов {len(words)}, верно {sum(w['predicted'] == w['expected'] for w in words)}")
