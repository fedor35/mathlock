"""vc_ru/*.wav -> CER-проверка GigaAM -> ogg/opus в out_ru3 (обрезка тишины, громкость). Плохие (CER>0.08) печатает."""
import glob, json, re, subprocess, sys
from pathlib import Path
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
norm = lambda s: re.sub(r"\s+", " ", re.sub(r"[^а-яё ]", "", s.lower().replace("́", "")).replace("ё", "е")).strip()
def cer(a, b):
    d = list(range(len(b)+1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1): p, d[j] = d[j], min(d[j]+1, d[j-1]+1, p+(ca!=cb))
    return d[-1]/max(1, len(b))
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open("../tts/texts_ru.jsonl") if l.strip()}
out = Path("out_ru3"); out.mkdir(exist_ok=True); res = {}
for f in sorted(glob.glob("vc_ru/*.wav")):
    n = Path(f).stem; tid = n.rsplit(".", 1)[0]
    c = cer(norm(m.transcribe(f)), norm(texts[tid])); res[n] = round(c, 3)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f, "-af",
        "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-18,adelay=300:all=1,apad=pad_dur=0.3",  # пауза 0,3 с: без неё срезан первый согласный
        "-c:a", "libopus", "-b:a", "32k", str(out / f"{n}.ogg")], check=True)
json.dump(res, open(out / "cer.json", "w"), indent=0)
bad = {k: v for k, v in res.items() if v > 0.08}
print("файлов", len(res), "средний CER", round(sum(res.values()) / len(res), 4), "плохие:", bad)
