"""Сборка таблицы гласных и наборов признаков из feats.pkl (extract.py).

Нормировка (повторяется в Java для наборов pros/spec/pos):
  * группа «слово» — гласные одного многосложного слова; «предложение» — гласные всех многосложных
    слов предложения (по . ! ? …) в этой записи;
  * просодия, 9 сырых признаков (common.RAW_NAMES): пропуски F0 заполняются средним по предложению
    (нет — 5.0); sd = std по предложению + 0.1*глобальная sd (из обучения, хранится в весах);
    признаки: (f - среднее слова)/sd, (f - среднее предложения)/sd, [f == максимум в слове];
  * спектр: DCT-II (ортонорм.) 64 лог-мел -> 20 коэффициентов; минус среднее предложения и минус среднее слова;
  * CTC: средние лог-вероятности по отрезку гласной для 9 гласных, пробела и пустого (11) минус среднее слова;
    «своя» лог-вероятность ожидаемой гласной и она же минус среднее слова;
  * позиция: one-hot (число гласных 2..5+, номер гласной), только для вариантов «+позиция».
"""
import pickle
import numpy as np
from common import VOWELS, VOW_IDS, VOCAB, BLANK

N_DCT = 20
CTC_COLS = VOW_IDS + [VOCAB.index(" "), BLANK]
POS_SLOTS = [(n, i) for n in range(2, 6) for i in range(n)]  # 14; nv>=5 -> 5, позиция с конца для хвоста


def dct_matrix(n_in=64, n_out=N_DCT):
    k = np.arange(n_out)[:, None]; n = np.arange(n_in)[None, :]
    M = np.cos(np.pi / n_in * (n + 0.5) * k) * np.sqrt(2.0 / n_in)
    M[0] /= np.sqrt(2.0)
    return M


DCT = dct_matrix()


def load(path):
    """path — feats.pkl или уже загруженный список записей extract.process."""
    D = pickle.load(open(path, "rb")) if isinstance(path, str) else path
    cols = {k: [] for k in ["file", "tid", "voice", "src", "word", "sent", "vi", "nv", "label", "w", "letter"]}
    raw, mel, ctc, enc = [], [], [], []
    for fi, r in enumerate(D):
        for x in r["rows"]:
            cols["file"].append(fi); cols["tid"].append(r["tid"]); cols["voice"].append(r["voice"])
            cols["src"].append(r["src"]); cols["word"].append(fi * 100000 + x["word"])
            cols["sent"].append(fi * 100000 + x["sent"]); cols["vi"].append(x["vi"]); cols["nv"].append(x["nv"])
            cols["label"].append(x["label"]); cols["w"].append(x["w"])
            cols["letter"].append([c for c in x["w"] if c in VOWELS][x["vi"]])
            raw.append(x["raw"]); mel.append(x["mel"]); ctc.append(x["ctc"])
        if r["enc"] is not None:
            enc.append(r["enc"])
    T = {k: np.array(v) for k, v in cols.items()}
    T["raw"] = np.array(raw, np.float64); T["mel"] = np.array(mel, np.float64)
    T["ctc"] = np.array(ctc, np.float64); T["enc"] = np.concatenate(enc).astype(np.float32)
    T["level"] = np.array([int(t.split("-")[1][1:]) for t in T["tid"]])
    _, T["wg"] = np.unique(T["word"], return_inverse=True)
    _, T["sg"] = np.unique(T["sent"], return_inverse=True)
    return T


def gmean(x, g, n=None):
    n = n or g.max() + 1
    x2 = x.reshape(len(x), -1)
    s = np.zeros((n, x2.shape[1])); np.add.at(s, g, x2)
    c = np.bincount(g, minlength=n)[:, None]
    return (s / np.maximum(c, 1))[g].reshape(x.shape)


def gmax(x, g):
    n = g.max() + 1
    m = np.full((n, x.shape[1]), -np.inf); np.maximum.at(m, g, x)
    return m[g]


def prosody(T, gsd=None):
    raw = T["raw"].copy(); sg, wg = T["sg"], T["wg"]
    nan = np.isnan(raw)
    fill = np.where(nan, 0.0, raw); cnt = gmean((~nan).astype(float), sg); sm = gmean(fill, sg)
    smean = np.where(cnt > 0, sm / np.maximum(cnt, 1e-9), 5.0)
    raw = np.where(nan, smean, raw)
    if gsd is None:
        gsd = raw.std(0)
    ms = gmean(raw, sg)
    sd = np.sqrt(gmean((raw - ms) ** 2, sg)) + 0.1 * gsd
    mw = gmean(raw, wg)
    ismax = (raw >= gmax(raw, wg) - 1e-12).astype(float)
    return np.hstack([(raw - mw) / sd, (raw - ms) / sd, ismax]), gsd


def spectral(T):
    C = T["mel"] @ DCT.T
    return np.hstack([C - gmean(C, T["sg"]), C - gmean(C, T["wg"])])


def ctcf(T):
    X = T["ctc"][:, CTC_COLS]
    own = T["ctc"][np.arange(len(X)), [VOCAB.index(c) for c in T["letter"]]][:, None]
    return np.hstack([X - gmean(X, T["wg"]), own, own - gmean(own, T["wg"])])


def position(T):
    nv = np.minimum(T["nv"], 5); vi = T["vi"].copy()
    tail = T["nv"] > 5
    vi[tail] = np.where(T["vi"][tail] >= 2, np.maximum(T["vi"][tail] - (T["nv"][tail] - 5), 2), T["vi"][tail])
    P = np.zeros((len(nv), len(POS_SLOTS)))
    for j, s in enumerate(POS_SLOTS):
        P[:, j] = (nv == s[0]) & (vi == s[1])
    return P


def encoder(T):
    return T["enc"] - gmean(T["enc"].astype(np.float64), T["wg"]).astype(np.float32)


def blocks(T, gsd=None):
    """Все блоки признаков (gsd просодии — по всем данным; это только масштаб, метки не используются)."""
    p, gsd = prosody(T, gsd)
    return dict(pros=p, spec=spectral(T), ctc=ctcf(T), pos=position(T), enc=encoder(T)), gsd
