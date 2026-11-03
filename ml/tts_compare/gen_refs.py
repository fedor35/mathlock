import subprocess, torch, soundfile as sf, unicodedata
from silero import silero_tts
from vosk_tts import Model, Synth
t = "Белка спустилась, взяла орех и спрятала его в дупло. — Она запасает еду на зиму, — объяснил папа."
m, _ = silero_tts(language="ru", speaker="v5_6_ru")
for s in ["aidar","baya","kseniya","xenia","eugene"]:
    sf.write(f"ref_s/silero-{s}.wav", m.apply_tts(text=t, speaker=s, sample_rate=48000).numpy(), 48000)
for v in ["aleksandr-hq","anna","elena","irina","mikhail"]:
    subprocess.run(["RHVoice-test","-p",v,"-o",f"ref_s/rhvoice-{v}.wav"], input=t.encode(), check=True)
sy = Synth(Model(model_path="/home/fedor/.cache/vosk/vosk-model-tts-ru-0.9-multi"))
for i in range(5): sy.synth(t, f"ref_s/vosk-s{i}.wav", speaker_id=i)
