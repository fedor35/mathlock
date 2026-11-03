"""ASR-проверка кандидатов: CER против текста + медиана F0. Аргументы: jsonl dir"""
import glob, json, re, sys
from pathlib import Path
import numpy as np, torch, torchaudio
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
res = {}
for line in Path(sys.argv[1]).read_text().splitlines():
    r = json.loads(line); ref = norm(r["text"])
    for f in sorted(glob.glob(f"{sys.argv[2]}/{r['id']}.*.wav")):
        hyp = m.transcribe(f); w, sr = torchaudio.load(f)
        f0 = torchaudio.functional.detect_pitch_frequency(w, sr, freq_low=60, freq_high=600)[0].numpy()
        c = cer(norm(hyp), ref); res[Path(f).name] = c
        print(f"{Path(f).name:22s} CER {c:.3f} F0 {np.median(f0):5.0f}  {hyp}")
json.dump(res, open(f"{sys.argv[2]}/scores.json","w"), indent=1)
