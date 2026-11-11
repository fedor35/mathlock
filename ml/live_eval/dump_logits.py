"""Лог-вероятности GigaAM-v3 ctc для каждого wav: <out>/<имя>.f32 (T×34, float32), окнами как в приложении."""
import glob, sys, os
from pathlib import Path
import numpy as np, torch, soundfile as sf
snap = glob.glob(os.path.expanduser("~/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*"))[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
out = Path(sys.argv[1]); out.mkdir(exist_ok=True)
for f in sys.argv[2:]:
    x, sr = sf.read(f, dtype="float32"); assert sr == 16000
    w = torch.from_numpy(x).unsqueeze(0).cuda()
    with torch.no_grad():
        a, al = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); T = int(al[0]); outs = []
        k = 0
        while True:   # окна как StreamingRecognizer: WIN 1600, STEP 1200, CTX 200 кадров признаков
            st = max(0, k * 1200 - 200); en = min(T, st + 1600)
            enc, el = m.encoder(a[:, :, st:en], torch.tensor([en - st]).cuda())
            lp = torch.log_softmax(m.head(enc)[0].float(), -1)[: int(el[0])]
            fr, to = (k * 1200 - st) // 4, ((k + 1) * 1200 - st) // 4
            if T >= (k + 1) * 1200 + 200: outs.append(lp[fr:to]); k += 1; continue
            outs.append(lp[fr:]); break
        lp = torch.cat(outs).cpu().numpy().astype("<f4")
    lp.tofile(out / (Path(f).stem + ".f32")); print(Path(f).stem, lp.shape, flush=True)
