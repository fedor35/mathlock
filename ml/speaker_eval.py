#!/usr/bin/env python3
"""Сравнение моделей голосовых отпечатков (speaker embedding, ONNX из релиза sherpa-onnx) для профилей чтецов.

Признаки как при обучении моделей: Kaldi fbank 80 (kaldi-native-fbank = torchaudio.compliance.kaldi.fbank),
dither 0, snip_edges, окно povey (3D-Speaker) или hamming (WeSpeaker), затем отбор речи по энергии кадра
(тот же алгоритм, что в android/reading Fbank.voiced) и вычитание среднего по времени.

Наборы:
  so    — speechocean762 (OpenSLR 101): 250 человек, английский как иностранный, возраст 6–43, по 20 фраз 2–5 с;
  tts   — озвучка корпуса (out_ru3/out_ru4): Silero → Seed-VC в голоса персонажей (не настоящие дети!);
  fedor — живые записи Фёдора с телефона (взрослый мужчина).

Запуск (r99950x): venv ttscmp, python -I ml/speaker_eval.py --root /mnt/2tb/mathlock-ml/spk
"""
import argparse, glob, json, os, random, subprocess, sys, time

import numpy as np
import onnxruntime as ort
import kaldi_native_fbank as knf

SR = 16000
VAD_DB = 30.0      # кадр — речь, если энергия не ниже 95-го перцентиля минус 30 дБ
VAD_FLOOR = -55.0  # и не тише −55 дБFS


def load(path):
    if path.lower().endswith(".wav"):
        import soundfile as sf
        x, sr = sf.read(path, dtype="float32")
        if x.ndim > 1: x = x.mean(1)
        if sr == SR: return x
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768


def fbank(x, window="povey", scale=1.0):
    o = knf.FbankOptions()
    o.frame_opts.dither = 0; o.frame_opts.snip_edges = True; o.frame_opts.window_type = window
    o.frame_opts.samp_freq = SR
    o.mel_opts.num_bins = 80; o.mel_opts.low_freq = 20; o.mel_opts.high_freq = 0
    f = knf.OnlineFbank(o); f.accept_waveform(SR, (x * scale).tolist()); f.input_finished()
    return np.stack([f.get_frame(i) for i in range(f.num_frames_ready)]) if f.num_frames_ready else np.zeros((0, 80), np.float32)


def voiced(x):
    """Маска речевых кадров (25/10 мс, как fbank): энергия ≥ max(p95 − VAD_DB, VAD_FLOOR) дБFS."""
    n = 0 if len(x) < 400 else (len(x) - 400) // 160 + 1
    if n == 0: return np.zeros(0, bool)
    idx = np.arange(400)[None, :] + 160 * np.arange(n)[:, None]
    fr = x[idx].astype(np.float64)
    e = 10 * np.log10(np.mean(fr * fr, 1) + 1e-10)
    thr = max(np.percentile(e, 95) - VAD_DB, VAD_FLOOR)
    return e >= thr


class Model:
    def __init__(self, path):
        so = ort.SessionOptions(); so.intra_op_num_threads = 4
        self.s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        m = self.s.get_modelmeta().custom_metadata_map
        self.wespeaker = m.get("framework") == "wespeaker"
        self.window = "hamming" if self.wespeaker else "povey"
        self.scale = 32768.0 if m.get("normalize_samples") == "0" else 1.0
        self.inp = self.s.get_inputs()[0].name
        self.name = os.path.basename(path)[:-5]

    def feats(self, x):
        f = fbank(x, self.window, self.scale)
        m = voiced(x)[:len(f)]
        if m.sum() >= 50: f = f[m]
        return f - f.mean(0, keepdims=True)

    def embed(self, x):
        f = self.feats(x)
        e = self.s.run(None, {self.inp: f[None].astype(np.float32)})[0][0]
        return e / np.linalg.norm(e)


def eer(pos, neg):
    pos, neg = np.sort(pos), np.sort(neg)
    best = (1, 0)
    for t in np.linspace(-0.2, 1, 601):
        far = (neg >= t).mean(); frr = (pos < t).mean()
        if abs(far - frr) < best[0]: best = (abs(far - frr), (far + frr) / 2, t)
    return best[1], best[2]


