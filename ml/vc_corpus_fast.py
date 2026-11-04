"""Корпус v4 (быстрый): Silero v5.6 (ударения корпуса через +) -> Seed-VC -> CER GigaAM -> ogg/opus. Всё в одном процессе.
Отличия от v3 (vc_corpus.py + finish_vc.py + vc_retry.py): модели грузятся один раз; текст режется по предложениям
на куски <= --chunk символов (Silero и окно Seed-VC ~24 с), куски склеиваются с паузой 250 мс; CER считается по каждому
куску, кусок с CER > --thr перегенерируется с другим seed (до --tries попыток, берётся лучший); итоговый CER — по всему файлу.
Запуск из каталога seed-vc (без -I, inference.py берётся из cwd):
  .venv/bin/python ../ttscmp/vc_corpus_fast.py --texts ../tts/texts_ru4.jsonl --levels 6-9 --out ../ttscmp/out_ru4 --steps 15
  --ids ru-L6-001,ru-L9-001 --wav-only --out ../ttscmp/out_ru4/probe   — пробы в wav.
Время слов потом: .venv/bin/python ../ttscmp/word_times.py ../tts/texts_ru4.jsonl ../ttscmp/out_ru4"""
import argparse, glob, json, os, re, subprocess, sys, time, unicodedata
import numpy as np, torch, torchaudio, soundfile as sf
sys.path.insert(0, os.getcwd())
import inference as I

p = argparse.ArgumentParser()
p.add_argument("--texts", required=True); p.add_argument("--out", required=True)
p.add_argument("--levels", default="6-9"); p.add_argument("--ids", default="")
p.add_argument("--voices", default="skazochnik,devochka")
p.add_argument("--steps", type=int, default=15); p.add_argument("--cfg", type=float, default=0.7)
p.add_argument("--length-adjust", type=float, default=1.0)
p.add_argument("--chunk", type=int, default=220, help="макс. символов в куске (без акутов)")
p.add_argument("--pause", type=float, default=0.25)
p.add_argument("--thr", type=float, default=0.05); p.add_argument("--tries", type=int, default=4)
p.add_argument("--wav-only", action="store_true"); p.add_argument("--force", action="store_true")
A = p.parse_args()

T = "/mnt/2tb/mathlock-ml"
SRC = {"skazochnik": "aidar", "velikan": "aidar", "devochka": "baya", "feya": "baya", "malchik": "baya"}
dev = torch.device("cuda")
lo, hi = map(int, A.levels.split("-"))
texts = [json.loads(l) for l in open(A.texts) if l.strip()]
ids = set(filter(None, A.ids.split(",")))
texts = [t for t in texts if t["id"] in ids] if ids else [t for t in texts if lo <= t.get("level", 0) <= hi]
voices = A.voices.split(",")
os.makedirs(A.out, exist_ok=True)

# --- модели: Silero (torch.package из пакета silero в ttscmp/venv), Seed-VC, GigaAM ---
t0 = time.time()
sm = torch.package.PackageImporter(f"{T}/ttscmp/venv/lib/python3.14/site-packages/silero/model/v5_6_ru.pt").load_pickle("tts_models", "model")
sm.to(dev)
vc_args = argparse.Namespace(f0_condition=False, checkpoint=None, config=None, fp16=False)
model, semantic_fn, _, vocoder_fn, campplus, mel_fn, mel_args = I.load_models(vc_args)
SR = mel_args["sampling_rate"]; HOP = 256; WIN = SR // HOP * 30; OVL = 16
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
asr = mg.GigaAMModel.from_pretrained(snap).model.eval().float().to(dev)  # transformers 4.46 грузит fp16
V = list(asr.cfg.decoding.vocabulary); BLANK = len(V)
print(f"модели загружены за {time.time()-t0:.1f} с", flush=True)

# --- голоса: признаки эталона считаются один раз ---
REF = {}
@torch.no_grad()
def ref(ch):
    if ch not in REF:
        a = torch.tensor(I.librosa.load(f"{T}/tts/voices/{ch}.wav", sr=SR)[0][:SR * 25])[None].float().to(dev)
        w16 = torchaudio.functional.resample(a, SR, 16000)
        mel2 = mel_fn(a)
        f = torchaudio.compliance.kaldi.fbank(w16, num_mel_bins=80, dither=0, sample_frequency=16000)
        style = campplus((f - f.mean(0, keepdim=True))[None])
        prompt = model.length_regulator(semantic_fn(w16), ylens=torch.LongTensor([mel2.size(2)]).to(dev), n_quantizers=3, f0=None)[0]
        REF[ch] = (mel2, style, prompt)
    return REF[ch]

@torch.no_grad()
def vc(wave24, ch, seed):
    """Как inference.main (f0_condition=False), но без загрузки файлов и с фиксированным seed."""
    mel2, style, prompt = ref(ch)
    src = torchaudio.functional.resample(wave24[None].to(dev), 24000, SR)
    S = semantic_fn(torchaudio.functional.resample(src, SR, 16000))  # куски < 30 с
    mel = mel_fn(src)
    cond = model.length_regulator(S, ylens=torch.LongTensor([int(mel.size(2) * A.length_adjust)]).to(dev), n_quantizers=3, f0=None)[0]
    torch.manual_seed(seed)
    win = WIN - mel2.size(2); done, outs, prev = 0, [], None
    while done < cond.size(1):
        cc = torch.cat([prompt, cond[:, done:done + win]], 1); last = done + win >= cond.size(1)
        tgt = model.cfm.inference(cc, torch.LongTensor([cc.size(1)]).to(dev), mel2, style, None, A.steps, inference_cfg_rate=A.cfg)[:, :, mel2.size(-1):]
        w = vocoder_fn(tgt.float()).squeeze().cpu().numpy(); ow = OVL * HOP
        if last: outs.append(w if prev is None else I.crossfade(prev, w, ow)); break
        outs.append(w[:-ow] if prev is None else I.crossfade(prev, w[:-ow], ow)); prev = w[-ow:]; done += tgt.size(2) - OVL
    return np.concatenate(outs)

