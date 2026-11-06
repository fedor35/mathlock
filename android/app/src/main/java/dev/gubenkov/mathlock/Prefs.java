package dev.gubenkov.mathlock;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки и состояние «заблокирован/нет». Состояние хранится на диске, чтобы пережить смерть процесса. */
public final class Prefs {
    private static final String NAME = "mathlock";
    private final SharedPreferences sp;

    public Prefs(Context c) { sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }

    public boolean enabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public boolean locked() { return sp.getBoolean("locked", false); }
    public void setLocked(boolean v) { sp.edit().putBoolean("locked", v).apply(); }

    public long screenOffAt() { return sp.getLong("screen_off_at", 0); }
    public void setScreenOffAt(long t) { sp.edit().putLong("screen_off_at", t).apply(); }

    /** Множители от minFactor до maxFactor включительно (второй множитель всегда 2..9). */
    public int minFactor() { return sp.getInt("min_factor", 2); }
    public int maxFactor() { return sp.getInt("max_factor", 9); }
    public void setFactors(int min, int max) { sp.edit().putInt("min_factor", min).putInt("max_factor", max).apply(); }

    /** Сколько правильных ответов нужно для разблокировки. */
    public int needCorrect() { return sp.getInt("need_correct", 3); }
    public void setNeedCorrect(int v) { sp.edit().putInt("need_correct", v).apply(); }

    /** Если экран погас меньше чем на столько секунд — примеры не задаём. */
    public int graceSeconds() { return sp.getInt("grace_seconds", 20); }
    public void setGraceSeconds(int v) { sp.edit().putInt("grace_seconds", v).apply(); }

    /** Родительский код: ввод этих цифр вместо ответа разблокирует телефон. Пусто = отключён. */
    public String parentCode() { return sp.getString("parent_code", "2580"); }
    public void setParentCode(String v) { sp.edit().putString("parent_code", v).apply(); }

    /** Контрольный пример каждые N минут работы экрана (0 = выключено). */
    public int checkIntervalMin() { return sp.getInt("check_interval_min", 30); }
    public void setCheckIntervalMin(int v) { sp.edit().putInt("check_interval_min", v).apply(); }
    /** Сколько примеров в контрольной проверке. */
    public int checkCount() { return sp.getInt("check_count", 1); }
    public void setCheckCount(int v) { sp.edit().putInt("check_count", v).apply(); }
    /** Сколько примеров нужно сейчас (меняется для контрольной проверки). */
    public int needNow() { return sp.getInt("need_now", needCorrect()); }
    public void setNeedNow(int v) { sp.edit().putInt("need_now", v).apply(); }

    /** До какого момента (elapsedRealtime) родитель может ходить по Настройкам без кода. */
    public long guardPassUntil() { return sp.getLong("guard_pass_until", 0); }
    public void setGuardPassUntil(long t) { sp.edit().putLong("guard_pass_until", t).apply(); }

    public int statAsked() { return sp.getInt("stat_asked", 0); }
    public int statWrong() { return sp.getInt("stat_wrong", 0); }
    public void bumpStat(boolean wrong) {
        sp.edit().putInt("stat_asked", statAsked() + 1).putInt("stat_wrong", statWrong() + (wrong ? 1 : 0)).apply();
    }

    // ---- чтение вслух (этап 5) ----
    /** Какое задание на экране блокировки: TaskPicker.Mode (0 — примеры, 1 — чтение, 2 — вперемешку). */
    public int taskMode() { return sp.getInt("task_mode", 0); }
    public void setTaskMode(int v) { sp.edit().putInt("task_mode", v).apply(); }
    /** Точность для разблокировки чтением, %: 100 — все слова верно (красные перечитать), меньше — хватит доли верных слов. */
    public int readThresholdPct() { return sp.getInt("read_threshold", TaskPicker.READ_TO_END); }
    public void setReadThresholdPct(int v) { sp.edit().putInt("read_threshold", v).apply(); }
    /** Стартовый уровень текстов для нового чтеца (без истории). */
    /** Награда за новый уровень чтения: столько минут без заданий (0 — выключено). */
    public int levelUpBonusMin() { return sp.getInt("levelup_bonus_min", 30); }
    public void setLevelUpBonusMin(int v) { sp.edit().putInt("levelup_bonus_min", v).apply(); }
    /** До этого момента (System.currentTimeMillis) заданий нет: ни при включении экрана, ни по таймеру. */
    public long freeUntil() { return sp.getLong("free_until", 0); }
    public void setFreeUntil(long t) { sp.edit().putLong("free_until", t).commit(); }
    public long freeLeftMs() { return Math.max(0, freeUntil() - System.currentTimeMillis()); }

    /** Потолок громкости медиа, %: днём, вечером (21–9), ночью (23–7). 100 = без ограничения. */
    public int volDayPct() { return sp.getInt("vol_day", 100); }
    public int volEveningPct() { return sp.getInt("vol_evening", 50); }
    public int volNightPct() { return sp.getInt("vol_night", 30); }
    public void setVolumes(int day, int evening, int night) { sp.edit().putInt("vol_day", day).putInt("vol_evening", evening).putInt("vol_night", night).apply(); }

    public int readStartLevel() { return sp.getInt("read_start_level", 5); }
    public void setReadStartLevel(int v) { sp.edit().putInt("read_start_level", v).apply(); }
    /** Счётчик сеансов «вперемешку»: чётный — чтение, нечётный — примеры. */
    public int mixedCounter() { return sp.getInt("mixed_counter", 0); }
    public void bumpMixedCounter() { sp.edit().putInt("mixed_counter", mixedCounter() + 1).apply(); }
    /** NPU (QNN HTP) однажды уронил процесс или не загрузился — дальше только CPU, пока родитель не нажмёт «Подготовить». */
    public boolean htpBroken() { return sp.getBoolean("htp_broken", false); }
    public void setHtpBroken(boolean v) { sp.edit().putBoolean("htp_broken", v).commit(); }
}
