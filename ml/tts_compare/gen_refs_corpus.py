"""Эталоны ударений для корпуса: Silero v5.6 (5 голосов, ударения корпуса через +), Vosk 0.9 (5, через +), RHVoice (5, свой словарь).
refs_ru/<id>/<движок-голос>.wav"""
import json, os, re, subprocess, sys, unicodedata
import torch, soundfile as sf
from silero import silero_tts
from vosk_tts import Model, Synth
torch.set_num_threads(8)
plain = lambda t: unicodedata.normalize("NFC", t.replace("́", ""))
plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))
texts = [json.loads(l) for l in open("../tts/texts_ru.jsonl") if l.strip()]
sm, _ = silero_tts(language="ru", speaker="v5_6_ru")
sy = Synth(Model(model_path="/home/fedor/.cache/vosk/vosk-model-tts-ru-0.9-multi"))
for t in texts:
    d = f"refs_ru/{t['id']}"; os.makedirs(d, exist_ok=True)
    if len(os.listdir(d)) >= 15: continue
    for s in ["aidar", "baya", "kseniya", "xenia", "eugene"]:
        sf.write(f"{d}/silero-{s}.wav", sm.apply_tts(text=plus(t["text"]), speaker=s, sample_rate=24000).numpy(), 24000)
    for i in range(5): sy.synth(plus(t["text"]), f"{d}/vosk-s{i}.wav", speaker_id=i)
    for v in ["aleksandr-hq", "anna", "elena", "irina", "mikhail"]:
        subprocess.run(["RHVoice-test", "-p", v, "-o", f"{d}/rhvoice-{v}.wav"], input=plain(t["text"]).encode(), check=True, capture_output=True)
    print(t["id"], flush=True)
print("done")