norm = lambda s: re.sub(r"\s+", " ", re.sub(r"[^а-яё ]", "", s.lower().replace("́", "")).replace("ё", "е")).strip()
def cer(a, b):
    d = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        q, d[0] = d[0], i
        for j, cb in enumerate(b, 1): q, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, q + (ca != cb))
    return d[-1] / max(1, len(b))

@torch.no_grad()
def transcribe(wave, sr):
    w = torchaudio.functional.resample(torch.from_numpy(wave).float()[None].to(dev), sr, 16000)
    x, xl = asr.preprocessor(w, torch.tensor([w.shape[-1]]).to(dev)); enc, el = asr.encoder(x, xl)
    ids = asr.head(enc).argmax(-1)[0].tolist(); out, pr = [], None
    for i in ids:
        if i != pr and i != BLANK: out.append(V[i])
        pr = i
    return "".join(out)

plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))
def chunks(text):
    sents = [s for s in re.split(r"(?<=[.!?…»])\s+(?=[—«А-ЯЁ])", text.strip()) if s]
    out = []
    for s in sents:
        if out and len((out[-1] + " " + s).replace("́", "")) <= A.chunk: out[-1] += " " + s
        else: out.append(s)
    return out

st = {"silero": 0.0, "vc": 0.0, "asr": 0.0, "ogg": 0.0}; audio_s = 0.0; first, final, full = {}, {}, {}
nretry_chunks = 0; retried_files = set()
cerf = f"{A.out}/cer.json"; res = json.load(open(cerf)) if os.path.exists(cerf) else {}
gap = np.zeros(int(SR * A.pause), np.float32)
T0 = time.time()
for t in texts:
    for ch in voices:
        name = f"{t['id']}.{ch}"; dst = f"{A.out}/{name}." + ("wav" if A.wav_only else "ogg")
        if os.path.exists(dst) and not A.force: continue
        parts, f_first, f_best = [], [], []
        for ci, c in enumerate(chunks(t["text"])):
            a = time.time(); w24 = sm.apply_tts(text=plus(c), speaker=SRC[ch], sample_rate=24000).float(); torch.cuda.synchronize(); st["silero"] += time.time() - a
            best = None
            for k in range(A.tries):
                a = time.time(); w = vc(w24, ch, seed=1000 * k + ci); torch.cuda.synchronize(); st["vc"] += time.time() - a
                a = time.time(); e = cer(norm(transcribe(w, SR)), norm(c)); st["asr"] += time.time() - a
                if k == 0: f_first.append((e, len(norm(c))))
                if best is None or e < best[0]: best = (e, w)
                if e <= A.thr: break
                nretry_chunks += 1; retried_files.add(name)
                print(f"  {name} кусок {ci}: CER {e:.3f} на seed {1000*k+ci}, ещё попытка", flush=True)
            parts += [best[1], gap]; f_best.append((best[0], len(norm(c))))
        wav = np.concatenate(parts[:-1]); audio_s += len(wav) / SR
        # итоговый CER = по кускам (то же аудио); GigaAM на целом файле > 30 с заметно хуже распознаёт, его CER только для справки
        a = time.time(); full[name] = cer(norm(transcribe(wav, SR)), norm(t["text"])); st["asr"] += time.time() - a
        wavg = lambda L: sum(x * n for x, n in L) / max(1, sum(n for _, n in L))
        first[name] = wavg(f_first); e = final[name] = res[name] = round(wavg(f_best), 4)
        a = time.time()
        if A.wav_only: sf.write(dst, wav, SR)
        else:
            tmp = f"{A.out}/{name}.tmp.wav"; sf.write(tmp, wav, SR)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-af",
                "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-18,adelay=300:all=1,apad=pad_dur=0.3",  # пауза 0,3 с: без неё срезан первый согласный
                "-c:a", "libopus", "-b:a", "32k", dst], check=True); os.remove(tmp)
        st["ogg"] += time.time() - a
        json.dump(res, open(cerf, "w"), indent=0)
        print(f"{name} {len(wav)/SR:.1f} с, CER {e:.3f} (1-я попытка {first[name]:.3f}, целиком {full[name]:.3f}), прошло {time.time()-T0:.0f} с", flush=True)

tot = time.time() - T0
if final:
    print(f"\nшаги {A.steps}, cfg {A.cfg}, файлов {len(final)}, аудио {audio_s:.1f} с, время {tot:.1f} с, RTF {tot/audio_s:.3f}")
    for k, v in st.items(): print(f"  {k}: {v:.1f} с, RTF {v/audio_s:.4f}")
    print(f"CER 1-я попытка (по кускам): средний {np.mean(list(first.values())):.4f}, макс {max(first.values()):.4f}")
    print(f"CER целым файлом (справочно): средний {np.mean(list(full.values())):.4f}, макс {max(full.values()):.4f}")
    print(f"CER итог (по кускам): средний {np.mean(list(final.values())):.4f}, макс {max(final.values()):.4f}")
    print(f"перегенерировано кусков: {nretry_chunks}, файлов затронуто: {len(retried_files)}; > {A.thr}: {[k for k, v in final.items() if v > A.thr]}")
print("done", flush=True)
