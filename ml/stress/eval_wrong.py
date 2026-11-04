"""Контроль «звук или слово»: модели учатся на обычной озвучке (ударения верные), проверяются на озвучке
с перенесёнными ударениями (gen_wrong.py). Если модель «знает слово», на изменённых словах она назовёт
словарное ударение, а не произнесённое.
  python eval_wrong.py feats.pkl wrong_feats.pkl wrong/texts_wrong.jsonl out.json
Режимы обучения: all — вся обычная озвучка (те же тексты в обучении: худший случай для «заучивания»);
textcv — 5 фолдов по текстам (исходник контрольного текста не в обучении)."""
import json, pickle, sys, os, tempfile
import numpy as np
from sklearn.model_selection import GroupKFold

feats_pkl, wrong_pkl, wrong_texts, out_json = sys.argv[1:5]
MODE = sys.argv[5] if len(sys.argv) > 5 else "full"  # full | roc (только textcv, несколько моделей, кривая тревоги)
D = pickle.load(open(feats_pkl, "rb")); Wd = pickle.load(open(wrong_pkl, "rb"))
for r in Wd:
    r["src"] = r["src"] + "-w"
tmp = os.path.join(tempfile.mkdtemp(), "comb.pkl"); pickle.dump(D + Wd, open(tmp, "wb")); del D, Wd
sys.argv = ["train.py", tmp]
import train as tr
from common import parse_words

orig = {}
for l in open(wrong_texts):
    j = json.loads(l); orig[j["id"]] = [lab for _, lab, _ in parse_words(j["orig"])]
first = tr.first; ctl = np.char.endswith(tr.w_src.astype(str), "-w")
widx = tr.T["word"][first] % 100000
w_orig = np.array([orig[t][i] if c else -1 for t, i, c in zip(tr.w_tid, widx, ctl)])
base_tid = np.array([t[:-1] if c else t for t, c in zip(tr.w_tid, ctl)])
ok_c = ctl & tr.w_has & (w_orig >= 0)
changed = ok_c & (w_orig != tr.w_lab)
print("контрольных слов", ok_c.sum(), "из них с перенесённым ударением", changed.sum(), flush=True)

SETS = ["pros", "pros+mel", "pros+spec", "pros+spec+pos", "spec", "ctc", "enc"]
MOD = {"gbm": tr.GBM, "mlp": lambda: tr.Listwise(32, 1e-3, 600, 0.01), "logreg": lambda: tr.Listwise(0, 1e-3, 400, 0.02)}
PLAN = [(s, "gbm") for s in SETS if s != "enc"] + [(s, "mlp") for s in ["pros+mel", "pros+spec"]] + [("enc", "logreg")]
MOD["gbm_s"] = lambda: tr.GBM(150, 15)
if MODE == "roc":
    PLAN = [("pros+spec", "gbm"), ("pros+spec", "gbm_s"), ("pros+mel", "gbm"), ("pros+spec", "mlp"), ("enc", "logreg")]


def p_dict(model, X, ws):
    """вероятность (softmax по гласным слова) словарной ударной гласной"""
    M = tr.word_matrix(ws); rows = M[M >= 0]
    sc = np.full(M.shape, -np.inf); sc[M >= 0] = model.score(X, rows)
    p = np.exp(sc - sc.max(1, keepdims=True)); p /= p.sum(1, keepdims=True)
    return p[np.arange(len(ws)), w_orig[ws]]


def roc(pd, ws):
    """тревога «ударение не там», если p(словарное) < порог: TPR при заданной доле ложных тревог"""
    ch = w_orig[ws] != tr.w_lab[ws]
    out = {}
    for fpr in [0.01, 0.02, 0.05, 0.10, 0.20]:
        thr = np.quantile(pd[~ch], fpr)
        out[f"{fpr:.2f}"] = dict(thr=float(thr), tpr=float((pd[ch] < thr).mean()))
    return out
