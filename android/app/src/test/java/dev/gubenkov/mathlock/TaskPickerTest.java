package dev.gubenkov.mathlock;

import static org.junit.Assert.*;

import org.junit.Test;

import dev.gubenkov.mathlock.TaskPicker.Kind;
import dev.gubenkov.mathlock.TaskPicker.Mode;

public class TaskPickerTest {
    @Test public void mathModeAlwaysMath() {
        for (int c = 0; c < 4; c++) assertEquals(Kind.MATH, TaskPicker.pick(Mode.MATH, false, true, c));
    }

    @Test public void readingModeReadsWhenReady() {
        assertEquals(Kind.READING, TaskPicker.pick(Mode.READING, false, true, 0));
        assertEquals(Kind.READING, TaskPicker.pick(Mode.READING, false, true, 1));
    }

    @Test public void notReadyFallsBackToMath() {
        // нет пакета / модели / микрофона — ребёнок получает примеры, а не пустой экран
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.READING, false, false, 0));
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.MIXED, false, false, 0));
    }

    @Test public void guardScreenIsAlwaysKeypad() {
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.READING, true, true, 0));
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.MIXED, true, true, 0));
    }

    @Test public void mixedAlternates() {
        assertEquals(Kind.READING, TaskPicker.pick(Mode.MIXED, false, true, 0));
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.MIXED, false, true, 1));
        assertEquals(Kind.READING, TaskPicker.pick(Mode.MIXED, false, true, 2));
        assertEquals(Kind.MATH, TaskPicker.pick(Mode.MIXED, false, true, -1));
    }

    @Test public void modeFromPrefs() {
        assertEquals(Mode.MATH, Mode.of(0));
        assertEquals(Mode.READING, Mode.of(1));
        assertEquals(Mode.MIXED, Mode.of(2));
        assertEquals(Mode.MATH, Mode.of(7));    // мусор в настройках — примеры
        assertEquals(Mode.MATH, Mode.of(-1));
        assertEquals(Kind.MATH, TaskPicker.pick(null, false, true, 0));
    }

    @Test public void defaultThresholdNeedsComplete() {
        // по умолчанию как в readbench: все слова верно (красные перечитать)
        assertTrue(TaskPicker.passed(10, 10, true, 100));
        assertFalse(TaskPicker.passed(9, 10, false, 100));
        assertFalse(TaskPicker.passed(10, 10, false, 100));   // без complete (например, хвост не дочитан) — нет
    }

    @Test public void parentThreshold() {
        assertTrue(TaskPicker.passed(9, 10, false, 90));
        assertFalse(TaskPicker.passed(8, 10, false, 90));
        assertTrue(TaskPicker.passed(17, 20, false, 85));
        assertTrue(TaskPicker.passed(3, 3, true, 90));
        assertFalse(TaskPicker.passed(0, 0, true, 50));       // пустой текст не засчитывается
    }

    @Test public void thresholdClamped() {
        assertEquals(50, TaskPicker.clampThreshold(10));
        assertEquals(100, TaskPicker.clampThreshold(150));
        assertEquals(80, TaskPicker.clampThreshold(80));
        assertFalse(TaskPicker.passed(3, 10, false, 10));     // 10% поднимается до 50%
        assertTrue(TaskPicker.passed(5, 10, false, 10));
        assertFalse(TaskPicker.passed(9, 10, false, 1000));   // больше 100 — как 100
    }

    @Test public void offerMathAfterFailsOrTime() {
        assertFalse(TaskPicker.offerMath(0, 0));
        assertFalse(TaskPicker.offerMath(TaskPicker.FALLBACK_FAILS - 1, TaskPicker.FALLBACK_MS - 1));
        assertTrue(TaskPicker.offerMath(TaskPicker.FALLBACK_FAILS, 0));
        assertTrue(TaskPicker.offerMath(0, TaskPicker.FALLBACK_MS));
    }
}
