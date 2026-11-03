package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Реальные выходы GigaAM (ONNX fp32/QDQ) на синтезированном детском чтении — ml/asr_fixture.py. */
public class FixtureTest {
    @Test public void fixture() throws Exception {
        InputStream in = getClass().getResourceAsStream("/reads_fixture.json");
        String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"file\": \"([^\"]+)\",\\s*\"id\": \"([^\"]+)\",\\s*\"target\": \"([^\"]+)\",\\s*\"fp32\": \"([^\"]*)\",\\s*\"qdq\": \"([^\"]*)\"").matcher(js);
        int n = 0, bad = 0; StringBuilder rep = new StringBuilder();
        while (m.find()) {
            n++;
            String file = m.group(1), id = m.group(2), target = m.group(3);
            boolean expectOk = id.startsWith("ok_") && !file.equals("ok_syll.1.wav"); // этот клип TTS озвучил без слога
            for (int k = 4; k <= 5; k++) {
                ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText(target), m.group(k), true);
                StringBuilder s = new StringBuilder();
                for (int w = 0; w < r.states.length; w++) s.append(r.states[w] == ReadingMatcher.State.OK ? "+" : "-")
                        .append(String.format("%.1f ", r.wordCost[w]));
                boolean okk = r.complete == expectOk; if (!okk) bad++;
                rep.append(okk ? "   " : "!! ").append(file).append(k == 4 ? " fp32 " : " qdq  ").append(r.complete).append("  ").append(s).append(" | ").append(m.group(k)).append('\n');
            }
        }
        System.out.println(rep);
        assertTrue(n > 0);
        assertEquals("ошибочных решений:\n" + rep, 0, bad);
    }
}
