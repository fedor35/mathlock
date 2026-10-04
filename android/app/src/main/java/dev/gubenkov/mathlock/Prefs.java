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
}
