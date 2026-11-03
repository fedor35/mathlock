"""Отбор кандидатов TTS по ударениям: сравнение с эталонами, где ударение верное (Silero/RHVoice/Vosk).
По CTC-выравниванию GigaAM для каждой гласной: длительность слога (центр гласной -> центр следующей),
энергия и F0 в гласной; внутри слова z-нормировка. Расстояние кандидата до среднего профиля эталонов.
Аргументы: text refs_glob cands_glob"""
import glob, re, subprocess, sys, json
from pathlib import Path
import numpy as np, torch, torchaudio
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
V = list(m.cfg.decoding.vocabulary); B = len(V)
VOW = set("аеиоуыэюяё")
text = sys.argv[1]
WORD = re.compile(r"[а-яёА-ЯЁ][а-яёА-ЯЁ́]*")
raw = WORD.findall(text)
words = [w.lower().replace("́", "").replace("ё", "е") for w in raw]
def stress_idx(w):  # индекс ударной гласной среди гласных слова (по акуту/ё), иначе None
    w = w.lower(); vi = -1
    for i, c in enumerate(w):
        if c in VOW or c == "ё": vi += 1
        if c == "ё" or (i + 1 < len(w) and w[i + 1] == "́"): return vi
    return None
truth = [stress_idx(w) for w in raw]
def f0(x, sr=16000):
    if len(x) < 400: return np.nan
    x = x - x.mean(); ac = np.correlate(x, x, "full")[len(x) - 1:]
    lo, hi = sr // 500, sr // 60
    if hi >= len(ac): return np.nan
    k = lo + np.argmax(ac[lo:hi]); return sr / k if ac[k] > 0.3 * ac[0] else np.nan
def profile(f):
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    a = np.frombuffer(pcm, np.float32).copy(); w = torch.from_numpy(a).unsqueeze(0).cuda()
    with torch.no_grad():
        x, xl = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); enc, el = m.encoder(x, xl)
        lp = m.head(enc).log_softmax(-1).float().cpu()
    tgt = " ".join(words)
    ali, sc = torchaudio.functional.forced_align(lp, torch.tensor([[V.index(c) for c in tgt]]), blank=B)
    spans = torchaudio.functional.merge_tokens(ali[0], sc[0].exp(), blank=B)
    prof, k = [], 0
    for wd in words:
        sp = spans[k:k + len(wd)]; k += len(wd) + 1
        vs = [s for c, s in zip(wd, sp) if c in VOW]
        if len(vs) < 2: prof.append(None); continue
        cen = [(s.start + s.end + 1) / 2 * 0.04 for s in vs]; wend = (sp[-1].end + 1) * 0.04 + 0.04
        dur = np.diff(cen + [wend])
        en, fz = [], []
        for s in vs:
            seg = a[int(s.start * 640) - 320: int((s.end + 1) * 640) + 320]
            en.append(np.log(np.mean(seg ** 2) + 1e-9)); fz.append(f0(seg))
        fz = np.array(fz); fz = np.where(np.isnan(fz), np.nanmean(fz) if np.any(~np.isnan(fz)) else 0, fz)
        z = lambda v: (np.array(v) - np.mean(v)) / (np.std(v) + 1e-6)
        prof.append(np.stack([z(np.log(dur)), z(en), z(np.log(fz + 1))]))
    return prof
refs = {Path(f).stem: profile(f) for f in sorted(glob.glob(sys.argv[2]))}
mean = []
for i in range(len(words)):
    ps = [p[i] for p in refs.values() if p[i] is not None]
    mean.append(np.mean(ps, 0) if ps else None)
W = np.array([1.0, 0.6, 0.8])[:, None]  # веса: длительность, энергия, F0
def score(p):
    ds = [float(np.mean(W * (p[i] - mean[i]) ** 2)) for i in range(len(words)) if p[i] is not None]
    worst = sorted(range(len(words)), key=lambda i: -(np.mean(W * (p[i] - mean[i]) ** 2) if p[i] is not None else 0))[:3]
    return np.mean(ds), [words[i] for i in worst]
def acc(p):  # абсолютная проверка: argmax(взвешенная сумма) против акута
    ok = n = 0
    for i, t in enumerate(truth):
        if p[i] is None or t is None: continue
        n += 1; ok += int(np.argmax((W * p[i]).sum(0)) == t)
    return ok / max(n, 1)
print("эталоны (леave-one-out не делаем):")
for k, p in refs.items(): print(f"  {k:24s} d={score(p)[0]:.3f} acc={acc(p):.2f}")
res = []
for f in sorted(glob.glob(sys.argv[3])):
    p = profile(f); d, worst = score(p); res.append((d, Path(f).stem, acc(p), worst))
for d, n, a_, wst in sorted(res): print(f"{n:28s} d={d:.3f} acc={a_:.2f} хуже всего: {' '.join(wst)}")
