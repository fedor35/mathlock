import json, re, subprocess, time, unicodedata, soundfile as sf
RU = {"L1": "Ма́ма. Ра́ма. Ми́ла.", "L3": "Наш пёс Ша́рик охраня́ет кали́тку. Он ве́рный друг!",
 "L5": "В па́рке живёт ма́ленькая ры́жая бе́лка. Она́ о́чень прово́рная. Прыг с ве́тки на ве́тку — и уже́ на верху́шке! Ари́на принесла́ ей оре́хи. Бе́лка спусти́лась, взяла́ оре́х и спря́тала его́ в дупло́. — Она́ запаса́ет еду́ на зи́му, — объясни́л па́па."}
plain = lambda t: unicodedata.normalize("NFC", t.replace("́", ""))
plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)́", lambda m: "+" + m.group(1), t))
res = []
for v in ["aleksandr-hq", "anna", "arina", "elena", "irina", "mikhail", "umka", "tatiana", "vitaliy-ng", "artemiy"]:
    for tid, t in RU.items():
        for suf, txt in [("", plain(t)), (".plus", plus(t))]:
            f = f"out/{tid}.rhvoice-{v}{suf}.wav"; t0 = time.time()
            subprocess.run(["RHVoice-test", "-p", v, "-o", f], input=txt.encode(), check=True, capture_output=True)
            dt = time.time() - t0; d = sf.info(f).duration
            res.append({"file": f, "sec": round(d, 2), "gen": round(dt, 2), "rtf": round(dt / d, 3)}); print(res[-1], flush=True)
json.dump(res, open("out/rhv_times.json", "w"), ensure_ascii=False, indent=1)
