import json, re, sys, time, unicodedata, os
import torch, soundfile as sf
from silero import silero_tts
torch.set_num_threads(2)
sys.path.insert(0, ".")
RU = {"L1": "Ма́ма. Ра́ма. Ми́ла.",
      "L3": "Наш пёс Ша́рик охраня́ет кали́тку. Он ве́рный друг!",
      "L5": "В па́рке живёт ма́ленькая ры́жая бе́лка. Она́ о́чень прово́рная. Прыг с ве́тки на ве́тку — и уже́ на верху́шке! Ари́на принесла́ ей оре́хи. Бе́лка спусти́лась, взяла́ оре́х и спря́тала его́ в дупло́. — Она́ запаса́ет еду́ на зи́му, — объясни́л па́па."}
KK = {"K1": "Менің атым Арман. Мен мектепте оқимын.",
      "K2": "Бүгін ауа райы жақсы. Біз әжеммен бірге саябаққа барамыз. Онда үлкен көл бар, көлде аққулар жүзіп жүр."}
plain = lambda t: unicodedata.normalize("NFC", t.replace("́", ""))
plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))
res = []
def run(model, spk, tid, txt, name):
    t0 = time.time(); a = model.apply_tts(text=txt, speaker=spk, sample_rate=48000); dt = time.time() - t0
    a = a.numpy(); dur = len(a) / 48000; f = f"out/{tid}.{name}.wav"; sf.write(f, a, 48000)
    res.append({"file": f, "sec": round(dur, 2), "gen": round(dt, 2), "rtf": round(dt / dur, 3)}); print(res[-1], flush=True)
pass
for spk in []: #, "baya", "kseniya", "xenia", "eugene"]:
    for tid, t in RU.items():
        run(m, spk, tid, plain(t), f"silero-{spk}")
        run(m, spk, tid, plus(t), f"silero-{spk}.plus")
for mid, spks in [("v5_cis_base", ["kaz_zhadyra", "kaz_zhazira"]), ("v5_cis_ext", ["kaz_abai", "kaz_aidana", "kaz_aisha", "kaz_bakir", "kaz_danara"])]:
    try:
        m, _ = silero_tts(language="ru", speaker=mid)
    except Exception as e:
        print("load", mid, e); continue
    for spk in spks:
        for tid, t in KK.items():
            try: run(m, spk, tid, t, f"silero-{spk}")
            except Exception as e: print("fail", spk, e)
json.dump(res, open("out/silero_kk_times.json", "w"), ensure_ascii=False, indent=1)
