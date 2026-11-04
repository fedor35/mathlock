package dev.gubenkov.mathlock;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReadingPackTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    File dir, qnn;

    void put(File d, String name, String body) throws Exception {
        File f = new File(d, name); f.getParentFile().mkdirs();
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
    }

    ReadingPack pack() throws Exception {
        if (dir == null) { dir = tmp.newFolder("files"); qnn = tmp.newFolder("qnn"); }
        return ReadingPack.at(dir, qnn);
    }

    @Test public void emptyIsNotReady() throws Exception {
        ReadingPack p = pack();
        assertFalse(p.ready(false));
        assertEquals(0, p.textCount());
        assertTrue(p.plan(false, true).isEmpty());
        assertFalse(p.missing(false).isEmpty());
    }

    @Test public void noStorageIsNotReady() {
        ReadingPack p = ReadingPack.at(null, null);
        assertFalse(p.ready(false));
        assertEquals("внешнее хранилище недоступно", p.missing(false));
    }

    @Test public void textsWithoutModelNotReady() throws Exception {
        pack(); put(dir, "texts.jsonl", "{\"id\":\"a\",\"text\":\"Мама\"}\n\n{\"id\":\"b\",\"text\":\"Кот\"}\n");
        ReadingPack p = pack();
        assertEquals(2, p.textCount());
        assertFalse(p.ready(false));
    }

    @Test public void cpuModelIsReady() throws Exception {
        pack(); put(dir, "texts.jsonl", "{\"id\":\"a\",\"text\":\"Мама\"}\n"); put(dir, ReadingPack.DEF_CPU, "x");
        ReadingPack p = pack();
        assertTrue(p.ready(false));
        assertEquals("", p.missing(false));
        List<ReadingPack.Candidate> plan = p.plan(false, false);
        assertEquals(1, plan.size());
        assertFalse(plan.get(0).htp);
    }

    @Test public void uncompiledNpuNeedsPrepareAndIsNotUsedOnLockScreen() throws Exception {
        pack(); put(dir, "texts.jsonl", "{\"id\":\"a\",\"text\":\"Мама\"}\n"); put(dir, ReadingPack.DEF_HTP_INT, "x");
        ReadingPack p = pack();
        assertTrue(p.needsPrepare(false));
        assertFalse("компиляция — минуты, на экране блокировки её не ждём", p.ready(false));
        assertEquals(1, p.plan(false, true).size());
        assertTrue(p.missing(false).contains("Подготовить"));
        // контекст собран — NPU годится и для экрана блокировки
        put(qnn, ReadingPack.ctxName(ReadingPack.DEF_HTP_INT), "ctx");
        p = pack();
        assertFalse(p.needsPrepare(false));
        assertTrue(p.ready(false));
        assertTrue(p.plan(false, false).get(0).cached);
    }

    @Test public void planOrderAndBrokenNpu() throws Exception {
        pack();
        put(dir, "texts.jsonl", "{\"id\":\"a\",\"text\":\"Мама\"}\n");
        for (String f : new String[]{ReadingPack.DEF_HTP_INT, ReadingPack.DEF_HTP_FP16, ReadingPack.DEF_CPU, ReadingPack.DEF_CPU_FP32}) put(dir, f, "x");
        put(qnn, ReadingPack.ctxName(ReadingPack.DEF_HTP_INT), "ctx");
        ReadingPack p = pack();
        List<ReadingPack.Candidate> all = p.plan(false, true);
        assertEquals(4, all.size());
        assertEquals(ReadingPack.DEF_HTP_INT, all.get(0).file);     // QDQ первым: идёт и на v69 (без fp16)
        assertEquals(ReadingPack.DEF_HTP_FP16, all.get(1).file);
        assertEquals(ReadingPack.DEF_CPU, all.get(2).file);
        assertEquals(ReadingPack.DEF_CPU_FP32, all.get(3).file);
        assertEquals(3, p.plan(false, false).size());               // fp16 не скомпилирован — на экране блокировки пропускаем
        List<ReadingPack.Candidate> broken = p.plan(true, true);    // NPU упал — только CPU
        assertEquals(2, broken.size());
        for (ReadingPack.Candidate c : broken) assertFalse(c.htp);
        assertFalse(p.needsPrepare(true));
    }

    @Test public void manifestNames() throws Exception {
        pack();
        put(dir, "gigaam3-ctc-ru.model.json", "{\"id\":\"g\",\"static_frames\": 800,\n \"files\": {\"cpu\": \"my_cpu.onnx\", \"htp_int\": \"my_q.onnx\"}}");
        ReadingPack p = pack();
        assertEquals("gigaam3-ctc-ru.model.json", p.manifest);
        assertEquals("my_cpu.onnx", p.cpu);
        assertEquals("my_q.onnx", p.htpInt);
        assertEquals(ReadingPack.DEF_HTP_FP16, p.htpFp16);
        assertEquals(800, p.staticFrames);
    }

    @Test public void ttsAndSpeaker() throws Exception {
        pack();
        put(dir, "tts/ru-L1-001.feya.ogg", "x"); put(dir, "tts/ru-L1-001.feya.json", "{}"); put(dir, "tts/ru-L1-001.malchik.ogg", "x");
        put(dir, "tts/ru-L1-002.rhvoice.wav", "x");
        ReadingPack p = pack();
        assertEquals(2, p.ttsCount());
        assertFalse(p.speaker());
        put(dir, ReadingPack.SPEAKER, "x");
        assertTrue(pack().speaker());
    }

    @Test public void zeroLengthModelIsMissing() throws Exception {
        pack(); put(dir, "texts.jsonl", "{\"id\":\"a\",\"text\":\"Мама\"}\n"); put(dir, ReadingPack.DEF_CPU, "");
        assertFalse(pack().ready(false));
    }

    @Test public void ctxNameMatchesCtcModel() {
        assertEquals("gigaam_ctc_T1600_qdq_qnnctx.onnx", ReadingPack.ctxName("gigaam_ctc_T1600_qdq.onnx"));
    }

    @Test public void installerAcceptsOnlyPackFiles() {
        assertTrue(PackInstaller.accept("texts.jsonl"));
        assertTrue(PackInstaller.accept("gigaam_ctc_T1600_qdq.onnx"));
        assertTrue(PackInstaller.accept("gigaam3-ctc-ru.model.json"));
        assertTrue(PackInstaller.accept("tts/ru-L1-001.feya.ogg"));
        assertTrue(PackInstaller.accept("tts/ru-L1-001.feya.json"));
        assertFalse(PackInstaller.accept("progress.tsv"));           // журналы и профили пакет не перетирает
        assertFalse(PackInstaller.accept("profiles/p1.json"));
        assertFalse(PackInstaller.accept("tts/sub/x.ogg"));
        assertFalse(PackInstaller.accept("other/model.onnx"));
        assertFalse(PackInstaller.accept("readme.txt"));
    }
}
