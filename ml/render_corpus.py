"""Озвучка корпуса: texts.jsonl -> jobs (клон голосов персонажей) -> tts_gen -> score -> лучший кандидат в ogg/opus.
Шаги: python render_corpus.py jobs texts.jsonl voices.json jobs.jsonl
      python render_corpus.py pick jobs.jsonl cand_dir scores.json out_dir"""
import json, re, subprocess, sys
from pathlib import Path

def jobs(texts, voices, out):
    V = json.load(open(voices))
    with open(out, "w") as f:
        for line in Path(texts).read_text().splitlines():
            if not line.strip(): continue
            t = json.loads(line)
            # акут оставляем (ASR не ломает); на слух решается, помогает ли ударению
            for vid, v in V.items():
                j = {"id": f"{t['id']}.{vid}", "text": t["text"], "ref_audio": v["ref_audio"], "ref_text": v["ref_text"], "n": 3}
                if t.get("level", 3) <= 2: j["speed"] = 0.8
                f.write(json.dumps(j, ensure_ascii=False) + "\n")

def pick(jobsf, cand, scoresf, out):
    sc = json.load(open(scoresf)); out = Path(out); out.mkdir(parents=True, exist_ok=True)
    bad = []
    for line in Path(jobsf).read_text().splitlines():
        j = json.loads(line); c = sorted((v, k) for k, v in sc.items() if k.startswith(j["id"] + "."))
        if not c: bad.append((j["id"], None)); continue
        best, name = c[0]
        if best > 0.08: bad.append((j["id"], best))
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"{cand}/{name}", "-af",
                        "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-18",
                        "-c:a", "libopus", "-b:a", "32k", str(out / f"{j['id']}.ogg")], check=True)
    print("плохие (CER>0.08 или нет кандидатов):", bad)

if __name__ == "__main__":
    {"jobs": jobs, "pick": pick}[sys.argv[1]](*sys.argv[2:])
