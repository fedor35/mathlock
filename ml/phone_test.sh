#!/bin/sh
# Проверка «Таблицы» на телефоне без человека: отладочная копия dev.gubenkov.mathlock.dev (рядом с рабочей,
# свои файлы, без блокировки и службы) читает запись вместо микрофона в реальном темпе, экран снимается по ходу.
#
#   ml/phone_test.sh install                     — собрать debug -PdevCopy и поставить (MIUI: «Запомнить выбор» уже нажат)
#   ml/phone_test.sh read <ogg|wav> <text_id> [с] — прочитать запись как текст text_id, снимки каждые 10 с в $OUT
#   ml/phone_test.sh shot [имя]                  — снимок экрана
#
# Телефон: PHONE (по умолчанию 13 Lite по Wi-Fi). Экран на время теста держится включённым (svc power stayon true)
# и возвращается как был (stay_on_while_plugged_in=0: на зарядке экран блокировки не должен гореть, 11.11).
# Пакет .dev: модель CPU int8, тексты и озвучка копируются из рабочей files/ (cp на телефоне, без передачи по сети).
set -eu
PHONE=${PHONE:-192.168.101.87:5555}
OUT=${OUT:-/tmp/mathlock-phone}
PKG=dev.gubenkov.mathlock.dev
F=/sdcard/Android/data/$PKG/files
M=/sdcard/Android/data/dev.gubenkov.mathlock/files
here=$(cd "$(dirname "$0")/.." && pwd)
a() { adb -s "$PHONE" "$@"; }
mkdir -p "$OUT"

shot() { a exec-out screencap -p > "$OUT/$1.png"; echo "$OUT/$1.png"; }

top() { a shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | tr -d '\r'; }

# Рабочая «Таблица» закрыла экран (блокировка после гашения): родительский код на экране примеров.
# Код — только локально (~/.secrets/mathlock-android/parent-code.txt), репозиторий публичный.
unlock_real() {
    top | grep -q "dev.gubenkov.mathlock/.QuizActivity" || return 0
    code=$(cat "$HOME/.secrets/mathlock-android/parent-code.txt")
    a shell input swipe 540 104 540 104 1200      # чтение: долгое нажатие на строку уровня → примеры
    sleep 2
    for d in $(echo "$code" | sed 's/./& /g'); do   # клавиатура примеров 1080×2400, 13 Lite
        case $d in 1) xy="263 1371";; 2) xy="539 1371";; 3) xy="814 1371";; 4) xy="263 1647";; 5) xy="539 1647";;
                   6) xy="814 1647";; 7) xy="263 1922";; 8) xy="539 1922";; 9) xy="814 1922";; 0) xy="539 2198";; esac
        a shell input tap $xy; sleep 0.3
    done
    sleep 2
    top | grep -q "dev.gubenkov.mathlock/.QuizActivity" && { echo "рабочая блокировка не снялась"; shot locked; exit 1; } || true
}

wake() {
    # из AOD (Dozing) KEYCODE_WAKEUP не будит — только кнопка питания; бодрствующий экран ею бы погасили
    a shell dumpsys power | grep -q "mWakefulness=Awake" || a shell input keyevent KEYCODE_POWER
    sleep 2
    # системная блокировка без кода — смахнуть; если на экране «Таблица» (блокировка примерами/чтением), смахивание ничего не ломает
    a shell 'dumpsys window | grep -q "isKeyguardShowing=true"' && a shell input swipe 540 1900 540 500 250 || true
    sleep 1
    unlock_real
}

case "${1:-}" in
install)
    (cd "$here/android" && JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk} ./gradlew -q :app:assembleDebug -PdevCopy)
    a install -r "$here/android/app/build/outputs/apk/debug/app-debug.apk"
    a shell "mkdir -p $F/tts; for x in gigaam_ctc_int8.onnx gigaam3-ctc-ru.model.json; do [ -s $F/\$x ] || cp $M/\$x $F/; done; cp $M/texts.jsonl $F/; chmod 666 $F/*.* 2>/dev/null; true"
    a shell pm grant $PKG android.permission.RECORD_AUDIO
    ;;
read)
    src=$2; id=$3; secs=${4:-0}
    wav="$OUT/$(basename "${src%.*}").wav"
    ffmpeg -loglevel error -y -i "$src" -ar 16000 -ac 1 -c:a pcm_s16le "$wav"
    [ "$secs" -gt 0 ] || secs=$(( $(stat -c %s "$wav") / 32000 + 25 ))
    a push "$wav" "$F/test.wav" >/dev/null
    a shell "cp $M/texts.jsonl $F/texts.jsonl; chmod 666 $F/*.* 2>/dev/null; true"
    prev=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
    a shell svc power stayon true
    trap 'a shell settings put global stay_on_while_plugged_in "${prev:-0}"' EXIT
    wake
    a shell am start -n $PKG/dev.gubenkov.mathlock.MainActivity --es wav "$F/test.wav" --es text_id "$id" >/dev/null
    sleep 5; a shell input tap 540 2156          # микрофон (портрет 1080×2400): запись из файла идёт по нажатию, как у ребёнка
    t=0
    while [ $t -le "$secs" ]; do sleep 10; t=$((t + 10)); shot "$id-$t" >/dev/null; done
    ls "$OUT"/"$id"-*.png
    ;;
shot) wake; shot "${2:-shot}" ;;
*) sed -n 2,12p "$0"; exit 1 ;;
esac
