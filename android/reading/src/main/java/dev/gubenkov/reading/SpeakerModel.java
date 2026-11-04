package dev.gubenkov.reading;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Голосовой отпечаток: 3D-Speaker CAM++ (zh_en common advanced, Apache-2.0, 28 МБ) в ONNX из релиза sherpa-onnx
 * «speaker-recongition-models». Вход x [1,T,80] — {@link Fbank#modelInput}, выход — эмбеддинг 192 (нормируем).
 * CPU, 2 потока: NPU не нужен, запись в 30 с считается за доли секунды.
 */
public final class SpeakerModel implements AutoCloseable {
    public static final String FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx";
    /** Не больше 30 с речи: дольше точность не растёт, а время — растёт. */
    public static final int MAX_FRAMES = 3000;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final Fbank fbank = new Fbank();

    public static final class Result {
        public final float[] emb; public final float speechS; public final int frames; public final long featMs, modelMs;
        Result(float[] e, float s, int f, long fm, long mm) { emb = e; speechS = s; frames = f; featMs = fm; modelMs = mm; }
    }

    public SpeakerModel(String path) throws OrtException {
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        so.setIntraOpNumThreads(2);
        session = env.createSession(path, so);
    }

    /** Эмбеддинг записи 16 кГц (x[0..n)); null, если речи нет совсем. */
    public Result embed(float[] x, int n) throws OrtException {
        long t0 = System.nanoTime();
        float speech = Fbank.speechSeconds(x, n);
        int[] T = new int[1];
        float[] feats = fbank.modelInput(x, n, MAX_FRAMES, T);
        long t1 = System.nanoTime();
        if (T[0] < 10) return null;
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, T[0], Fbank.BINS});
             OrtSession.Result r = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), in))) {
            float[] e = ((float[][]) r.get(0).getValue())[0];
            long t2 = System.nanoTime();
            return new Result(SpeakerId.normalize(e), speech, T[0], (t1 - t0) / 1000000, (t2 - t1) / 1000000);
        }
    }

    @Override public void close() throws OrtException { session.close(); }
}
