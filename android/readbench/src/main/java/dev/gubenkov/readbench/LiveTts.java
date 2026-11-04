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
 * Акуты убираются: RHVoice ставит ударения сам, а разметка «+» его ломает (проверено на r99950x).
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

    /** Синтез в wav; {@code done} получает файл или null (вызывается в потоке TTS). */
    void synth(String text, File out, Consumer<File> done) {
        if (!ready) { done.accept(null); return; }
        String id = out.getName() + "@" + System.nanoTime();
        synchronized (this) { pending.put(id, done); started.put(id, System.nanoTime()); files.put(id, out); }
        int r = tts.synthesizeToFile(plain(text), new Bundle(), out, id);
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
