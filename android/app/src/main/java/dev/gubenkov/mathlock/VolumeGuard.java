package dev.gubenkov.mathlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;

/**
 * Потолок громкости медиа (видео, игры, музыка) по времени суток — просьба Фёдора 07.11:
 * вечером ({@link Prefs#eveFrom}–{@link Prefs#eveTo}, по умолчанию 21–9) — {@link Prefs#volEveningPct}, ночью (23–7) —
 * {@link Prefs#volNightPct}, в остальное время — {@link Prefs#volDayPct}. Часы — в родительских настройках.
 * Процент — от шкалы ползунка (50% = ползунок на середине).
 * Громче потолка поставить нельзя: кнопки громкости сразу откатываются. Звонок и будильник не трогаем.
 */
final class VolumeGuard {
    private static final String TAG = "VolumeGuard";

    private static VolumeGuard inst;
    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;

    private VolumeGuard(Context c) { this.c = c.getApplicationContext(); }

    static synchronized void start(Context c) {
        if (inst != null) { inst.apply(); return; }
        inst = new VolumeGuard(c);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(Intent.ACTION_HEADSET_PLUG);   // у наушников своя громкость
        f.addAction(Intent.ACTION_SCREEN_ON);
        inst.c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { inst.h.post(inst::apply); }
        }, f);
        inst.tick();
    }

    /** Потолок сейчас, % (100 — без ограничения). */
    static int capPct(Prefs p, int minute) {
        return capPct(minute, p.eveFrom(), p.eveTo(), p.nightFrom(), p.nightTo(), p.volDayPct(), p.volEveningPct(), p.volNightPct());
    }

    static int capPct(int m, int eveFrom, int eveTo, int nightFrom, int nightTo, int day, int eve, int night) {
        if (in(m, nightFrom, nightTo)) return night;
        if (in(m, eveFrom, eveTo)) return eve;
        return day;
    }

    /** m в [from, to) с переходом через полночь; from == to — период выключен. */
    static boolean in(int m, int from, int to) {
        if (from == to) return false;
        return from < to ? m >= from && m < to : m >= from || m < to;
    }

    /** «21», «21:30», «9.00» → минуты суток; -1 — не разобрать. */
    static int parseTime(String s) {
        try {
            String[] a = s.trim().split("[:.]");
            int hh = Integer.parseInt(a[0].trim()), mm = a.length > 1 ? Integer.parseInt(a[1].trim()) : 0;
            if (hh == 24 && mm == 0) return 0;
            return hh >= 0 && hh < 24 && mm >= 0 && mm < 60 ? hh * 60 + mm : -1;
        } catch (Exception e) { return -1; }
    }

    /** «21-9», «21:30 – 9:00» → {от, до} в минутах; null — не разобрать. */
    static int[] parseRange(String s) {
        String[] a = s.replace('–', '-').replace('—', '-').split("-");
        if (a.length != 2) return null;
        int f = parseTime(a[0]), t = parseTime(a[1]);
        return f < 0 || t < 0 ? null : new int[]{f, t};
    }

    static String fmtRange(int from, int to) { return fmt(from) + "-" + fmt(to); }
    static String fmt(int m) { return m % 60 == 0 ? String.valueOf(m / 60) : String.format(java.util.Locale.ROOT, "%d:%02d", m / 60, m % 60); }

    static int capIndex(int pct, int max) {
        if (pct >= 100) return max;
        return Math.max(1, Math.round(max * pct / 100f));
    }

    private void apply() {
        try {
            Prefs p = new Prefs(c);
            Calendar now = Calendar.getInstance();
            int pct = capPct(p, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
            AudioManager am = c.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), cap = capIndex(pct, max);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur > cap) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0);
                Log.i(TAG, "громкость " + cur + " → " + cap + " из " + max + " (" + pct + "%)");
            }
        } catch (Throwable e) { Log.w(TAG, "громкость: " + e); }
    }

    /** Раз в минуту: смена периода (вечер, ночь) срабатывает сама, даже если экран не включали. */
    private void tick() {
        apply();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 60_000);
    }
}
