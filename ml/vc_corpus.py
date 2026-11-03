"""Корпус v3: Silero (ударения корпуса) -> Seed-VC в голоса персонажей. Модели грузятся один раз.
Запуск из каталога seed-vc: ../seed-vc/.venv/bin/python ../ttscmp/vc_corpus.py"""
import argparse, glob, json, os, shutil, sys
sys.path.insert(0, os.getcwd())
import inference as I
cache = {}
orig = I.load_models
def cached(args):
    if "m" not in cache: cache["m"] = orig(args)
    return cache["m"]
I.load_models = cached
T = "/mnt/2tb/mathlock-ml"
SRC = {"skazochnik": "silero-aidar", "velikan": "silero-aidar", "devochka": "silero-baya", "feya": "silero-baya", "malchik": "silero-baya"}
texts = [json.loads(l) for l in open(f"{T}/tts/texts_ru.jsonl") if l.strip()]
out = f"{T}/ttscmp/vc_ru"; tmp = f"{out}/tmp"; os.makedirs(tmp, exist_ok=True)
for t in texts:
    for ch, src in SRC.items():
        dst = f"{out}/{t['id']}.{ch}.wav"
        if os.path.exists(dst): continue
        for f in glob.glob(f"{tmp}/*.wav"): os.remove(f)
        a = argparse.Namespace(source=f"{T}/ttscmp/refs_ru/{t['id']}/{src}.wav", target=f"{T}/tts/voices/{ch}.wav", output=tmp,
                               diffusion_steps=50, length_adjust=1.25 if t.get("level", 3) <= 2 else 1.0, inference_cfg_rate=0.7,
                               f0_condition=False, auto_f0_adjust=False, semi_tone_shift=0, checkpoint=None, config=None, fp16=False)
        I.main(a)
        shutil.move(glob.glob(f"{tmp}/*.wav")[0], dst)
        print(t["id"], ch, flush=True)
print("done")
