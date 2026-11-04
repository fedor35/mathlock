package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Фикстура из ml/stress/export.py: озвучка корпуса, лог-вероятности GigaAM (PyTorch fp32), признаки и ответы Python.
 * Java должна дать те же отрезки гласных, те же признаки (мел считает MelFrontend — допуск) и те же ударные гласные.
 */
public class StressClassifierTest {
    static final String[] CLIPS = {"ru-L3-007.feya", "ru-L4-015.malchik"};

    static byte[] res(String name) throws Exception {
        try (InputStream in = StressClassifierTest.class.getResourceAsStream(name)) {
            assertNotNull("нет ресурса " + name, in);
            return in.readAllBytes();
        }
    }

    static float[] f32(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[b.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
        return out;
    }

    static List<double[]> rows(String js, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]\\]").matcher(js);
        assertTrue(key, m.find());
        List<double[]> out = new ArrayList<>();
        for (String row : m.group(1).split("\\]\\s*,\\s*\\[")) {
            String[] p = row.replace("[", "").trim().split("\\s*,\\s*");
            double[] r = new double[p.length];
            for (int i = 0; i < p.length; i++) r[i] = Double.parseDouble(p[i]);
            out.add(r);
        }
        return out;
    }

    @Test public void alignAndFeaturesMatchPython() throws Exception {
        StressClassifier sc = StressClassifier.fromResource();
        int words = 0, agree = 0, correct = 0;
        for (String clip : CLIPS) {
            String js = new String(res("/stress_fixture/" + clip + ".json"), StandardCharsets.UTF_8);
            String text = js.replaceAll("(?s).*\"text\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            int samples = Integer.parseInt(js.replaceAll("(?s).*\"samples\"\\s*:\\s*(\\d+).*", "$1"));
            int T = Integer.parseInt(js.replaceAll("(?s).*\"T\"\\s*:\\s*(\\d+).*", "$1"));
            float[] pcm = f32(res("/stress_fixture/" + clip + ".pcm.f32"));
            float[] flat = f32(res("/stress_fixture/" + clip + ".lp.f32"));
            assertEquals(samples, pcm.length);
            float[][] lp = new float[T][34];
            for (int t = 0; t < T; t++) System.arraycopy(flat, t * 34, lp[t], 0, 34);

            ReadingText rt = new ReadingText(text);
            List<StressClassifier.Vowel> vs = new ArrayList<>();
            double[][] X = sc.features(rt, lp, T, pcm, samples, vs);
            assertNotNull(X);
            List<double[]> segs = rows(js, "segs"), feats = rows(js, "features");
            assertEquals(clip + ": число гласных", segs.size(), vs.size());
            for (int i = 0; i < vs.size(); i++) {
                assertEquals(clip + " отрезок " + i, (int) segs.get(i)[0], vs.get(i).a);
                assertEquals(clip + " отрезок " + i, (int) segs.get(i)[1], vs.get(i).b);
            }
            double maxErr = 0;
            for (int i = 0; i < X.length; i++) {
                assertEquals(feats.get(i).length, X[i].length);
                for (int c = 0; c < X[i].length; c++) {
                    double e = Math.abs(X[i][c] - feats.get(i)[c]) / (1 + Math.abs(feats.get(i)[c]));
                    if (e > 2e-2) System.out.printf("  гласная %d признак %d: java %.5f python %.5f%n", i, c, X[i][c], feats.get(i)[c]);
                    maxErr = Math.max(maxErr, e);
                }
            }
            System.out.printf("%s: гласных %d, макс. отн. расхождение признаков %.2e%n", clip, X.length, maxErr);
            assertTrue(clip + ": признаки расходятся " + maxErr, maxErr < 2e-2); // мел: MelFrontend (float) против препроцессора PyTorch

            List<StressClassifier.WordStress> out = sc.analyze(rt, lp, T, pcm, samples);
            Matcher wm = Pattern.compile("\"expected\":\\s*(-?\\d+),\\s*\"predicted\":\\s*(\\d+)").matcher(js);
            for (StressClassifier.WordStress w : out) {
                assertTrue(wm.find());
                assertEquals(clip + " " + w.letters + ": ударение по тексту", Integer.parseInt(wm.group(1)), w.expected);
                words++;
                if (Integer.parseInt(wm.group(2)) == w.predicted) agree++;
                if (w.predicted == w.expected) correct++;
            }
            assertFalse(clip + ": лишние слова в фикстуре", wm.find());
        }
        System.out.printf("слов %d, совпадений с Python %d, верных ударений %d%n", words, agree, correct);
        assertEquals("ответы Java и Python", words, agree);
    }

    /** Путь через «голову ударения» энкодера: s_t из PyTorch, ответы — как у Python (export_enc.py). */
    @Test public void encoderHeadMatchesPython() throws Exception {
        int words = 0, agree = 0, correct = 0;
        for (String clip : CLIPS) {
            String js = new String(res("/stress_fixture/" + clip + ".json"), StandardCharsets.UTF_8);
            String text = js.replaceAll("(?s).*\"text\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            int T = Integer.parseInt(js.replaceAll("(?s).*\"T\"\\s*:\\s*(\\d+).*", "$1"));
            float[] flat = f32(res("/stress_fixture/" + clip + ".lp.f32"));
            float[][] lp = new float[T][34];
            for (int t = 0; t < T; t++) System.arraycopy(flat, t * 34, lp[t], 0, 34);
            float[] s = f32(res("/stress_fixture/" + clip + ".s.f32"));
            assertEquals(T, s.length);
            String ej = new String(res("/stress_fixture/" + clip + ".enc.json"), StandardCharsets.UTF_8);
            List<StressClassifier.WordStress> out = StressClassifier.analyzeFrameScores(new ReadingText(text), lp, T, s);
            Matcher wm = Pattern.compile("\"expected\":\\s*(-?\\d+),\\s*\"predicted\":\\s*(\\d+)").matcher(ej);
            for (StressClassifier.WordStress w : out) {
                assertTrue(wm.find());
                words++;
                if (Integer.parseInt(wm.group(2)) == w.predicted) agree++;
                if (w.predicted == w.expected) correct++;
            }
        }
        System.out.printf("энкодер: слов %d, совпадений с Python %d, верных ударений %d%n", words, agree, correct);
        assertEquals(words, agree);
        float[] v = StressClassifier.encHead(new String(res("/dev/gubenkov/reading/stress_enc_head.json"), StandardCharsets.UTF_8));
        assertEquals(768, v.length);
    }

    @Test public void alignmentSimple() {
        // 6 кадров, цель «аб»: а на 0-1, пусто 2, б 3-4, пусто 5
        float[][] lp = new float[6][34];
        int a = StressClassifier.tokenIndex('а'), b = StressClassifier.tokenIndex('б');
        int[] best = {a, a, 33, b, b, 33};
        for (int t = 0; t < 6; t++) { java.util.Arrays.fill(lp[t], -10f); lp[t][best[t]] = -0.01f; }
        int[][] al = StressClassifier.forcedAlign(lp, 6, new int[]{a, b});
        assertArrayEquals(new int[]{0, 3}, al[0]);
        assertArrayEquals(new int[]{1, 4}, al[1]);
    }
}
