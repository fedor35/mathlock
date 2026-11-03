"""Время слов в эталонной озвучке: CTC forced alignment GigaAM по тексту.
Аргументы: texts.jsonl dir_with_ogg. Пишет рядом <id>.<голос>.json: {"words":[[t0,t1],...]} в секундах,
по одному отрезку на слово текста (те же слова, что ReadingText: буквы + дефис внутри слова)."""
import glob, json, re, subprocess, sys
from pathlib import Path
import numpy as np, torch, torchaudio
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
m = mg.GigaAMModel.from_pretrained(snap).model.eval().cuda()
V = list(m.cfg.decoding.vocabulary); B = len(V)
texts = {json.loads(l)["id"]: json.loads(l)["text"] for l in Path(sys.argv[1]).read_text().splitlines() if l.strip()}
WORD = re.compile(r"[а-яёА-ЯЁ][а-яёА-ЯЁ́]*(?:-[а-яёА-ЯЁ][а-яёА-ЯЁ́]*)*")
bad = 0
for f in sorted(glob.glob(f"{sys.argv[2]}/*.ogg")):
    tid = Path(f).name.split(".")[0]
    words = [w.lower().replace("́", "").replace("ё", "е").replace("-", "") for w in WORD.findall(texts[tid])]
    tgt = " ".join(words)
    pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    w = torch.from_numpy(np.frombuffer(pcm, np.float32).copy()).unsqueeze(0).cuda()
    with torch.no_grad():
        x, xl = m.preprocessor(w, torch.tensor([w.shape[-1]]).cuda()); enc, el = m.encoder(x, xl)
        lp = m.head(enc).log_softmax(-1).float().cpu()
    ids = torch.tensor([[V.index(c) for c in tgt]])
    try:
        ali, sc = torchaudio.functional.forced_align(lp, ids, blank=B)
    except Exception as e:
        print("не выровнялось", f, e); bad += 1; continue
    spans = torchaudio.functional.merge_tokens(ali[0], sc[0].exp(), blank=B)  # по одному на символ цели
    out, k = [], 0
    for wd in words:
        s0, s1 = spans[k], spans[k + len(wd) - 1]
        out.append([round(s0.start * 0.04, 2), round((s1.end + 1) * 0.04, 2)])
        k += len(wd) + 1  # + пробел
    json.dump({"words": out}, open(f[:-4] + ".json", "w"))
print("готово, ошибок:", bad)
