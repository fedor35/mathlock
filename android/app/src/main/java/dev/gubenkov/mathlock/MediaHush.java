package dev.gubenkov.mathlock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Log;

/**
 * Тишина на время чтения. Живые записи сына 06.11: у 10 из 22 попыток микрофон слышал не ребёнка, а видео
 * из NewPipe / звук Roblox с этого же телефона — распознавание путалось, часть попыток засчиталась «за видео».
 * <ul>
 * <li>{@link #hold}: пока открыт экран чтения — аудиофокус (NewPipe и прочие плееры ставят паузу);</li>
 * <li>{@link #mute}: пока идёт запись — STREAM_MUSIC без звука (игры, которые фокус не слушают).
 *     Флаг в prefs: если процесс умрёт с заглушённым звуком, {@link #recover} вернёт его при следующем старте.</li>
 * </ul>
 */
final class MediaHush {
    private static final String TAG = "MediaHush";
    private static final String KEY = "hush_muted";
    private static AudioFocusRequest focus;

    private MediaHush() { }

    static synchronized void hold(Context c) {
        if (focus != null) return;
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(ch -> { })
                    .build();
            int res = am.requestAudioFocus(r);
            focus = r;
            Log.i(TAG, "аудиофокус: " + res);
        } catch (Throwable e) { Log.w(TAG, "аудиофокус: " + e); }
    }

    static synchronized void release(Context c) {
        unmute(c);
        if (focus == null) return;
        try { c.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); } catch (Throwable ignored) { }
        focus = null;
    }

    static synchronized void mute(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return;   // уже без звука — не наше, не трогаем
            c.getSharedPreferences("mathlock", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).commit();
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        } catch (Throwable e) { Log.w(TAG, "mute: " + e); }
    }

    static synchronized void unmute(Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("mathlock", Context.MODE_PRIVATE);
            if (!sp.getBoolean(KEY, false)) return;
            c.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            sp.edit().remove(KEY).apply();
        } catch (Throwable e) { Log.w(TAG, "unmute: " + e); }
    }

    /** После падения процесса посреди записи. */
    static void recover(Context c) { unmute(c); }
}
