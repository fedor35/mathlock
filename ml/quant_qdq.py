"""QDQ A16W8 для QNN HTP (v69 без fp16). Калибровка — лог-мел русских wav. Аргументы: T wav..."""
import glob, sys
from pathlib import Path
import numpy as np, torch, torchaudio
from onnxruntime.quantization import QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
snap = glob.glob("/home/fedor/.cache/huggingface/hub/models--ai-sage--GigaAM-v3/snapshots/15ef*")[0]
sys.path.insert(0, snap); import modeling_gigaam as mg
OUT = Path("/mnt/2tb/mathlock-ml/models"); T = int(sys.argv[1])
pre = mg.GigaAMModel.from_pretrained(snap).model.preprocessor.eval()
feats = []
for f in sys.argv[2:]:
    w, sr = torchaudio.load(f); w = torchaudio.functional.resample(w.mean(0, keepdim=True), sr, 16000)
    with torch.no_grad(): x, _ = pre(w, torch.tensor([w.shape[-1]]))
    p = np.full((1, 64, T), np.log(1e-9), np.float32); n = min(T, x.shape[-1]); p[..., :n] = x[..., :n].numpy()
    feats.append(p)
class R:
    def __init__(s): s.it = iter(feats)
    def get_next(s):
        x = next(s.it, None); return None if x is None else {"feats": x}
src = OUT / f"gigaam_ctc_T{T}_fp32.onnx"; prep = OUT / f"gigaam_ctc_T{T}_prep.onnx"
prep = src  # qnn_preprocess_model падает на onnx-версии; LayerNorm уже слит
cfg = get_qnn_qdq_config(str(prep), R(), activation_type=QuantType.QUInt16, weight_type=QuantType.QUInt8, per_channel=True)
quantize(str(prep), str(OUT / f"gigaam_ctc_T{T}_qdq.onnx"), cfg)
print("ok, калибровочных:", len(feats))
