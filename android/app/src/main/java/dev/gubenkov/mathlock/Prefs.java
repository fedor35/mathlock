package dev.gubenkov.mathlock;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки и состояние «заблокирован/нет». Состояние хранится на диске, чтобы пережить смерть процесса. */
public final class Prefs {
    private static final String NAME = "mathlock";
    /** Чтение-правка-запись состояния суток идёт из службы (главный поток) и из распознавания (свой поток). */
    static final Object TIME_LOCK = new Object();
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context c) { ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c; sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }

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
    public long guardPassUntil() {
        // Пропуск считается от включения телефона: после перезагрузки старое значение оказывалось «в будущем» и открывало
        // Настройки без кода на часы (найдено 18.11 — телефон сел и перезагрузился). Больше 10 мин вперёд — недействителен.
        long v = sp.getLong("guard_pass_until", 0);
        return v > android.os.SystemClock.elapsedRealtime() + QuizActivity.GUARD_PASS_MS ? 0 : v;
    }
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
    /** Периоды «с-до» в минутах суток: вечер 21:00–9:00, ночь 23:00–7:00 (ночь важнее вечера). */
    public int eveFrom() { return sp.getInt("vol_eve_from", 21 * 60); }
    public int eveTo() { return sp.getInt("vol_eve_to", 9 * 60); }
    public int nightFrom() { return sp.getInt("vol_night_from", 23 * 60); }
    public int nightTo() { return sp.getInt("vol_night_to", 7 * 60); }
    public void setVolumeHours(int eveFrom, int eveTo, int nightFrom, int nightTo) {
        sp.edit().putInt("vol_eve_from", eveFrom).putInt("vol_eve_to", eveTo).putInt("vol_night_from", nightFrom).putInt("vol_night_to", nightTo).apply();
    }
    public void setVolumes(int day, int evening, int night) { sp.edit().putInt("vol_day", day).putInt("vol_evening", evening).putInt("vol_night", night).apply(); }

    /** Счётчик загрузок телефона изменился с прошлого раза → это загрузка; запоминает новый. */
    public boolean newBoot(int bootCount) {
        if (bootCount < 0) return true;
        int was = sp.getInt("boot_count", -1);
        if (was != bootCount) sp.edit().putInt("boot_count", bootCount).commit();
        return was >= 0 && was != bootCount;   // первый запуск этой версии — просто запомнить, не блокировать
    }

    /** Запас минут, накопленный дополнительными примерами: контрольная проверка отодвигается на столько. */
    public int checkBankMin() { return sp.getInt("check_bank", 0); }
    public void setCheckBankMin(int v) { sp.edit().putInt("check_bank", v).apply(); }
    public int bonusPerExampleMin() { return sp.getInt("bonus_per_example", 2); }
    public int bankMaxMin() { return sp.getInt("bank_max", 30); }
    public void setBonus(int perExample, int max) { sp.edit().putInt("bonus_per_example", perExample).putInt("bank_max", max).apply(); }

    public int readStartLevel() { return sp.getInt("read_start_level", 5); }
    public void setReadStartLevel(int v) { sp.edit().putInt("read_start_level", v).apply(); }
    /** Счётчик сеансов «вперемешку»: чётный — чтение, нечётный — примеры. */
    public int mixedCounter() { return sp.getInt("mixed_counter", 0); }
    public void bumpMixedCounter() { sp.edit().putInt("mixed_counter", mixedCounter() + 1).apply(); }
    /** NPU (QNN HTP) однажды уронил процесс или не загрузился — дальше только CPU, пока родитель не нажмёт «Подготовить». */
    public boolean htpBroken() { return sp.getBoolean("htp_broken", false); }
    public void setHtpBroken(boolean v) { sp.edit().putBoolean("htp_broken", v).commit(); }

    // ---- дневной лимит экранного времени (0.5.0, ScreenTime) ----
    public boolean limitOn() { return sp.getBoolean("limit_on", false); }
    public int limitBaseMin() { return sp.getInt("limit_base", 90); }
    public int limitEarnMaxMin() { return sp.getInt("limit_earn_max", 90); }
    /** Награда за прочитанный текст, мин (за пример — bonusPerExampleMin). */
    public int limitReadMin() { return sp.getInt("limit_read", 10); }
    /** Сколько минут добавляет родительский код на экране «время вышло». */
    public int limitParentMin() { return sp.getInt("limit_parent", 30); }
    public int limitResetHour() { return sp.getInt("limit_reset_hour", 6); }
    public void setLimit(boolean on, int base, int earnMax, int read, int parent, int resetHour) {
        sp.edit().putBoolean("limit_on", on).putInt("limit_base", base).putInt("limit_earn_max", earnMax)
                .putInt("limit_read", read).putInt("limit_parent", parent).putInt("limit_reset_hour", resetHour).apply();
    }
    public ScreenTime.Rules limitRules() { return new ScreenTime.Rules(limitBaseMin(), limitEarnMaxMin(), limitResetHour()); }

    /** Состояние суток, уже переведённое на сегодня. */
    public ScreenTime.State timeState() {
        ScreenTime.State s = new ScreenTime.State();
        s.day = sp.getInt("st_day", 0); s.usedSec = sp.getLong("st_used", 0); s.earnedMin = sp.getInt("st_earned", 0);
        s.extraMin = sp.getInt("st_extra", 0); s.warned = sp.getInt("st_warned", 0);
        int oldDay = s.day; long oldUsed = s.usedSec; int oldEarned = s.earnedMin, oldExtra = s.extraMin;
        int today = ScreenTime.dayKey(System.currentTimeMillis(), limitResetHour(), java.util.TimeZone.getDefault());
        if (sp.getInt("st_reset", -1) != limitResetHour()) {   // родитель сдвинул начало суток — сегодняшнее не обнулять
            if (oldDay != 0) s.day = today;
            sp.edit().putInt("st_reset", limitResetHour()).apply();
        }
        if (ScreenTime.roll(s, today)) {
            saveTimeState(s);
            if (oldDay != 0 && limitOn()) writeTimeLog("day " + oldDay, 0, oldUsed, oldEarned, oldExtra, -1);
        }
        return s;
    }
    public void saveTimeState(ScreenTime.State s) {
        sp.edit().putInt("st_day", s.day).putLong("st_used", s.usedSec).putInt("st_earned", s.earnedMin)
                .putInt("st_extra", s.extraMin).putInt("st_warned", s.warned).commit();
    }
    /**
     * Журнал дневного времени во внешних files/time.tsv (родитель забирает adb pull, чтобы подобрать базу и потолок):
     * когда, событие, минут, использовано мин, заработано, добавлено, осталось мин. «day N» — итог прошедших суток.
     */
    public void logTime(String kind, int min) {
        if (!limitOn()) return;
        ScreenTime.State s = timeState();
        writeTimeLog(kind, min, s.usedSec, s.earnedMin, s.extraMin, ScreenTime.leftSec(s, limitRules()) / 60);
    }

    private void writeTimeLog(String kind, int min, long usedSec, int earned, int extra, long leftMin) {
        try {
            java.io.File ext = ctx.getExternalFilesDir(null);
            if (ext == null) return;
            String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
            String line = when + "\t" + kind + "\t" + min + "\t" + usedSec / 60 + "\t" + earned + "\t" + extra + "\t" + leftMin + "\n";
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(ext, "time.tsv"), true)) {
                o.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** Лимит включён и время на сегодня вышло. */
    public boolean timeUp() { return limitOn() && ScreenTime.leftSec(timeState(), limitRules()) <= 0; }
    public long timeLeftSec() { return limitOn() ? ScreenTime.leftSec(timeState(), limitRules()) : 24 * 3600L; }
    /** Заработать минуты (с дневным потолком). @return сколько добавлено. */
    public int earnTime(int min) {
        if (!limitOn() || min <= 0) return 0;
        synchronized (TIME_LOCK) {
            ScreenTime.State s = timeState(); ScreenTime.Rules r = limitRules();
            int add = ScreenTime.earn(s, r, min); ScreenTime.rearmWarnings(s, r); saveTimeState(s);
            return add;
        }
    }
    public int earnTime(int min, String why) {
        int add = earnTime(min);
        logTime(why, add);
        return add;
    }
    /** Добавить минуты вне потолка (новый уровень чтения, родитель). */
    public void addExtraTime(int min) {
        synchronized (TIME_LOCK) {
            ScreenTime.State s = timeState(); ScreenTime.Rules r = limitRules();
            ScreenTime.addExtra(s, r, min); ScreenTime.rearmWarnings(s, r); saveTimeState(s);
        }
    }
    public void addExtraTime(int min, String why) {
        addExtraTime(min);
        logTime(why, min);
    }
}
