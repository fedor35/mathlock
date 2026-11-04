#!/bin/sh
# Пакет чтения для «Таблицы» (этап 5): папка, которую родитель копирует на телефон по кабелю (в «Загрузки»)
# или флешкой и выбирает в настройках кнопкой «Установить пакет чтения из папки…». Интернет не нужен.
# Положить ту же папку в android/app/reading-pack/ — пакет войдёт в APK (кнопка «…из приложения»).
#
#   ml/make_reading_pack.sh <папка моделей> <папка озвучки tts или -> <выход>
#
# Модели: gigaam_ctc_T1600_qdq.onnx (NPU, HTP v69 — Xiaomi 13 Lite, ml/quant_qdq.py) и/или gigaam_ctc_int8.onnx
# (CPU-запасной), CAM++ 3dspeaker_…onnx (голоса, необязательно). fp32-модели (880 МБ) не берутся без PACK_FP32=1.
# Озвучка: tts/<id>.<голос>.ogg|wav (+ .json разметки слов) — как в files/tts/ стенда readbench.
set -eu
[ $# -eq 3 ] || { sed -n 2,10p "$0"; exit 1; }
models=$1; tts=$2; out=$3
here=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$out"
cp "$here/content/ru/texts.jsonl" "$out/"
cp "$here/ml/gigaam3-ctc-ru.model.json" "$out/"
n=0
for f in gigaam_ctc_T1600_qdq.onnx gigaam_ctc_int8.onnx 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx; do
    if [ -f "$models/$f" ]; then cp "$models/$f" "$out/"; n=$((n + 1)); else echo "нет $models/$f — пропускаю"; fi
done
if [ "${PACK_FP32:-0}" = 1 ]; then
    for f in gigaam_ctc_T1600_fp32.onnx gigaam_ctc_fp32.onnx; do [ -f "$models/$f" ] && cp "$models/$f" "$out/"; done
fi
if [ "$tts" != - ] && [ -d "$tts" ]; then
    mkdir -p "$out/tts"
    find "$tts" -maxdepth 1 -type f \( -name '*.ogg' -o -name '*.wav' -o -name '*.json' \) -exec cp {} "$out/tts/" \;
fi
ls "$out"/*.onnx >/dev/null 2>&1 || echo "ВНИМАНИЕ: в пакете нет ни одной модели — чтение будет недоступно"
du -sh "$out"
