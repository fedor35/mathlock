# Образцы офлайн-TTS через sherpa-onnx: RU и KK, замер RTF на CPU (2 потока, как на телефоне).
import json, os, sys, time, unicodedata
import sherpa_onnx as s, soundfile as sf
M = "models"; OUT = "out"; os.makedirs(OUT, exist_ok=True)
RU = {"L1": "Ма́ма. Ра́ма. Ми́ла.",
      "L3": "Наш пёс Ша́рик охраня́ет кали́тку. Он ве́рный друг!",
      "L5": "В па́рке живёт ма́ленькая ры́жая бе́лка. Она́ о́чень прово́рная. Прыг с ве́тки на ве́тку — и уже́ на верху́шке! Ари́на принесла́ ей оре́хи. Бе́лка спусти́лась, взяла́ оре́х и спря́тала его́ в дупло́. — Она́ запаса́ет еду́ на зи́му, — объясни́л па́па."}
KK = {"K1": "Менің атым Арман. Мен мектепте оқимын.",
      "K2": "Бүгін ауа райы жақсы. Біз әжеммен бірге саябаққа барамыз. Онда үлкен көл бар, көлде аққулар жүзіп жүр."}
plain = lambda t: unicodedata.normalize("NFC", t.replace("́", ""))
plus = lambda t: __import__("re").sub(r"(\w)́", lambda m: "+" + m.group(1), t)  # 'ма́ма' -> 'м+ама'
def vits(d):
    n = [f for f in os.listdir(f"{M}/{d}") if f.endswith(".onnx")][0]
    lx = f"{M}/{d}/lexicon.txt"
    return s.OfflineTtsModelConfig(vits=s.OfflineTtsVitsModelConfig(model=f"{M}/{d}/{n}", tokens=f"{M}/{d}/tokens.txt",
        data_dir=f"{M}/{d}/espeak-ng-data" if os.path.isdir(f"{M}/{d}/espeak-ng-data") else "",
        lexicon=lx if os.path.exists(lx) else ""), num_threads=2, provider="cpu")
def supertonic():
    d = f"{M}/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    return s.OfflineTtsModelConfig(supertonic=s.OfflineTtsSupertonicModelConfig(
        duration_predictor=f"{d}/duration_predictor.int8.onnx", text_encoder=f"{d}/text_encoder.int8.onnx",
        vector_estimator=f"{d}/vector_estimator.int8.onnx", vocoder=f"{d}/vocoder.int8.onnx", tts_json=f"{d}/tts.json",
        unicode_indexer=f"{d}/unicode_indexer.bin", voice_style=f"{d}/voice.bin"), num_threads=2, provider="cpu")
jobs = []
jobs.append(("mms-rus", vits("vits-mms-rus"), RU, None))
for sid in [0, 3, 6, 9]:
    jobs.append((f"supertonic3-s{sid}", supertonic(), RU, {"sid": sid, "lang": "ru"}))
res = []
cache = {}
for name, cfg, texts, extra in jobs:
    key = name.split("-s")[0] if name.startswith("supertonic") else name
    if key not in cache: cache[key] = s.OfflineTts(s.OfflineTtsConfig(model=cfg))
    tts = cache[key]
    for tid, t in texts.items():
        variants = [("", plain(t))]
        for suf, txt in variants:
            t0 = time.time()
            if extra:
                g = s.GenerationConfig(); g.sid = extra["sid"]; g.num_steps = 8; g.speed = 1.0; g.extra["lang"] = extra["lang"]
                a = tts.generate(txt, g)
            else:
                a = tts.generate(txt, sid=0, speed=1.0)
            dt = time.time() - t0; dur = len(a.samples) / a.sample_rate
            f = f"{OUT}/{tid}.{name}{'.plus' if suf else ''}.wav"; sf.write(f, a.samples, a.sample_rate)
            res.append({"file": f, "sec": round(dur, 2), "gen": round(dt, 2), "rtf": round(dt / max(dur, .01), 3)})
            print(res[-1], flush=True)
import numpy as np, onnxruntime
from piper import PiperVoice
piper = [(f"piper-{v}", f"vits-piper-ru_RU-{v}-medium", RU) for v in ["ruslan", "irina", "dmitri", "denis"]] + \
        [(f"piper-kk-{v}", f"vits-piper-kk_KZ-{v}", KK) for v in ["issai-high", "iseke-x_low", "raya-x_low"]]
so = onnxruntime.SessionOptions(); so.intra_op_num_threads = 2
for name, d, texts in piper:
    n = [f for f in os.listdir(f"{M}/{d}") if f.endswith(".onnx")][0]
    voice = PiperVoice.load(f"{M}/{d}/{n}")
    voice.session = onnxruntime.InferenceSession(f"{M}/{d}/{n}", sess_options=so, providers=["CPUExecutionProvider"])
    for tid, t in texts.items():
        variants = [("", plain(t))] + ([(".acute", unicodedata.normalize("NFC", t))] if texts is RU else [])
        for suf, txt in variants:
            t0 = time.time(); ch = list(voice.synthesize(txt)); dt = time.time() - t0
            x = np.concatenate([c.audio_float_array for c in ch]); sr = ch[0].sample_rate; dur = len(x) / sr
            f = f"{OUT}/{tid}.{name}{suf}.wav"; sf.write(f, x, sr)
            res.append({"file": f, "sec": round(dur, 2), "gen": round(dt, 2), "rtf": round(dt / max(dur, .01), 3)})
            print(res[-1], flush=True)
json.dump(res, open(f"{OUT}/sherpa_times.json", "w"), ensure_ascii=False, indent=1)
