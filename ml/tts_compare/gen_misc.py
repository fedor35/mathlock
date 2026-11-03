import json, time, unicodedata, re
import torch, soundfile as sf
torch.set_num_threads(2)
RU = {"L1": "Ма́ма. Ра́ма. Ми́ла.",
      "L3": "Наш пёс Ша́рик охраня́ет кали́тку. Он ве́рный друг!",
      "L5": "В па́рке живёт ма́ленькая ры́жая бе́лка. Она́ о́чень прово́рная. Прыг с ве́тки на ве́тку — и уже́ на верху́шке! Ари́на принесла́ ей оре́хи. Бе́лка спусти́лась, взяла́ оре́х и спря́тала его́ в дупло́. — Она́ запаса́ет еду́ на зи́му, — объясни́л па́па."}
KK = {"K1": "Менің атым Арман. Мен мектепте оқимын.",
      "K2": "Бүгін ауа райы жақсы. Біз әжеммен бірге саябаққа барамыз. Онда үлкен көл бар, көлде аққулар жүзіп жүр."}
plain = lambda t: unicodedata.normalize("NFC", t.replace("́", ""))
plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))
res = []
def rec(f, dt):
    d = sf.info(f).duration; res.append({"file": f, "sec": round(d, 2), "gen": round(dt, 2), "rtf": round(dt / d, 3)}); print(res[-1], flush=True)
# MMS kaz
from transformers import VitsModel, AutoTokenizer
tok = AutoTokenizer.from_pretrained("facebook/mms-tts-kaz"); m = VitsModel.from_pretrained("facebook/mms-tts-kaz")
for tid, t in KK.items():
    t0 = time.time()
    with torch.no_grad(): w = m(**tok(t, return_tensors="pt")).waveform[0].numpy()
    f = f"out/{tid}.mms-kaz.wav"; sf.write(f, w, m.config.sampling_rate); rec(f, time.time() - t0)
# Vosk TTS ru
from vosk_tts import Model, Synth
vm = Model(lang="ru"); sy = Synth(vm)
for spk in range(5):
    for tid, t in RU.items():
        for suf, txt in [("", plain(t)), (".plus", plus(t))]:
            f = f"out/{tid}.vosk-s{spk}{suf}.wav"; t0 = time.time(); sy.synth(txt, f, speaker_id=spk); rec(f, time.time() - t0)
json.dump(res, open("out/misc_times.json", "w"), ensure_ascii=False, indent=1)
