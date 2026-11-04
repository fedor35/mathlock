package dev.gubenkov.mathlock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пакет чтения на телефоне: что лежит в {@code files/} «Таблицы» и можно ли с этим читать.
 * Только java.io (без Android и org.json) — проверяется JUnit.
 * <pre>
 * files/texts.jsonl                                   тексты (обязательно)
 * files/gigaam_ctc_T1600_qdq.onnx                     NPU, QDQ A16W8 — HTP v68/v69 без fp16 (Xiaomi 13 Lite) и новее
 * files/gigaam_ctc_T1600_fp32.onnx                    NPU с fp16 — только HTP v73+ (S26 Ultra)
 * files/gigaam_ctc_int8.onnx, gigaam_ctc_fp32.onnx    CPU — запасной путь
 * files/gigaam3-ctc-ru.model.json                     манифест (необязательно: имена файлов, окно)
 * files/3dspeaker_…onnx                               голоса чтецов (необязательно: без него общий журнал)
 * files/tts/&lt;id&gt;.&lt;голос&gt;.ogg|wav                    озвучка «послушать» (необязательно)
 * filesDir/qnn/&lt;модель&gt;_qnnctx.onnx (+ .bin)            скомпилированный под NPU граф (готовит родитель)
 * </pre>
 * Модель годится для экрана блокировки, только если она загрузится быстро: CPU-файл или NPU с готовым контекстом.
 * Компиляция под NPU (минуты) на экране блокировки не запускается — её делает кнопка «Подготовить» у родителя.
 */
final class ReadingPack {
    static final String TEXTS = "texts.jsonl", TTS = "tts", MANIFEST_SUFFIX = ".model.json";
    static final String DEF_HTP_INT = "gigaam_ctc_T1600_qdq.onnx", DEF_HTP_FP16 = "gigaam_ctc_T1600_fp32.onnx";
    static final String DEF_CPU = "gigaam_ctc_int8.onnx", DEF_CPU_FP32 = "gigaam_ctc_fp32.onnx";
    /** dev.gubenkov.reading.SpeakerModel.FILE (константа, без загрузки ORT). */
    static final String SPEAKER = dev.gubenkov.reading.SpeakerModel.FILE;
    static final int DEF_STATIC_FRAMES = 1600;

    /** Один вариант загрузки модели. */
    static final class Candidate {
        final String file; final boolean htp; final boolean cached;
        Candidate(String file, boolean htp, boolean cached) { this.file = file; this.htp = htp; this.cached = cached; }
        @Override public String toString() { return (htp ? (cached ? "NPU " : "NPU (компиляция) ") : "CPU ") + file; }
    }

    final File dir, qnnDir;
    final String htpInt, htpFp16, cpu, manifest;
    final int staticFrames;

    private ReadingPack(File dir, File qnnDir, String manifest, String htpInt, String htpFp16, String cpu, int staticFrames) {
        this.dir = dir; this.qnnDir = qnnDir; this.manifest = manifest;
        this.htpInt = htpInt; this.htpFp16 = htpFp16; this.cpu = cpu; this.staticFrames = staticFrames;
    }

    /** @param dir files/ пакета (может быть null — внешнее хранилище недоступно), {@code qnnDir} — кэш контекстов NPU. */
    static ReadingPack at(File dir, File qnnDir) {
        String man = null, hi = DEF_HTP_INT, hf = DEF_HTP_FP16, c = DEF_CPU; int sf = DEF_STATIC_FRAMES;
        File[] ms = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(MANIFEST_SUFFIX));
        if (ms != null && ms.length > 0) {
            java.util.Arrays.sort(ms);
            try {
                String js = new String(java.nio.file.Files.readAllBytes(ms[0].toPath()), StandardCharsets.UTF_8);
                man = ms[0].getName();
                hi = field(js, "htp_int", hi); hf = field(js, "htp_fp16", hf); c = field(js, "cpu", c);
                Matcher m = Pattern.compile("\"static_frames\"\\s*:\\s*(\\d+)").matcher(js);
                if (m.find()) sf = Integer.parseInt(m.group(1));
            } catch (Exception ignored) { }
        }
        return new ReadingPack(dir, qnnDir, man, hi, hf, c, sf);
    }

    private static String field(String js, String key, String def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(js);
        return m.find() ? m.group(1) : def;
    }

    /** Имя скомпилированного контекста — как его называет CtcModel. */
    static String ctxName(String onnx) { return new File(onnx).getName().replace(".onnx", "_qnnctx.onnx"); }

    boolean has(String f) { return dir != null && f != null && new File(dir, f).length() > 0; }
    boolean cached(String f) { return qnnDir != null && f != null && new File(qnnDir, ctxName(f)).length() > 0; }

    /**
     * Порядок попыток загрузки: NPU QDQ → NPU fp16 → CPU int8 → CPU fp32.
     * @param htpBroken NPU однажды упал — пропускаем
     * @param allowCompile можно ли компилировать под NPU (долго; только у родителя)
     */
    List<Candidate> plan(boolean htpBroken, boolean allowCompile) {
        List<Candidate> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (!htpBroken) for (String f : new String[]{htpInt, htpFp16})
            if (has(f) && seen.add(f) && (cached(f) || allowCompile)) out.add(new Candidate(f, true, cached(f)));
        for (String f : new String[]{cpu, DEF_CPU_FP32})
            if (has(f) && seen.add(f)) out.add(new Candidate(f, false, false));
        return out;
    }

    /** Есть NPU-модель, ещё не скомпилированная под этот телефон. */
    boolean needsPrepare(boolean htpBroken) {
        if (htpBroken) return false;
        for (String f : new String[]{htpInt, htpFp16}) if (has(f) && !cached(f)) return true;
        return false;
    }

    /** Число текстов в texts.jsonl (непустых строк с "text"). */
    int textCount() {
        if (dir == null) return 0;
        File f = new File(dir, TEXTS); if (!f.isFile()) return 0;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) if (l.contains("\"text\"")) n++;
        } catch (Exception e) { return 0; }
        return n;
    }

    boolean speaker() { return has(SPEAKER); }

    /** Для скольких текстов есть озвучка (tts/&lt;id&gt;.&lt;голос&gt;.ogg|wav). */
    int ttsCount() {
        File[] fs = dir == null ? null : new File(dir, TTS).listFiles((d, n) -> n.endsWith(".ogg") || n.endsWith(".wav"));
        if (fs == null) return 0;
        Set<String> ids = new HashSet<>();
        for (File f : fs) { String n = f.getName(); int i = n.indexOf('.'); if (i > 0) ids.add(n.substring(0, i)); }
        return ids.size();
    }

    /** Можно ли сейчас дать задание чтения (без компиляции и без учёта микрофона). */
    boolean ready(boolean htpBroken) { return textCount() > 0 && !plan(htpBroken, false).isEmpty(); }

    /** Чего не хватает для чтения — для родителя; пусто, если всё есть. */
    String missing(boolean htpBroken) {
        List<String> m = new ArrayList<>();
        if (dir == null) return "внешнее хранилище недоступно";
        if (textCount() == 0) m.add(TEXTS);
        if (plan(htpBroken, true).isEmpty()) m.add("модель GigaAM (" + htpInt + " или " + cpu + ")");
        else if (plan(htpBroken, false).isEmpty()) m.add("модель не подготовлена для NPU — нажмите «Подготовить»");
        return String.join(", ", m);
    }
}
