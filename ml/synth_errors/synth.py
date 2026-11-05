"""Озвучка заданий make_jobs.py (Silero v5.6) и жадная гипотеза GigaAM-v3 по кускам ≤ 220 символов (как окна приложения).
«child»: сдвиг вверх ресэмплом ×1.15 (высота и форманты, темп +15%) — грубое приближение детского голоса.
Запуск на r99950x из seed-vc/.venv:  python synth.py jobs.jsonl out_dir   -> out_dir/hyps.jsonl, out_dir/<id>.ogg"""
import glob, json, os, re, subprocess, sys, time, unicodedata
import numpy as np, torch, torchaudio, soundfile as sf

T = "/mnt/2tb/mathlock-ml"
jobs, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
dev = torch.device("cuda")
sm = torch.package.PackageImporter(f"{T}/ttscmp/venv/lib/python3.14/site-packages/silero/model/v5_6_ru.pt").load_pickle("tts_models", "model")
sm.to(dev)
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
asr = mg.GigaAMModel.from_pretrained(snap).model.eval().float().to(dev)
V = list(asr.cfg.decoding.vocabulary); BLANK = len(V)

plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))

def chunks(text, lim=220):
    sents = [s for s in re.split(r"(?<=[.!?…»])\s+(?=[—«А-ЯЁ])", text.strip()) if s]
    res = []
    for s in sents:
        if res and len((res[-1] + " " + s).replace("́", "")) <= lim: res[-1] += " " + s
        else: res.append(s)
    return res

@torch.no_grad()
def transcribe(w16):
    w = w16[None].to(dev)
    x, xl = asr.preprocessor(w, torch.tensor([w.shape[-1]]).to(dev)); enc, el = asr.encoder(x, xl)
    ids = asr.head(enc).argmax(-1)[0].tolist(); s, pr = [], None
    for i in ids:
        if i != pr and i != BLANK: s.append(V[i])
        pr = i
    return "".join(s).strip()

hf = os.path.join(out, "hyps.jsonl")
done = {json.loads(l)["id"] for l in open(hf)} if os.path.exists(hf) else set()
fo = open(hf, "a"); t0 = time.time(); n = 0
gap = torch.zeros(int(16000 * 0.3))
for line in open(jobs):
    j = json.loads(line)
    if j["id"] in done: continue
    hyps, parts = [], []
    for c in chunks(j["spoken"]):
        with torch.no_grad():
            w = sm.apply_tts(text=plus(c), speaker=j["voice"], sample_rate=24000).float().cpu()
        src_sr = int(24000 * 1.15) if j["child"] else 24000   # играем быстрее -> выше
        w16 = torchaudio.functional.resample(w, src_sr, 16000)
        hyps.append(transcribe(w16)); parts += [w16, gap]
    wav = torch.cat(parts[:-1]).numpy()
    tmp = os.path.join(out, j["id"] + ".wav"); sf.write(tmp, wav, 16000)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-c:a", "libopus", "-b:a", "24k", tmp[:-4] + ".ogg"], check=True)
    os.remove(tmp)
    fo.write(json.dumps({"id": j["id"], "hyp": " ".join(h for h in hyps if h)}, ensure_ascii=False) + "\n"); fo.flush()
    n += 1
    if n % 20 == 0: print(f"{n} за {time.time() - t0:.0f} с", flush=True)
print("done", n, f"{time.time() - t0:.0f} с", flush=True)
