"""Устойчивость к шуму: к записям подмешивается «телевизор» (смесь трёх чужих текстов озвучки) или «улица» (розовый
шум) с заданным SNR, затем жадная гипотеза GigaAM-v3 ctc (fp32, весь файл — как телефон без окон).
python noise_test.py out.jsonl <список wav/ogg> --babble <каталог ogg для смеси>
Вывод: {file, id, cond, hyp}; сверка — Java F.java по условиям (ml/live_eval/README.md)."""
import glob, json, random, subprocess, sys
from pathlib import Path
import numpy as np, torch

snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
V = list(m.cfg.decoding.vocabulary); B = len(V)

def load(f):
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(pcm, np.float32).copy()

def hyp(x):
    # длинные записи (до 4 мин) — кусками по 60 с: у энкодера предел ~200 с
    if len(x) > 16000 * 90:
        return " ".join(hyp1(x[i:i + 16000 * 60]) for i in range(0, len(x), 16000 * 60))
    return hyp1(x)

def hyp1(x):
    w = torch.from_numpy(x).unsqueeze(0).cuda()
    with torch.no_grad():
        f, fl = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); enc, el = m.encoder(f, fl)
        a = m.head(enc).argmax(-1)[0].cpu().tolist()
    s, p = [], -1
    for t in a:
        if t != p and t != B: s.append(V[t])
        p = t
    return "".join(s)

def pink(n, rng):
    w = rng.standard_normal(n); F = np.fft.rfft(w); f = np.arange(len(F)); f[0] = 1
    return np.fft.irfft(F / np.sqrt(f), n).astype(np.float32)

def mix(x, noise, snr):
    px = np.mean(x[np.abs(x) > 0.01 * np.abs(x).max()] ** 2) + 1e-9   # мощность речи (без тишины)
    pn = np.mean(noise ** 2) + 1e-12
    y = x + noise * np.sqrt(px / pn / 10 ** (snr / 10))
    return (y / max(1.0, np.abs(y).max())).astype(np.float32)

out_path, files, bab_dir = sys.argv[1], sys.argv[2:sys.argv.index("--babble")], sys.argv[sys.argv.index("--babble") + 1]
pool = sorted(glob.glob(f"{bab_dir}/*.ogg")); rng = np.random.default_rng(7); random.seed(7)
with open(out_path, "w") as out:
    for f in files:
        x = load(f); n = len(x); name = Path(f).name; tid = json.load(open(f[:-4] + ".json"))["id"] if f.endswith(".wav") else name.split(".")[0]
        others = [load(p) for p in random.sample([p for p in pool if not Path(p).name.startswith(tid + ".")], 3)]
        bab = np.zeros(n, np.float32)
        for o in others: bab += np.resize(o, n)
        conds = {"clean": x}
        for snr in (10, 5, 0):
            conds[f"tv{snr}"] = mix(x, bab, snr); conds[f"street{snr}"] = mix(x, pink(n, rng), snr)
        for c, y in conds.items():
            out.write(json.dumps({"file": name, "id": tid, "cond": c, "hyp": hyp(y)}, ensure_ascii=False) + "\n")
        out.flush(); print(name, flush=True)
