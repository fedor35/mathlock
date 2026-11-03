import glob, json, re, subprocess, collections
import numpy as np
from pathlib import Path
from faster_whisper import WhisperModel
m = WhisperModel("large-v3", device="cuda", compute_type="float16")
EXP = {"wa": "わあ", "sugoi": "すごい", "yatta": "やった", "oh": "おお", "ehehe": "えへへ", "kanpeki": "かんぺき", "wao": "わお", "ah": "あすごい"}
def kana(s):
    s = "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)
    s = s.replace("完璧", "かんぺき").replace("凄い", "すごい").replace("すごーい", "すごい")
    return re.sub(r"[^ぁ-ゖ]", "", s)
def cer(a, b):
    d = list(range(len(b)+1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1): p, d[j] = d[j], min(d[j]+1, d[j-1]+1, p+(ca!=cb))
    return d[-1]/max(1,len(b))
rows = collections.defaultdict(list)
for f in sorted(glob.glob("cheer/*.wav")):
    v, k, i = Path(f).stem.split(".")
    x = np.frombuffer(subprocess.run(["ffmpeg","-v","quiet","-i",f,"-ac","1","-ar","16000","-f","f32le","-"],capture_output=True).stdout, np.float32)
    dur = len(x) / 16000
    seg, _ = m.transcribe(x, language="ja", beam_size=5); h = "".join(s.text for s in seg)
    c = cer(kana(h).replace("ー",""), EXP[k]); rows[(v, k)].append((c, dur, f, h))
keep = []
for (v, k), r in sorted(rows.items()):
    r = [x for x in sorted(r) if x[0] <= 0.5 and 0.3 <= x[1] <= 3.0]
    print(v, k, [(round(c,2), round(d,1), h) for c, d, f, h in r[:2]])
    keep += [f for c, d, f, h in r[:2]]
json.dump(keep, open("cheer_keep.json", "w")); print("оставлено", len(keep))
