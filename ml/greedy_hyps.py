"""Жадная CTC-гипотеза GigaAM-v3 ctc для каждого ogg в каталогах; печать jsonl {file, id, hyp}."""
import glob, json, subprocess, sys
from pathlib import Path
import numpy as np, torch
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
V = list(m.cfg.decoding.vocabulary); B = len(V)
out = open(sys.argv[1], "w")
for d in sys.argv[2:]:
    for f in sorted(glob.glob(f"{d}/*.ogg")):
        pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"], capture_output=True, check=True).stdout
        w = torch.from_numpy(np.frombuffer(pcm, np.float32).copy()).unsqueeze(0).cuda()
        with torch.no_grad():
            x, xl = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); enc, el = m.encoder(x, xl)
            a = m.head(enc).argmax(-1)[0].cpu().tolist()
        s, p = [], -1
        for t in a:
            if t != p and t != B: s.append(V[t])
            p = t
        out.write(json.dumps({"file": Path(f).name, "id": Path(f).name.split(".")[0], "hyp": "".join(s)}, ensure_ascii=False) + "\n")
