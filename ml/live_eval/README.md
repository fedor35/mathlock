# Стенд живых записей

Записи и журналы семьи в репозиторий не входят: ноутбук `~/mathlock-live/<дата>/`, r99950x `/mnt/2tb/mathlock-ml/live/`.

1. Забрать с телефона `files/recordings`, `progress-*.tsv`, `profiles/` (adb pull).
2. `whisper_refs.py` на r99950x — независимая расшифровка (faster-whisper large-v3, без подсказки текста);
   LD_LIBRARY_PATH на `venv/.../nvidia/*/lib`, как в `/mnt/2tb/asr-tools/transcribe`.
3. `compare.py <каталог>` — по чтецам: красные у приложения, которые Whisper слышит верно (кандидаты в ложные
   красные), и зелёные, где Whisper слышит другое слово.
4. Java (классы модуля `reading` + org.json; `Reds <texts> <hyps>` — красные слова на озвучке по текстам):
   - `E` — пересчёт сверки по сохранённым гипотезам: по словам состояние (записанное/сейчас), кусок гипотезы, цена;
   - `F <texts.jsonl> <hyps.jsonl>` — доля красных слов без и со `strictEndings` по уровням; на `hyps_ru6.jsonl`
     (жадные гипотезы GigaAM на эталонной озвучке, `ml/greedy_hyps.py`) это доля ложных красных.

5. Поток по звуку (как в приложении, с оценкой красных по звуку):
   - `dump_logits.py <out> <wav…>` на r99950x (`/mnt/2tb/asr-tools/gigaam-venv`) — лог-вероятности GigaAM-v3 ctc
     окнами StreamingRecognizer, `<out>/<запись>.f32` (T×34);
   - `Sim2 <recordings> <lp> [-noac] [-nocl]` — каждая остановка на красном, время на красном по записям и всего;
     `-Dwordscore.accept=…` — порог звука; `GopNear <recordings> <lp>` — как часто «соседняя» форма проходит порог.

Итоги 11.11 (записи 05–10.11, 0.4.1 → 0.4.2) — docs/READING.md, «Живые записи сына 05–10.11».
Итоги 05.11 (ночь 04→05.11, 2839 слов в 40 засчитанных попытках) — в docs/READING.md, «Сверка на живых записях».
