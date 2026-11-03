import numpy as np, subprocess
import glob, json, re
from pathlib import Path
from faster_whisper import WhisperModel
m = WhisperModel("large-v3", device="cuda", compute_type="float16")
KK = {"K1": "Менің атым Арман. Мен мектепте оқимын.", "K2": "Бүгін ауа райы жақсы. Біз әжеммен бірге саябаққа барамыз. Онда үлкен көл бар, көлде аққулар жүзіп жүр."}
norm = lambda s: re.sub(r"\s+", " ", re.sub(r"[^\w ]", "", s.lower())).strip()
def cer(a, b):
    d = list(range(len(b)+1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1): p, d[j] = d[j], min(d[j]+1, d[j-1]+1, p+(ca!=cb))
    return d[-1]/max(1,len(b))
res = {}
for f in sorted(glob.glob("out_kk/K*.wav")):
    x = np.frombuffer(subprocess.run(["ffmpeg","-v","quiet","-i",f,"-ac","1","-ar","16000","-f","f32le","-"],capture_output=True).stdout, np.float32); seg, _ = m.transcribe(x, language="kk", beam_size=5); hyp = " ".join(s.text for s in seg).strip()
    res[Path(f).name] = {"cer": round(cer(norm(hyp), norm(KK[Path(f).name[:2]])), 3), "hyp": hyp}
    print(Path(f).name, res[Path(f).name])
json.dump(res, open("out_kk/cer_kk.json", "w"), ensure_ascii=False, indent=1)
