#!/bin/sh
# Seed-VC: эталоны с верными ударениями (Silero/RHVoice/Vosk) -> голоса персонажей. 4 источника x 5 голосов.
cd /mnt/2tb/mathlock-ml/seed-vc || exit 1
export HF_HUB_DISABLE_XET=1
OUT=../ttscmp/vc; mkdir -p $OUT/tmp
for src in silero-baya silero-aidar rhvoice-aleksandr-hq vosk-s2; do
  for tgt in skazochnik devochka malchik velikan feya; do
    rm -f $OUT/tmp/*.wav
    .venv/bin/python inference.py --source ../ttscmp/ref_s/$src.wav --target ../tts/voices/$tgt.wav \
      --output $OUT/tmp --diffusion-steps 50 --inference-cfg-rate 0.7 --fp16 False > $OUT/log_$src-$tgt.txt 2>&1 \
      || { echo "ОШИБКА $src -> $tgt, см. $OUT/log_$src-$tgt.txt"; tail -3 $OUT/log_$src-$tgt.txt; continue; }
    mv "$(ls $OUT/tmp/*.wav | head -1)" $OUT/V.$src-to-$tgt.wav && echo "готово $src -> $tgt"
  done
done
ls $OUT/V.*.wav | wc -l
