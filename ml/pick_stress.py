"""Отбор озвучки корпуса: CER (GigaAM) + ударения относительно эталонов (Silero/Vosk/RHVoice).
Аргументы: texts.jsonl jobs.jsonl cand_dir refs_dir out_dir
Правило: из кандидатов с CER <= min(CER)+0.02 берётся ближайший к эталонам по профилю слогов
(длительность, энергия, F0 гласных внутри слова). Пишет out_dir/<id>.ogg и out_dir/picks.json."""
import glob, json, re, subprocess, sys
from pathlib import Path
import numpy as np, torch, torchaudio
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
V = list(m.cfg.decoding.vocabulary); B = len(V)
VOW = set("аеиоуыэюя")
WORD = re.compile(r"[а-яёА-ЯЁ][а-яёА-ЯЁ́]*")
W = np.array([1.0, 0.6, 0.8])[:, None]
norm = lambda s: re.sub(r"\s+", " ", re.sub(r"[^а-яё ]", "", s.lower().replace("́", "")).replace("ё", "е")).strip()

def cer(a, b):
    d = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1): p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (ca != cb))
    return d[-1] / max(1, len(b))

def f0(x, sr=16000):
    if len(x) < 400: return np.nan
    x = x - x.mean(); ac = np.correlate(x, x, "full")[len(x) - 1:]
    lo, hi = sr // 500, sr // 60
    if hi >= len(ac): return np.nan
    k = lo + np.argmax(ac[lo:hi]); return sr / k if ac[k] > 0.3 * ac[0] else np.nan

def analyze(f, words):
    """-> (гипотеза ASR, профиль по словам или None)"""
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    a = np.frombuffer(pcm, np.float32).copy(); w = torch.from_numpy(a).unsqueeze(0).cuda()
    with torch.no_grad():
        x, xl = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); enc, el = m.encoder(x, xl)
        lp = m.head(enc).log_softmax(-1).float().cpu()
    ids = lp[0].argmax(-1).tolist(); hyp, prev = [], B
    for t in ids:
        if t != prev and t != B: hyp.append(V[t])
        prev = t
    hyp = "".join(hyp)
    try:
        ali, sc = torchaudio.functional.forced_align(lp, torch.tensor([[V.index(c) for c in " ".join(words)]]), blank=B)
    except Exception:
        return hyp, None
    spans = torchaudio.functional.merge_tokens(ali[0], sc[0].exp(), blank=B)
    prof, k = [], 0
    for wd in words:
        sp = spans[k:k + len(wd)]; k += len(wd) + 1
        vs = [s for c, s in zip(wd, sp) if c in VOW]
        if len(vs) < 2: prof.append(None); continue
        cen = [(s.start + s.end + 1) / 2 * 0.04 for s in vs]; wend = (sp[-1].end + 1) * 0.04 + 0.04
        dur = np.maximum(np.diff(cen + [wend]), 0.02)
        en, fz = [], []
        for s in vs:
            seg = a[max(0, int(s.start * 640) - 320): int((s.end + 1) * 640) + 320]
            en.append(np.log(np.mean(seg ** 2) + 1e-9)); fz.append(f0(seg))
        fz = np.array(fz); fz = np.where(np.isnan(fz), np.nanmean(fz) if np.any(~np.isnan(fz)) else 100, fz)
        z = lambda v: (np.array(v) - np.mean(v)) / (np.std(v) + 1e-6)
        prof.append(np.stack([z(np.log(dur)), z(en), z(np.log(fz + 1))]))
    return hyp, prof

def dist(p, mean):
    ds = [float(np.mean(W * (p[i] - mean[i]) ** 2)) for i in range(len(mean)) if p[i] is not None and mean[i] is not None]
    return float(np.mean(ds)) if ds else 0.0

texts_f, jobs_f, cand, refs, out = sys.argv[1:6]
out = Path(out); out.mkdir(parents=True, exist_ok=True)
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open(texts_f) if l.strip()}
picks = json.load(open(out / "picks.json")) if (out / "picks.json").exists() else {}
refmean = {}
for line in open(jobs_f):
    j = json.loads(line); jid = j["id"]; tid = jid.rsplit(".", 1)[0]
    if jid in picks and (out / f"{jid}.ogg").exists(): continue
    words = [w.lower().replace("́", "").replace("ё", "е") for w in WORD.findall(texts[tid])]
    if tid not in refmean:
        ps = [analyze(f, words)[1] for f in sorted(glob.glob(f"{refs}/{tid}/*.wav"))]
        ps = [p for p in ps if p is not None]
        refmean[tid] = [np.mean([p[i] for p in ps if p[i] is not None], 0) if any(p[i] is not None for p in ps) else None for i in range(len(words))] if ps else None
    ref = norm(texts[tid]); rows = []
    for f in sorted(glob.glob(f"{cand}/{jid}.*.wav")):
        hyp, p = analyze(f, words)
        c = cer(norm(hyp), ref)
        d = dist(p, refmean[tid]) if (p is not None and refmean[tid] is not None) else 9.0
        rows.append((c, d, f))
    if not rows: print("нет кандидатов", jid); continue
    cmin = min(r[0] for r in rows)
    ok = [r for r in rows if r[0] <= cmin + 0.02]
    c, d, f = min(ok, key=lambda r: r[1])
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f, "-af",
                    "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-18",
                    "-c:a", "libopus", "-b:a", "32k", str(out / f"{jid}.ogg")], check=True)
    picks[jid] = {"file": Path(f).name, "cer": round(c, 3), "d": round(d, 3),
                  "all": [[Path(r[2]).name, round(r[0], 3), round(r[1], 3)] for r in rows]}
    print(jid, Path(f).name, f"CER {c:.3f} d {d:.3f}", flush=True)
    json.dump(picks, open(out / "picks.json", "w"), ensure_ascii=False, indent=0)
bad = {k: v["cer"] for k, v in picks.items() if v["cer"] > 0.08}
print("плохие (CER>0.08):", bad); print("done")
