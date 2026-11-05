# Синтетические ошибки чтения

1. `make_jobs.py content/ru/texts.jsonl jobs.jsonl 2` — варианты текстов с подставленными ошибками и метками по словам.
2. `synth.py jobs.jsonl out` на r99950x (`/mnt/2tb/mathlock-ml/seed-vc/.venv`, каталог `/mnt/2tb/mathlock-ml/synth_err`) —
   Silero v5.6 + жадная гипотеза GigaAM-v3 → `out/hyps.jsonl`, `out/<id>.ogg` (780 вариантов ≈ 80 мин).
3. `G.java` (классы модуля `reading` + org.json, JDK 17):
   `java [-Dtol=0.25] [-Dclean=hyps_ru6.jsonl] G texts.jsonl jobs.jsonl hyps.jsonl [промахи.tsv]` — доли по типам.
4. `iso.py` — те же неверные окончания отдельными словами: отличить правку грамматики распознаванием от неразборчивой озвучки.

Итоги 05.11 — docs/READING.md, «Синтетические ошибки чтения».
