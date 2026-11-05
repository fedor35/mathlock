"""Проверка: неверное окончание, произнесённое ОТДЕЛЬНО (без контекста фразы) — что слышит GigaAM.
Если отдельно слышит «новую», а во фразе «новые» — грамматику правит распознавание (внутренняя языковая модель).
python iso.py endforms.json iso.jsonl  (рядом с synth.py, тот же venv)"""
import json, sys, torch, torchaudio
sys.argv, args = [sys.argv[0], "/dev/null", "/tmp"], sys.argv[1:]
exec(open(__file__.replace("iso.py", "synth.py")).read().split("hf = os.path.join")[0])   # модели и transcribe из synth.py
fo = open(args[1], "w")
for e in json.load(open(args[0])):
    w = sm.apply_tts(text=plus(e["said"] + "."), speaker=e["voice"], sample_rate=24000).float().cpu()
    w16 = torchaudio.functional.resample(w, int(24000 * 1.15) if e["child"] else 24000, 16000)
    e["iso"] = transcribe(w16); fo.write(json.dumps(e, ensure_ascii=False) + "\n")
print("done")