def trials(emb, spk, sel):
    """Все пары одиночных фраз внутри набора sel: косинусы своих и чужих."""
    ids = [i for i in range(len(spk)) if sel(spk[i])]
    E = emb[ids]; S = np.array([spk[i] for i in ids])
    C = E @ E.T; iu = np.triu_indices(len(ids), 1)
    same = S[iu[0]] == S[iu[1]]
    return C[iu][same], C[iu][~same]


def families(emb, spk, groups, n_enroll=3, rounds=300, seed=0):
    """«Семьи»: из групп людей (например, 3 ребёнка + 1 взрослый) профиль = среднее n_enroll фраз,
    остальные фразы опознаются. Возвращает (top1, косинус к своему, лучший чужой, отрыв)."""
    rnd = random.Random(seed); by = {}
    for i, s in enumerate(spk): by.setdefault(s, []).append(i)
    ok = tot = 0; own, other = [], []
    for _ in range(rounds):
        fam = []
        for g, k in groups:
            fam += rnd.sample(g, k)
        if len(set(fam)) < len(fam): continue
        prof, tests = {}, []
        for s in fam:
            u = by[s][:]; rnd.shuffle(u)
            p = emb[u[:n_enroll]].mean(0); prof[s] = p / np.linalg.norm(p)
            tests += [(s, i) for i in u[n_enroll:]]
        names = list(prof); P = np.stack([prof[s] for s in names])
        for s, i in tests:
            c = P @ emb[i]; j = int(np.argmax(c))
            ok += names[j] == s; tot += 1
            own.append(c[names.index(s)]); other.append(np.max(np.delete(c, names.index(s))))
    own, other = np.array(own), np.array(other)
    return ok / tot, own, other


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="/mnt/2tb/mathlock-ml/spk")
    ap.add_argument("--models", nargs="*")
    ap.add_argument("--tts", default="/mnt/2tb/mathlock-ml/ttscmp")
    ap.add_argument("--per-voice", type=int, default=40)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    so = os.path.join(a.root, "so")
    age = {}
    for part in ("train", "test"):
        for l in open(os.path.join(so, part, "spk2age")):
            s, y = l.split(); age[s] = int(y)
    wavs = sorted(glob.glob(os.path.join(so, "WAVE", "SPEAKER*", "*.WAV")))
    # дети 6–10 и взрослые ≥ 19; 12 фраз на человека — хватает и быстрее
    utt = []
    for s in sorted(age):
        g = "kid" if age[s] <= 10 else "adult" if age[s] >= 19 else None
        if g is None: continue
        fs = sorted(glob.glob(os.path.join(so, "WAVE", "SPEAKER%04d" % int(s), "*.WAV")))[:12]
        utt += [(f, "so:" + s, g) for f in fs]
    tts = []
    for d in ("out_ru3", "out_ru4"):
        for v in ("devochka", "malchik", "skazochnik", "feya", "velikan"):
            fs = sorted(glob.glob(os.path.join(a.tts, d, "*.%s.ogg" % v)))
            random.Random(1).shuffle(fs)
            tts += [(f, "tts:" + v, "tts") for f in fs[:a.per_voice // 2]]
    fed = [(f, "fedor", "fedor") for f in sorted(glob.glob(os.path.join(a.root, "fedor", "*.wav")))]
    allu = utt + tts + fed
    print("фраз: so %d (детей %d, взрослых %d), tts %d, fedor %d" % (
        len(utt), len({s for _, s, g in utt if g == "kid"}), len({s for _, s, g in utt if g == "adult"}), len(tts), len(fed)), flush=True)
    audio = [load(f) for f, _, _ in allu]
    print("средняя длительность: so %.1f с, tts %.1f с" % (
        np.mean([len(x) for x, u in zip(audio, allu) if u[2] in ("kid", "adult")]) / SR,
        np.mean([len(x) for x, u in zip(audio, allu) if u[2] == "tts"]) / SR), flush=True)
    spk = [s for _, s, _ in allu]; grp = {s: g for _, s, g in allu}
    kids = sorted({s for s in spk if grp[s] == "kid"}); adults = sorted({s for s in spk if grp[s] == "adult"})
    models = a.models or sorted(glob.glob(os.path.join(a.root, "models", "*.onnx")))
    report = {}
    for mp in models:
        if "nemo" in mp: continue
        m = Model(mp)
        t = time.time(); emb = np.stack([m.embed(x) for x in audio]); dt = time.time() - t
        tot_s = sum(len(x) for x in audio) / SR
        r = {"size_mb": os.path.getsize(mp) / 1e6, "rtf_4thr": dt / tot_s}
        p, n = trials(emb, spk, lambda s: grp[s] == "kid"); r["eer_kid_kid"], r["thr_kid"] = eer(p, n)
        r["kid_same"], r["kid_diff"] = float(np.median(p)), float(np.median(n))
        p, n = trials(emb, spk, lambda s: grp[s] == "adult"); r["eer_adult"], _ = eer(p, n)
        p, n = trials(emb, spk, lambda s: grp[s] in ("kid", "adult"))
        r["eer_all_so"], r["thr_all"] = eer(p, n)
        # взрослый ↔ ребёнок: только чужие пары
        ka = (emb[[i for i in range(len(spk)) if grp[spk[i]] == "kid"]] @ emb[[i for i in range(len(spk)) if grp[spk[i]] == "adult"]].T)
        r["kid_adult_p99"] = float(np.percentile(ka, 99)); r["kid_adult_median"] = float(np.median(ka))
        acc, own, oth = families(emb, spk, [(kids, 3), (adults, 2)])
        r["family_top1"] = acc; r["family_own_med"] = float(np.median(own)); r["family_other_med"] = float(np.median(oth))
        r["family_margin_p05"] = float(np.percentile(own - oth, 5))
        acc2, own2, oth2 = families(emb, spk, [(kids, 2)], rounds=600)
        r["two_kids_top1"] = acc2
        if tts:
            p, n = trials(emb, spk, lambda s: grp[s] == "tts"); r["eer_tts"], _ = eer(p, n)
            V = sorted({s for s in spk if grp[s] == "tts"}); cm = {}
            for v in V + (["fedor"] if fed else []):
                ev = emb[[i for i in range(len(spk)) if spk[i] == v]]
                cm[v] = ev
            r["tts_matrix"] = {u: {v: float(np.mean(cm[u] @ cm[v].T)) for v in cm} for u in cm}
        report[m.name] = r
        print("\n== %s  %.1f МБ, RTF %.4f (4 потока)" % (m.name, r["size_mb"], r["rtf_4thr"]))
        print("  EER дети↔дети %.1f%% (порог %.2f; свой %.2f, чужой %.2f), взрослые %.1f%%, все so %.1f%% (порог %.2f)" % (
            100 * r["eer_kid_kid"], r["thr_kid"], r["kid_same"], r["kid_diff"], 100 * r["eer_adult"], 100 * r["eer_all_so"], r["thr_all"]))
        print("  ребёнок↔взрослый: медиана %.2f, 99%% < %.2f" % (r["kid_adult_median"], r["kid_adult_p99"]))
        print("  «семья» 3 ребёнка + 2 взрослых, профиль из 3 фраз: верно %.1f%%, к своему %.2f, лучший чужой %.2f, отрыв p5 %.2f; 2 ребёнка: %.1f%%" % (
            100 * acc, r["family_own_med"], r["family_other_med"], r["family_margin_p05"], 100 * acc2))
        if tts:
            print("  tts EER %.1f%%" % (100 * r["eer_tts"]))
            ks = list(r["tts_matrix"])
            print("  " + " ".join("%10s" % k.replace("tts:", "") for k in [""] + ks))
            for u in ks: print("  %10s " % u.replace("tts:", "") + " ".join("%10.2f" % r["tts_matrix"][u][v] for v in ks))
        sys.stdout.flush()
    if a.out: json.dump(report, open(a.out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
