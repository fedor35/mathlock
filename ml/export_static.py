"""Статическая форма для QNN HTP: feats [1,64,T] -> logp [1,T/4,34], без входа длины (паддинг тишиной)."""
import glob, sys, time
from pathlib import Path
import numpy as np, torch
OUT = Path("/mnt/2tb/mathlock-ml/models")
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
T = int(sys.argv[1]) if len(sys.argv) > 1 else 1600
m = mg.GigaAMModel.from_pretrained(snap).model.eval().float()
class Wrap(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, feats):
        enc, _ = s.m.encoder(feats, torch.full((1,), T, dtype=torch.long))
        return s.m.head(enc).log_softmax(-1)
w = Wrap(m).eval()
x = torch.from_numpy(np.load(OUT / "test_feats.npy"))
pad = torch.full((1, 64, T), float(np.log(1e-9)))
pad[..., :x.shape[-1]] = x
p = OUT / f"gigaam_ctc_T{T}_fp32.onnx"
with torch.no_grad():
    ref = w(pad).numpy()
    torch.onnx.export(w, (pad,), str(p), input_names=["feats"], output_names=["logp"], opset_version=17, dynamo=False)
import onnx, onnxsim
mo, ok = onnxsim.simplify(onnx.load(str(p))); onnx.save(mo, str(p), save_as_external_data=False)
ops = {}
for n in mo.graph.node: ops[n.op_type] = ops.get(n.op_type, 0) + 1
print("simplified", ok, "ops:", dict(sorted(ops.items(), key=lambda kv: -kv[1])))
import onnxruntime as ort
s = ort.InferenceSession(str(p)); t = time.time(); o = s.run(None, {"feats": pad.numpy()})[0]
print(f"T={T} cpu {time.time()-t:.2f}s maxdiff {np.abs(o-ref).max():.4f}")
