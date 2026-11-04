"""Контрольная озвучка с НЕВЕРНЫМИ ударениями: слушает ли классификатор звук или «знает» слово.
Запуск на r99950x в ttscmp/venv (Silero v5 + Vosk TTS 0.9, ударение задаётся «+»):
  python gen_wrong.py texts_ru4.jsonl out_dir [уровни, по умолчанию 1-5]
В каждом многосложном слове с одним ударением (без «ё» и дефиса) ударение с вероятностью 1/2 переносится
на случайную другую гласную. Пишет out_dir/texts_wrong.jsonl (id = <id>w, text — с фактическими ударениями,
orig — исходный текст) и out_dir/<id>w/<движок-голос>.wav (5 голосов Silero + 5 Vosk)."""
import json, os, random, re, sys, unicodedata
import torch, soundfile as sf

ACUTE = "́"; VOW = "аеиоуыэюяАЕИОУЫЭЮЯ"
WORD = re.compile(r"[а-яёА-ЯЁ][а-яёА-ЯЁ́]*(?:-[а-яёА-ЯЁ][а-яёА-ЯЁ́]*)*")
plus = lambda t: unicodedata.normalize("NFC", re.sub(r"(\w)" + ACUTE, lambda m: "+" + m.group(1), t))


def shift(word, rng):
    if "-" in word or "ё" in word.lower() or word.count(ACUTE) != 1:
        return word, False
    plain = word.replace(ACUTE, "")
    vpos = [i for i, c in enumerate(plain) if c in VOW]
    if len(vpos) < 2:
        return word, False
    cur = vpos.index(word.index(ACUTE) - 1)
    if rng.random() < 0.5:
        return word, False
    new = rng.choice([j for j in range(len(vpos)) if j != cur])
    k = vpos[new] + 1
    return plain[:k] + ACUTE + plain[k:], True


if __name__ == "__main__":
    texts_f, out = sys.argv[1], sys.argv[2]
    lv = sys.argv[3] if len(sys.argv) > 3 else "1-5"
    lo, hi = map(int, lv.split("-"))
    rng = random.Random(4)
    texts = [json.loads(l) for l in open(texts_f) if l.strip()]
    texts = [t for t in texts if lo <= t["level"] <= hi]
    os.makedirs(out, exist_ok=True)
    jobs = []
    with open(f"{out}/texts_wrong.jsonl", "w") as fo:
        for t in texts:
            nch = [0]
            def rep(m):
                w, ch = shift(m.group(0), rng); nch[0] += ch; return w
            txt = WORD.sub(rep, t["text"])
            j = dict(id=t["id"] + "w", level=t["level"], text=txt, orig=t["text"], changed=nch[0])
            fo.write(json.dumps(j, ensure_ascii=False) + "\n"); jobs.append(j)
    from silero import silero_tts
    from vosk_tts import Model, Synth
    torch.set_num_threads(8)
    sm, _ = silero_tts(language="ru", speaker="v5_6_ru")
    sy = Synth(Model(model_path="/home/fedor/.cache/vosk/vosk-model-tts-ru-0.9-multi"))
    for j in jobs:
        d = f"{out}/{j['id']}"; os.makedirs(d, exist_ok=True)
        for s in ["aidar", "baya", "kseniya", "xenia", "eugene"]:
            f = f"{d}/silero-{s}.wav"
            if not os.path.exists(f):
                sf.write(f, sm.apply_tts(text=plus(j["text"]), speaker=s, sample_rate=24000).numpy(), 24000)
        for i in range(5):
            f = f"{d}/vosk-s{i}.wav"
            if not os.path.exists(f):
                sy.synth(plus(j["text"]), f, speaker_id=i)
        print(j["id"], j["changed"], flush=True)
    print("done")
