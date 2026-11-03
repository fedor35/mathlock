# Сравнение офлайн-TTS (RU, KK)

Одни и те же тексты (ru-L1-001, ru-L3-005, ru-L5-003 и две казахские фразы) озвучены всеми найденными движками,
которые могут работать без интернета. Разборчивость считается CER: GigaAM для RU, Whisper large-v3 для KK.
Скорость (×RT) замерена на 2 потоках CPU Ryzen 9950X. Генерация шла на r99950x в `/mnt/2tb/mathlock-ml/ttscmp`,
модели и образцы в репозиторий не входят.

| Скрипт | Что делает |
|---|---|
| gen_sherpa.py | Piper (piper-tts), MMS-rus и Supertonic 3 (sherpa-onnx 2.0.1) |
| gen_silero.py | Silero v5.6 ru и v5 CIS (казахские голоса) |
| gen_misc.py / gen_vosk.py | MMS-kaz (transformers) и Vosk TTS 0.9 multi |
| gen_rhv.py | RHVoice (пакеты Manjaro) |
| score_cmp.py / score_kk.py | CER для RU и KK, плюс лучший кандидат OmniVoice |

Итоги 03.11.2026:
- **RU на телефоне.** Silero (авто-ударения, омографы) и Piper (готов в sherpa-onnx для Android) дают CER около 0 при
  ×RT 0,02. У Supertonic 3 два голоса из четырёх ошибаются на «Ма́ма. Ра́ма. Ми́ла.».
- **Что отпало.** RHVoice ломается на разметке «+» (CER 0,2–0,5). Vosk 0.9 весит около 900 МБ. MMS путает короткие фразы.
- **KK.** Лучше всех Silero (7 голосов, kaz_zhadyra и kaz_zhazira под MIT) и OmniVoice, следом Piper issai-high.
  Piper x_low и MMS заметно хуже.
- **OmniVoice остаётся сервером.** Даже на GPU синтез идёт в 1,6 раза медленнее реального времени.

Нерешённое: sherpa-onnx 2.0.1 из PyPI не фонетизирует текст для Piper (нет espeak), поэтому Piper шёл через
`piper-tts`. Замер на самом телефоне не сделан: запуск скачанного бинарника на Samsung заблокирован автомодом.
