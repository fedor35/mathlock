"""Признаки гласных для всего корпуса озвучки. Запуск на r99950x в gigaam-venv:
  python extract.py texts_ru4.jsonl out.pkl  dir1 [dir2 ...]
Каталоги: out_ru3/out_ru4 (<id>.<голос>.ogg, Seed-VC) и refs_ru (<id>/<движок-голос>.wav).
Пишет список файлов: {file, tid, voice, src, words, rows(без enc), enc[n_vowels][768] float16}."""
import glob, json, pickle, subprocess, sys, time
from pathlib import Path
import numpy as np, torch
sys.path.insert(0, str(Path(__file__).parent))
from common import VOCAB, parse_words, forced_align, f0_track, vowel_features

snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
dev = "cuda" if torch.cuda.is_available() else "cpu"
m = mg.GigaAMModel.from_pretrained(snap).model.eval().to(dev)
assert list(m.cfg.decoding.vocabulary) == VOCAB


def load(f):
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(pcm, np.float32).copy()


def run(pcm):
    w = torch.from_numpy(pcm).unsqueeze(0).to(dev)
    with torch.no_grad():
        x, xl = m.preprocessor(w, torch.tensor([w.shape[-1]]).to(dev))
        enc, el = m.encoder(x, xl)
        lp = m.head(enc).log_softmax(-1)
    return x[0].float().cpu().numpy(), enc[0].float().cpu().numpy().T, lp[0].float().cpu().numpy()


def process(f, text, keep=False):
    pcm = load(f)
    mel, enc, lp = run(pcm)
    words = parse_words(text)
    tgt = " ".join(w for w, _, _ in words)
    toks = [VOCAB.index(c) for c in tgt]
    al = forced_align(lp, toks)
    if al is None:
        return None
    start, end = al
    rows = vowel_features(words, start, end, lp.shape[0], mel, f0_track(pcm, mel.shape[1]), lp)
    E = np.stack([enc[a:b].mean(0) for a, b in (r["seg"] for r in rows)]).astype(np.float16) if rows else None
    r = dict(words=words, rows=rows, enc=E, dur=len(pcm) / 16000)
    if keep:
        r.update(pcm=pcm, lp=lp, mel=mel)
    return r


if __name__ == "__main__":
    texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open(sys.argv[1]) if l.strip()}
    files = []
    for d in sys.argv[3:]:
        for f in sorted(glob.glob(f"{d}/*.ogg")):
            tid, voice = Path(f).name.split(".")[:2]
            files.append((f, tid, voice, "vc"))
        for f in sorted(glob.glob(f"{d}/*/*.wav")):
            tid, voice = Path(f).parent.name, Path(f).stem
            if tid in texts:
                files.append((f, tid, voice, voice.split("-")[0]))
    print(len(files), "файлов", flush=True)
    out, bad, t0 = [], 0, time.time()
    for n, (f, tid, voice, src) in enumerate(files):
        r = process(f, texts[tid])
        if r is None:
            bad += 1; print("не выровнялось", f, flush=True); continue
        r.update(file=f, tid=tid, voice=voice, src=src)
        out.append(r)
        if n % 100 == 0:
            print(n, f"{time.time() - t0:.0f}s", flush=True)
    pickle.dump(out, open(sys.argv[2], "wb"))
    print("готово:", len(out), "не выровнялось:", bad)
