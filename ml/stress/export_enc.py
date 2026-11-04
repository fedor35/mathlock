"""«Голова ударения» на энкодере GigaAM: логрегрессия по эмбеддингам гласных (лучший вариант в docs/STRESS.md).
Модель линейна, а признак — среднее эмбеддингов по кадрам гласной минус среднее по слову, поэтому внутри слова
оценка гласной = среднее по её кадрам от s_t = v·enc_t (+ общая для слова константа, которая сокращается в softmax).
Значит, телефону нужен один лишний скаляр на кадр: v (768 чисел) можно добавить в ONNX как 35-й выход головы.
  python export_enc.py feats.pkl texts.jsonl out_dir audio1 [audio2 ...]
Пишет out_dir/stress_enc_head.json ({"v": [...768]}) и для фикстуры out_dir/stress_fixture/<имя>.s.f32 —
s_t по кадрам (PyTorch fp32) и <имя>.enc.json — ответы Python."""
import json, sys
from pathlib import Path
import numpy as np, torch
from train import Listwise, w_has, B, T, predict, word_matrix

feats_pkl, texts_f, out = sys.argv[1:4]
out = Path(out); (out / "stress_fixture").mkdir(parents=True, exist_ok=True)
X = B["enc"].astype(np.float32); ws = np.flatnonzero(w_has)
model = Listwise(0, 1e-3, 400, 0.02).fit(X, ws)
w = model.m.weight.detach().numpy()[0].astype(np.float64)
v = w / model.sd
json.dump(dict(version=1, v=[float(f"{x:.7g}") for x in v]), open(out / "stress_enc_head.json", "w"), separators=(",", ":"))
# проверка эквивалентности на обучающих словах: argmax по среднему s_t == predict логрегрессии
s_vowel = T["enc"].astype(np.float64) @ v  # среднее по кадрам от v·enc = v·(среднее enc)
sub = ws[:5000]
M = word_matrix(sub)
sc = np.where(M >= 0, s_vowel[np.maximum(M, 0)], -np.inf)
print("совпадение с логрегрессией:", float((sc.argmax(1) == predict(model, X, sub)).mean()))

import extract
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open(texts_f) if l.strip()}
for f in sys.argv[4:]:
    name = Path(f).name.rsplit(".", 1)[0]; tid = name.split(".")[0]
    pcm = extract.load(f); mel, enc, lp = extract.run(pcm)
    s = (enc.astype(np.float64) @ v).astype(np.float32)
    s.astype("<f4").tofile(out / "stress_fixture" / f"{name}.s.f32")
    rec = extract.process(f, texts[tid])
    words, rows, i = [], rec["rows"], 0
    while i < len(rows):
        nv = rows[i]["nv"]
        sv = np.array([s[a:b].astype(np.float64).mean() for a, b in (rows[i + j]["seg"] for j in range(nv))])
        p = np.exp(sv - sv.max()); p /= p.sum()
        words.append(dict(letters=rows[i]["w"], expected=int(rows[i]["label"]), predicted=int(p.argmax()),
                          prob=[float(f"{x:.6g}") for x in p]))
        i += nv
    json.dump(dict(T=int(len(s)), words=words), open(out / "stress_fixture" / f"{name}.enc.json", "w"), ensure_ascii=False)
    print(name, f"слов {len(words)}, верно {sum(w['predicted'] == w['expected'] for w in words)}")
