"""Независимая расшифровка живых записей чтения (faster-whisper large-v3, без подсказки текста) — опора для
оценки сверки ReadingMatcher. Whisper тянет к «правильным» словам, поэтому как эталон он скорее прощает ошибки
ребёнка: его расхождения с «красным» приложения — кандидаты в ложные «красные».
Запуск на r99950x: /mnt/2tb/asr-tools/venv/bin/python whisper_refs.py <каталог wav> <out.jsonl>"""
import glob, json, sys
from pathlib import Path
import soundfile as sf
from faster_whisper import WhisperModel

m = WhisperModel("large-v3", device="cuda", compute_type="float16")
done = set()
out_path = Path(sys.argv[2])
if out_path.exists():
    done = {json.loads(l)["rec"] for l in out_path.read_text().splitlines() if l.strip()}
with out_path.open("a") as out:
    for f in sorted(glob.glob(f"{sys.argv[1]}/*.wav")):
        rec = Path(f).stem
        if rec in done: continue
        a, sr = sf.read(f, dtype="float32")
        if len(a) < sr * 1.0: continue
        segs, _ = m.transcribe(a, language="ru", beam_size=5, condition_on_previous_text=False, vad_filter=False)
        text = " ".join(s.text.strip() for s in segs)
        out.write(json.dumps({"rec": rec, "whisper": text}, ensure_ascii=False) + "\n"); out.flush()
        print(rec, text[:80], flush=True)