normal = tr.w_has & ~ctl
res = {}


def summary(pred, ws):
    lab, org, src = tr.w_lab[ws], w_orig[ws], tr.w_src[ws]
    ch = org != lab
    out = {}
    for name, m in [("все", np.ones(len(ws), bool)), ("silero", src == "silero-w"), ("vosk", src == "vosk-w")]:
        c, u = ch & m, ~ch & m
        out[name] = dict(n_changed=int(c.sum()), n_same=int(u.sum()),
                         acc_same=float((pred[u] == lab[u]).mean()),           # ударение как в словаре — верно услышано
                         heard_changed=float((pred[c] == lab[c]).mean()),       # перенесённое — услышано, где сказано
                         dict_changed=float((pred[c] == org[c]).mean()),        # перенесённое — названо словарное (ошибка)
                         flag_tpr=float((pred[c] != org[c]).mean()),            # «ударение не там» замечено
                         flag_fpr=float((pred[u] != org[u]).mean()))            # ложная тревога на верных
    return out


Xs = {s: np.hstack([tr.B[b] for b in tr.SETS[s]]).astype(np.float32) for s in SETS}
tids = np.array(sorted(set(base_tid[ok_c])))
folds = [tids[te] for _, te in GroupKFold(5).split(tids, groups=tids)]
for sname, mname in PLAN:
    X = Xs[sname]
    if MODE != "roc":
        m = MOD[mname]().fit(X, np.flatnonzero(normal))
        ws = np.flatnonzero(ok_c)
        res[f"all|{mname}:{sname}"] = summary(tr.predict(m, X, ws), ws)
    preds, wss, pds = [], [], []
    for ft in folds:
        tr_ws = np.flatnonzero(normal & ~np.isin(base_tid, ft)); te_ws = np.flatnonzero(ok_c & np.isin(base_tid, ft))
        m = MOD[mname]().fit(X, tr_ws); preds.append(tr.predict(m, X, te_ws)); wss.append(te_ws); pds.append(p_dict(m, X, te_ws))
    res[f"textcv|{mname}:{sname}"] = summary(np.concatenate(preds), np.concatenate(wss))
    res[f"textcv|{mname}:{sname}"]["roc"] = rc = roc(np.concatenate(pds), np.concatenate(wss))
    print(f"textcv|{mname}:{sname} тревога при доле ложных:", ", ".join(f"{k} -> TPR {v['tpr']:.3f}" for k, v in rc.items()), flush=True)
    for k in ([f"all|{mname}:{sname}"] if MODE != "roc" else []) + [f"textcv|{mname}:{sname}"]:
        a = res[k]["все"]
        print(f"{k:28s} верные: {a['acc_same']:.3f}  перенесённые: услышано {a['heard_changed']:.3f}, словарное {a['dict_changed']:.3f}"
              f"  | тревога: TPR {a['flag_tpr']:.3f} FPR {a['flag_fpr']:.3f}  (silero услышано {res[k]['silero']['heard_changed']:.3f}, vosk {res[k]['vosk']['heard_changed']:.3f})", flush=True)
# базовые
ws = np.flatnonzero(ok_c); rng = np.random.default_rng(0)
res["случайная"] = summary((rng.random(len(ws)) * tr.w_nv[ws]).astype(int), ws)
res["формула (прототип)"] = summary(tr.formula(ws), ws)
res["словарь (всегда верное)"] = summary(w_orig[ws], ws)
for k in ["случайная", "формула (прототип)", "словарь (всегда верное)"]:
    a = res[k]["все"]; print(f"{k:28s} верные: {a['acc_same']:.3f}  перенесённые: услышано {a['heard_changed']:.3f}, словарное {a['dict_changed']:.3f}  | TPR {a['flag_tpr']:.3f} FPR {a['flag_fpr']:.3f}")
json.dump(res, open(out_json, "w"), ensure_ascii=False, indent=1)
