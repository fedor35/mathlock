"""Экспорт GigaAM-v3 CTC (символьная) в ONNX: лог-мел [1,64,T] + длина -> log_probs [1,T/4,34]."""
import glob, json, sys, time
from pathlib import Path
import numpy as np, torch

OUT = Path("/mnt/2tb/mathlock-ml/models"); OUT.mkdir(parents=True, exist_ok=True)
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap)
import modeling_gigaam as mg

model = mg.GigaAMModel.from_pretrained(snap).model.eval().float()
vocab = list(model.cfg.decoding.vocabulary)
json.dump({"vocab": vocab, "blank": len(vocab), "sample_rate": 16000, "n_mels": 64,
           "n_fft": 320, "win": 320, "hop": 160, "center": False, "mel_scale": "htk",
           "log": "ln(clamp(x,1e-9,1e9))", "subsampling": 4},
          open(OUT / "gigaam_ctc_meta.json", "w"), ensure_ascii=False, indent=1)

class Wrap(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, feats, lens):
        enc, _ = s.m.encoder(feats, lens)
        return s.m.head(enc).log_softmax(-1)

w = Wrap(model).eval()
wav = mg.load_audio(sys.argv[1]).unsqueeze(0)
ln = torch.tensor([wav.shape[-1]])
with torch.no_grad():
    print("torch transcribe:", model.transcribe(sys.argv[1]))
    feats, flen = model.preprocessor(wav, ln)
    np.save(OUT / "test_feats.npy", feats.numpy())
    ref = w(feats, flen).numpy()
    torch.onnx.export(w, (feats, flen), str(OUT / "gigaam_ctc_fp32.onnx"),
        input_names=["feats", "lens"], output_names=["logp"],
        dynamic_axes={"feats": {0: "B", 2: "T"}, "lens": {0: "B"}, "logp": {0: "B", 1: "U"}},
        opset_version=17, dynamo=False)

import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(str(OUT / "gigaam_ctc_fp32.onnx"), str(OUT / "gigaam_ctc_int8.onnx"), weight_type=QuantType.QInt8)

def greedy(lp):
    ids = lp.argmax(-1); out = []; prev = -1
    for i in ids:
        if i != prev and i != len(vocab): out.append(vocab[i])
        prev = i
    return "".join(out)

print("ref greedy:", greedy(ref[0]))
for name in ["fp32", "int8"]:
    s = ort.InferenceSession(str(OUT / f"gigaam_ctc_{name}.onnx"), providers=["CPUExecutionProvider"])
    t = time.time(); o = s.run(None, {"feats": feats.numpy(), "lens": flen.numpy()})[0]; dt = time.time() - t
    print(name, f"{dt:.2f}s audio {wav.shape[-1]/16000:.1f}s maxdiff {np.abs(o-ref).max():.3f}", greedy(o[0]))
