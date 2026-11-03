"""CER GigaAM для всех RU-образцов в out/ (+ лучшие кандидаты OmniVoice из корпуса)."""
import glob, json, re, sys, shutil
from pathlib import Path
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
norm = lambda s: re.sub(r"\s+", " ", re.sub(r"[^а-яё ]", "", s.lower().replace("+","").replace("́","")).replace("ё","е")).strip()
def cer(a, b):
    d = list(range(len(b)+1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1): p, d[j] = d[j], min(d[j]+1, d[j-1]+1, p+(ca!=cb))
    return d[-1]/max(1,len(b))
RU = {"L1": "ru-L1-001", "L3": "ru-L3-005", "L5": "ru-L5-003"}
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in open("../tts/texts_ru.jsonl")}
# OmniVoice: лучший из 3 кандидатов по CER, как в конвейере
for tid, cid in RU.items():
    for v in ["skazochnik", "devochka", "malchik", "velikan", "feya"]:
        c = sorted(glob.glob(f"../tts/cand_ru/{cid}.{v}.*.wav") + glob.glob(f"omni_l5/{cid}.{v}.*.wav"))
        if not c: continue
        best = min(c, key=lambda f: cer(norm(m.transcribe(f)), norm(texts[cid])))
        shutil.copy(best, f"out/{tid}.omnivoice-{v}.wav")
res = {}
for f in sorted(glob.glob("out/L*.wav")):
    tid = Path(f).name.split(".")[0]; ref = norm(texts[RU[tid]]); hyp = m.transcribe(f)
    res[Path(f).name] = {"cer": round(cer(norm(hyp), ref), 3), "hyp": hyp}
    print(f"{Path(f).name:40s} {res[Path(f).name]['cer']:.3f}  {hyp}", flush=True)
json.dump(res, open("out/cer_ru.json", "w"), ensure_ascii=False, indent=1)
