package dev.gubenkov.mathlock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.List;

import dev.gubenkov.reading.AsrModelSpec;
import dev.gubenkov.reading.CtcModel;
import dev.gubenkov.reading.SpeakerModel;

/**
 * Модели чтения на процесс: GigaAM (NPU или CPU) и CAM++ (голоса). Грузятся при первом задании чтения,
 * отпускаются через минуту после последнего использования — служба блокировки живёт постоянно и не должна
 * держать сотни мегабайт.
 * <p>
 * Страховка NPU: перед созданием сессии QNN кладётся метка {@code qnn/loading}; если процесс упал внутри
 * нативного кода, при следующем старте метка найдётся — NPU помечается сломанным (Prefs.htpBroken), дальше CPU.
 */
final class ReadingEngine {
    static final String TAG = "MathLockRead";
    private static final long IDLE_CLOSE_MS = 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static volatile CtcModel model;
    private static volatile SpeakerModel speaker;
    private static volatile String describe = "";
    private static int users;
    /** Загрузка (и компиляция под NPU — минуты) идёт под этим замком; главный поток его не ждёт, только tryLock. */
    private static final java.util.concurrent.locks.ReentrantLock load = new java.util.concurrent.locks.ReentrantLock();
    private static final Runnable closer = ReadingEngine::closeIfIdle;

    private ReadingEngine() {}

    static File dataDir(Context c) { return c.getExternalFilesDir(null); }
    static File qnnDir(Context c) { File d = new File(c.getFilesDir(), "qnn"); d.mkdirs(); return d; }
    static ReadingPack pack(Context c) { checkCrashMarker(c); return ReadingPack.at(dataDir(c), qnnDir(c)); }

    private static volatile boolean markerChecked;

    /**
     * Прошлая загрузка NPU не дошла до конца (процесс умер) — NPU больше не трогаем. Проверяется один раз на процесс:
     * метка живой загрузки (компиляция у родителя идёт минуты) — не след падения.
     */
    static synchronized void checkCrashMarker(Context c) {
        if (markerChecked) return;
        markerChecked = true;
        File m = new File(qnnDir(c), "loading");
        if (m.exists()) {
            Log.w(TAG, "метка загрузки NPU осталась от упавшего процесса — дальше только CPU");
            new Prefs(c).setHtpBroken(true);
            m.delete();
        }
    }

    static synchronized void acquire() { users++; main.removeCallbacks(closer); }

    static synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0) { main.removeCallbacks(closer); main.postDelayed(closer, IDLE_CLOSE_MS); }
    }

    private static void closeIfIdle() {
        synchronized (ReadingEngine.class) { if (users > 0) return; }
        if (!load.tryLock()) { main.postDelayed(closer, IDLE_CLOSE_MS); return; }
        try { closeNow(); } finally { load.unlock(); }
    }

    private static void closeNow() {
        try { if (model != null) model.close(); } catch (Exception ignored) { }
        try { if (speaker != null) speaker.close(); } catch (Exception ignored) { }
        if (model != null) Log.i(TAG, "модели выгружены");
        model = null; speaker = null;
    }

    static String describe() { return describe; }
    static SpeakerModel speaker() { return speaker; }

    /**
     * Модель по плану ReadingPack: первая, что загрузилась. Медленная и долгая — только из рабочего потока.
     * @param allowCompile компилировать под NPU, если контекста ещё нет (минуты; кнопка «Подготовить» у родителя)
     * @return модель или null — читать нечем (тогда экран блокировки даёт примеры)
     */
    static CtcModel model(Context c, boolean allowCompile) {
        load.lock();
        try { return loadLocked(c, allowCompile); } finally { load.unlock(); }
    }

    private static CtcModel loadLocked(Context c, boolean allowCompile) {
        if (model != null) return model;
        Prefs prefs = new Prefs(c);
        ReadingPack p = pack(c);
        List<ReadingPack.Candidate> plan = p.plan(prefs.htpBroken(), allowCompile);
        AsrModelSpec spec = spec(p);
        for (ReadingPack.Candidate k : plan) {
            File marker = new File(qnnDir(c), "loading");
            long t = System.nanoTime();
            try {
                if (k.htp) java.nio.file.Files.write(marker.toPath(), k.file.getBytes());
                String path = new File(p.dir, k.file).getPath();
                CtcModel m = spec != null
                        ? new CtcModel(path, k.htp ? CtcModel.Backend.QNN_HTP : CtcModel.Backend.CPU, k.htp ? spec.staticFrames : 0,
                                k.htp ? qnnDir(c).getPath() : null, spec.vocab, spec.blank)
                        : new CtcModel(path, k.htp ? CtcModel.Backend.QNN_HTP : CtcModel.Backend.CPU, k.htp ? p.staticFrames : 0,
                                k.htp ? qnnDir(c).getPath() : null);
                marker.delete();
                model = m;
                describe = k + " за " + (System.nanoTime() - t) / 1_000_000 + " мс";
                Log.i(TAG, "модель: " + describe);
                break;
            } catch (Throwable e) {
                marker.delete();
                Log.e(TAG, "модель " + k + " не загрузилась", e);
                if (k.htp) {
                    // битый или чужой (другая версия ORT/QNN) контекст — убрать, чтобы «Подготовить» собрал заново
                    File ctx = new File(qnnDir(c), ReadingPack.ctxName(k.file));
                    if (k.cached) { ctx.delete(); deleteBins(qnnDir(c), k.file); }
                    else if (allowCompile) prefs.setHtpBroken(true);   // компиляция не прошла — этот NPU не тянет модель
                }
            }
        }
        if (model != null && speaker == null && p.speaker()) {
            try { speaker = new SpeakerModel(new File(p.dir, ReadingPack.SPEAKER).getPath()); }
            catch (Throwable e) { Log.e(TAG, "голосовая модель", e); }
        }
        return model;
    }

    private static void deleteBins(File dir, String onnx) {
        String stem = new File(onnx).getName().replace(".onnx", "");
        File[] fs = dir.listFiles((d, n) -> n.startsWith(stem) && n.endsWith(".bin"));
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Манифест модели, если лежит в пакете (словарь, blank, окно); иначе — словарь GigaAM по умолчанию. */
    private static AsrModelSpec spec(ReadingPack p) {
        if (p.manifest == null) return null;
        try {
            String js = new String(java.nio.file.Files.readAllBytes(new File(p.dir, p.manifest).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return new AsrModelSpec(new org.json.JSONObject(js));
        } catch (Exception e) { Log.w(TAG, "манифест " + p.manifest + ": " + e); return null; }
    }

    /** Родитель нажал «Подготовить»: снять пометку «NPU сломан», выгрузить и собрать заново с компиляцией. */
    static String prepare(Context c) {
        new Prefs(c).setHtpBroken(false);
        acquire();
        try {
            load.lock();
            try {   // загружен CPU-запасной, а NPU ещё не собран — выгрузить, если экран чтения им сейчас не пользуется
                boolean busy; synchronized (ReadingEngine.class) { busy = users > 1; }
                if (model != null && model.backend == CtcModel.Backend.CPU && !busy && pack(c).needsPrepare(false)) closeNow();
            } finally { load.unlock(); }
            CtcModel m = model(c, true);
            return m == null ? "Модель не загрузилась (подробности в logcat, тег " + TAG + ")" : "Готово: " + describe();
        } finally { release(); }
    }
}
