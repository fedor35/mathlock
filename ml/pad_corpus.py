"""Собирает исправленный корпус out_ru6: out_ru3 (L1–L5) + out_ru4 (L6–L9), поверх — out_fix (перегенерированные).
Каждому ogg добавляется 0,3 с тишины в начале и конце (без неё первый согласный срезан и первое слово теряется),
время слов в json сдвигается на +0,3 с."""
import glob, json, os, shutil, subprocess, sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
PAD = 0.3
src = {}
for d in ["out_ru3", "out_ru4", "out_fix"]:
    for f in glob.glob(f"{d}/*.ogg"): src[Path(f).name] = f
os.makedirs("out_ru6", exist_ok=True)
def one(name):
    f = src[name]; dst = f"out_ru6/{name}"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f, "-af", f"adelay={int(PAD*1000)}:all=1,apad=pad_dur={PAD}",
                    "-c:a", "libopus", "-b:a", "40k", dst], check=True)
    j = json.load(open(f[:-4] + ".json"))
    j["words"] = [[round(a + PAD, 3), round(b + PAD, 3)] for a, b in j["words"]]
    json.dump(j, open(dst[:-4] + ".json", "w"), ensure_ascii=False)
with ThreadPoolExecutor(16) as ex: list(ex.map(one, sorted(src)))
print(len(src), "файлов")
