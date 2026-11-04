package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Kaldi fbank и маска речи против kaldi-native-fbank (ml/speaker_fixture.py, клип — голос персонажа озвучки). */
public class FbankTest {
    static byte[] res(String name) throws Exception {
        try (InputStream in = FbankTest.class.getResourceAsStream("/" + name)) { return in.readAllBytes(); }
    }

    static float[] clip() throws Exception {
        ByteBuffer b = ByteBuffer.wrap(res("spk_clip.wav")).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(40) / 2; float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = b.getShort(44 + 2 * i) / 32768f;
        return x;
    }

    @Test public void matchesKaldiNativeFbank() throws Exception {
        float[] x = clip();
        float[][] f = new Fbank().compute(x, x.length);
        ByteBuffer ref = ByteBuffer.wrap(res("spk_fbank.f32")).order(ByteOrder.LITTLE_ENDIAN);
        int T = ref.remaining() / 4 / Fbank.BINS;
        assertEquals(T, f.length);
        double maxErr = 0, sum = 0; int n = 0;
        for (int t = 0; t < T; t++) for (int m = 0; m < Fbank.BINS; m++) {
            float r = ref.getFloat(); if (r < -10) continue;   // тишина у пола ln(eps): относительная точность не важна
            double e = Math.abs(r - f[t][m]); maxErr = Math.max(maxErr, e); sum += e; n++;
        }
        System.out.printf("fbank: %d кадров, max |Δ| %.2e, средняя %.2e%n", T, maxErr, sum / n);
        assertTrue("max |Δ| = " + maxErr, maxErr < 2e-3);
    }

    @Test public void voicedMaskMatches() throws Exception {
        float[] x = clip();
        boolean[] v = Fbank.voiced(x, x.length);
        String ref = new String(res("spk_voiced.txt")).trim();
        assertEquals(ref.length(), v.length);
        int diff = 0, speech = 0;
        for (int i = 0; i < v.length; i++) { if (v[i] != (ref.charAt(i) == '1')) diff++; if (v[i]) speech++; }
        System.out.printf("речь %d из %d кадров, расхождений %d%n", speech, v.length, diff);
        assertTrue(diff <= 1);
        assertTrue(speech > 100 && speech < v.length - 50);   // тишина по краям отрезана
    }

    @Test public void modelInputIsMeanFree() throws Exception {
        float[] x = clip(); int[] T = new int[1];
        float[] in = new Fbank().modelInput(x, x.length, 100000, T);
        assertEquals(in.length, T[0] * Fbank.BINS);
        for (int m = 0; m < Fbank.BINS; m++) {
            double s = 0; for (int t = 0; t < T[0]; t++) s += in[t * Fbank.BINS + m];
            assertEquals(0, s / T[0], 1e-4);
        }
    }
}
