#!/usr/bin/env python3
"""Фикстура для android/reading FbankTest: короткий синтезированный клип (голос персонажа из озвучки, не человек)
с тишиной и слабым шумом по краям, эталонный Kaldi fbank (kaldi-native-fbank), маска речи и эмбеддинг CAM++.

python -I ml/speaker_fixture.py SRC.ogg MODEL.onnx OUT_DIR
Пишет в OUT_DIR: spk_clip.wav (16 кГц, 16 бит), spk_fbank.f32 (T×80, без вычитания среднего),
spk_voiced.txt (маска 0/1), spk_emb.f32 (192, нормирован).
"""
import os, struct, sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from speaker_eval import Model, fbank, load, voiced, SR  # noqa: E402


def main():
    src, model, out = sys.argv[1:4]
    x = load(src)[: int(3.5 * SR)]
    rnd = np.random.default_rng(7)
    pad = lambda n: rnd.normal(0, 0.0005, n)  # шум около −66 дБFS
    x = np.concatenate([pad(int(0.6 * SR)), x, pad(int(0.5 * SR))])
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    x = pcm.astype(np.float32) / 32768  # ровно то, что прочитает Java
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "spk_clip.wav"), "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 36 + 2 * len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, SR, 2 * SR, 2, 16)
                + b"data" + struct.pack("<I", 2 * len(pcm)) + pcm.tobytes())
    fb = fbank(x, "povey", 1.0).astype("<f4")
    fb.tofile(os.path.join(out, "spk_fbank.f32"))
    m = voiced(x)
    open(os.path.join(out, "spk_voiced.txt"), "w").write("".join("1" if v else "0" for v in m))
    e = Model(model).embed(x).astype("<f4"); e.tofile(os.path.join(out, "spk_emb.f32"))
    print("кадров %d, речь %d, эмбеддинг %s…" % (len(fb), m.sum(), np.round(e[:4], 4)))


if __name__ == "__main__":
    main()
