package dev.gubenkov.readbench;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Озвучка на самом телефоне через системный TTS-движок (по умолчанию RHVoice из F-Droid:
 * com.github.olga_yakovleva.rhvoice.android, русский голос скачивается в его приложении).
 * Ударения корпуса передаются знаком «+» перед ударной гласной. Это работает, только если в RHVoice
 * импортирован конфиг со строкой {@code stress_marker=+} (Настройки RHVoice → импорт файла конфигурации);
 * без него «+» ломает чтение. Маркер слушаются и Tatiana, и Aleksandr-hq (Фёдор на слух, 04.11); Tatiana звучит чище.
 */
final class LiveTts {
    static final String TAG = "ReadBench";
    static final String RHVOICE = "com.github.olga_yakovleva.rhvoice.android";

    private TextToSpeech tts;
    private volatile boolean ready;
    private final Map<String, Consumer<File>> pending = new HashMap<>();
    private final Map<String, Long> started = new HashMap<>();
    private final Map<String, File> files = new HashMap<>();
    final String engine;
    String voice = "?";

    LiveTts(Context ctx, String engine) {
        this.engine = engine;
        tts = new TextToSpeech(ctx, st -> {
            if (st != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS " + engine + ": init " + st); return; }
            int r = tts.setLanguage(new Locale("ru", "RU"));
            // выбор голоса: files/tts_voice.txt с именем голоса (список имён — в логе)
            StringBuilder all = new StringBuilder();
            try { for (android.speech.tts.Voice v : tts.getVoices()) if ("ru".equals(v.getLocale().getLanguage())) all.append(v.getName()).append(' '); } catch (Exception ignored) {}
            Log.i(TAG, "TTS голоса ru: " + all);
            try {
                File pref = new File(ctx.getExternalFilesDir(null), "tts_voice.txt");
                {
                    String want = pref.exists() ? new String(java.nio.file.Files.readAllBytes(pref.toPath())).trim() : DEFAULT_VOICE;
                    for (android.speech.tts.Voice v : tts.getVoices()) if (v.getName().equalsIgnoreCase(want)) { tts.setVoice(v); break; }
                }
            } catch (Exception e) { Log.w(TAG, "tts_voice.txt: " + e); }
            if (tts.getVoice() != null) voice = tts.getVoice().getName();
            ready = r >= TextToSpeech.LANG_AVAILABLE;
            Log.i(TAG, "TTS " + engine + ": язык ru → " + r + ", голос " + voice + (ready ? "" : " — русский голос не установлен"));
        }, engine);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finish(id, true); }
            @Override public void onError(String id) { finish(id, false); }
            @Override public void onError(String id, int code) { Log.w(TAG, "TTS ошибка " + code); finish(id, false); }
        });
    }

    boolean ready() { return ready; }

    static String plain(String text) { return text.replace(String.valueOf('́'), ""); }

    /** «Ма́ма» → «М+ама»: акут после гласной становится «+» перед ней. */
    static String plus(String text) {
        final char acute = 0x0301;
        StringBuilder b = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == acute) continue;
            if (i + 1 < text.length() && text.charAt(i + 1) == acute) b.append('+');
            b.append(c);
        }
        return b.toString();
    }

    /** Голос по умолчанию: качество звука лучше, чем у Aleksandr-hq (Фёдор на слух, 04.11). */
    static final String DEFAULT_VOICE = "Tatiana";

    /** Синтез в wav; {@code done} получает файл или null (вызывается в потоке TTS). */
    void synth(String text, File out, Consumer<File> done) {
        if (!ready) { done.accept(null); return; }
        String id = out.getName() + "@" + System.nanoTime();
        synchronized (this) { pending.put(id, done); started.put(id, System.nanoTime()); files.put(id, out); }
        int r = tts.synthesizeToFile(plus(text), new Bundle(), out, id);
        if (r != TextToSpeech.SUCCESS) finish(id, false);
    }

    private void finish(String id, boolean ok) {
        Consumer<File> c; long t0; File f;
        synchronized (this) { c = pending.remove(id); Long s = started.remove(id); t0 = s == null ? 0 : s; f = files.remove(id); }
        if (c == null) return;
        Log.i(TAG, String.format(Locale.ROOT, "TTS %s: %s за %d мс", voice, ok ? f.getName() + " " + f.length() / 1024 + " КБ" : "ошибка",
                (System.nanoTime() - t0) / 1000000));
        c.accept(ok ? f : null);
    }

    void shutdown() { tts.shutdown(); }
}
