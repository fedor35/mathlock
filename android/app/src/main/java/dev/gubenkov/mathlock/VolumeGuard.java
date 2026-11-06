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
 * днём {@link Prefs#volDayPct}, вечером с 21 до 9 — {@link Prefs#volEveningPct}, ночью с 23 до 7 — {@link Prefs#volNightPct}.
 * Громче потолка поставить нельзя: кнопки громкости сразу откатываются. Звонок и будильник не трогаем.
 */
final class VolumeGuard {
    private static final String TAG = "VolumeGuard";
    static final int EVENING_FROM = 21, MORNING_TO = 9, NIGHT_FROM = 23, NIGHT_TO = 7;

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
    static int capPct(Prefs p, int hour) {
        if (hour >= NIGHT_FROM || hour < NIGHT_TO) return p.volNightPct();
        if (hour >= EVENING_FROM || hour < MORNING_TO) return p.volEveningPct();
        return p.volDayPct();
    }

    static int capIndex(int pct, int max) {
        if (pct >= 100) return max;
        return Math.max(1, Math.round(max * pct / 100f));
    }

    private void apply() {
        try {
            Prefs p = new Prefs(c);
            int pct = capPct(p, Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
            AudioManager am = c.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), cap = capIndex(pct, max);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur > cap) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0);
                Log.i(TAG, "громкость " + cur + " → " + cap + " из " + max + " (" + pct + "%)");
            }
        } catch (Throwable e) { Log.w(TAG, "громкость: " + e); }
    }

    /** Раз в минуту: смена периода (21:00, 23:00) срабатывает сама, даже если экран не включали. */
    private void tick() {
        apply();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 60_000);
    }
}
