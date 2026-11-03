package dev.gubenkov.reading;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * CTC-модель в ONNX по манифесту AsrModelSpec. Сейчас — GigaAM-v3 CTC (символы):
 * feats [1,64,T] -> logp [1,U,34], U = T/4. Словарь и blank берутся из манифеста.
 */
public final class CtcModel implements AutoCloseable {
    /** Словарь GigaAM-v3 ctc — по умолчанию, если манифеста нет (стенд). */
    public static final String[] GIGAAM_VOCAB = " абвгдежзийклмнопрстуфхцчшщъыьэюя".split("(?!^)");

    public enum Backend { CPU, QNN_HTP }

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final boolean staticT;
    public final int frames; // фиксированное T для статической модели, 0 — динамическая
    public final Backend backend;
    private final String[] vocab;
    private final int blank;

    /** cacheDir — куда класть скомпилированный контекст QNN (первый запуск долгий, потом быстрый). */
    public CtcModel(String path, Backend backend, int staticFrames, String cacheDir) throws OrtException {
        this(path, backend, staticFrames, cacheDir, GIGAAM_VOCAB, GIGAAM_VOCAB.length);
    }

    /** Модель по манифесту: файл выбирается под бэкенд (CPU — динамическая, HTP — статическая). */
    public static CtcModel open(AsrModelSpec spec, java.io.File dir, Backend backend, boolean htpFp16, String cacheDir) throws OrtException {
        String f = backend == Backend.CPU ? spec.fileCpu : (htpFp16 ? spec.fileHtpFp16 : spec.fileHtpInt);
        if (f == null) throw new IllegalArgumentException(spec.id + ": нет файла для " + backend);
        return new CtcModel(new java.io.File(dir, f).getPath(), backend, backend == Backend.CPU ? 0 : spec.staticFrames, cacheDir, spec.vocab, spec.blank);
    }

    public CtcModel(String path, Backend backend, int staticFrames, String cacheDir, String[] vocab, int blank) throws OrtException {
        this.vocab = vocab; this.blank = blank;
        this.backend = backend;
        this.frames = staticFrames;
        this.staticT = staticFrames > 0;
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (backend == Backend.QNN_HTP) {
            Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", "libQnnHtp.so");
            qnn.put("htp_performance_mode", "burst");
            qnn.put("enable_htp_fp16_precision", "1");
            qnn.put("htp_graph_finalization_optimization_mode", "3");
            so.addQnn(qnn);
            so.addConfigEntry("session.disable_cpu_ep_fallback", "0");
            if (cacheDir != null) {
                // Скомпилированный под HTP граф кэшируем: первый запуск ~100 с, дальше — загрузка готового.
                java.io.File ctx = new java.io.File(cacheDir, new java.io.File(path).getName().replace(".onnx", "_qnnctx.onnx"));
                if (ctx.exists()) path = ctx.getPath();
                else {
                    so.addConfigEntry("ep.context_enable", "1");
                    so.addConfigEntry("ep.context_embed_mode", "0");
                    so.addConfigEntry("ep.context_file_path", ctx.getPath());
                }
            }
        } else {
            so.setIntraOpNumThreads(4);
        }
        session = env.createSession(path, so);
    }

    /** feats в раскладке [mel][t], t = nFrames. Для статической модели nFrames должен быть == frames. */
    public float[][] run(float[] feats, int nFrames) throws OrtException {
        Map<String, OnnxTensor> in = new HashMap<>();
        in.put("feats", OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, MelFrontend.N_MELS, nFrames}));
        if (!staticT) in.put("lens", OnnxTensor.createTensor(env, new long[]{nFrames}));
        try (OrtSession.Result r = session.run(in)) {
            float[][][] lp = (float[][][]) r.get(0).getValue();
            return lp[0];
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    public String greedy(float[][] logp, int validFrames) { return greedy(logp, validFrames, vocab, blank); }

    public static String greedy(float[][] logp, int validFrames, String[] vocab, int blank) {
        StringBuilder sb = new StringBuilder(); int prev = -1;
        for (int t = 0; t < Math.min(validFrames, logp.length); t++) {
            int best = 0; float bv = logp[t][0];
            for (int c = 1; c < logp[t].length; c++) if (logp[t][c] > bv) { bv = logp[t][c]; best = c; }
            if (best != prev && best != blank) sb.append(vocab[best]);
            prev = best;
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    /** Число выходных кадров кодера для nFeat кадров признаков (свёртки с шагом 2 дважды). */
    public static int encFrames(int nFeat) { return (nFeat + 3) / 4; }

    @Override public void close() throws OrtException { session.close(); }
}
