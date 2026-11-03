#!/bin/sh
# Корпус v2: текст без акутов (OmniVoice их не слушается), 6 кандидатов, отбор CER + ударения по эталонам.
cd /mnt/2tb/mathlock-ml/tts
HF_HUB_CACHE=/mnt/2tb/omnivoice/hf-cache /mnt/2tb/omnivoice/OmniVoice/.venv/bin/python -I tts_gen.py jobs_ru2.jsonl cand_ru2 64 > gen_ru2.log 2>&1
until grep -q "^done" ../ttscmp/refs_ru.log; do sleep 60; done
/mnt/2tb/asr-tools/gigaam-venv/bin/python -I pick_stress.py texts_ru.jsonl jobs_ru2.jsonl cand_ru2 ../ttscmp/refs_ru out_ru2 > pick_ru2.log 2>&1
/mnt/2tb/asr-tools/gigaam-venv/bin/python -I ../scripts/word_times.py texts_ru.jsonl out_ru2 > wt_ru2.log 2>&1
echo done >> pick_ru2.log
