"""Пакетная озвучка OmniVoice в макс. качестве. Вход JSONL: {id,text,instruct?|ref_audio+ref_text, n?}.
Каждая запись -> n кандидатов out/<id>.<k>.wav (fp32, num_step=64)."""
import json, sys, time
from pathlib import Path
import torch, soundfile as sf
from omnivoice import OmniVoice
from omnivoice.models.omnivoice import OmniVoiceGenerationConfig as GC

src, out = Path(sys.argv[1]), Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
steps = int(sys.argv[3]) if len(sys.argv) > 3 else 64
model = OmniVoice.from_pretrained("k2-fsa/OmniVoice", device_map="cuda:0", dtype=torch.float32)
for line in src.read_text().splitlines():
    if not line.strip(): continue
    r = json.loads(line)
    for k in range(r.get("n", 3)):
        p = out / f"{r['id']}.{k}.wav"
        if p.exists(): continue
        torch.manual_seed(1000 + k)
        kw = dict(text=r["text"], language="ru", generation_config=GC(num_step=steps))
        if "ref_audio" in r: kw.update(ref_audio=r["ref_audio"], ref_text=r.get("ref_text"))
        elif "instruct" in r: kw["instruct"] = r["instruct"]
        if "speed" in r: kw["speed"] = r["speed"]
        t = time.time(); a = model.generate(**kw)
        a = a[0] if isinstance(a, list) else a
        a = a.squeeze().float().cpu().numpy() if torch.is_tensor(a) else a.squeeze()
        sf.write(p, a, 24000); print(p.name, f"{len(a)/24000:.1f}s за {time.time()-t:.1f}s", flush=True)
