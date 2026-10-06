package dev.gubenkov.mathlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Служба переднего плана: следит за гашением/включением экрана.
 * Экран погас → помечаем «заблокирован». Экран включился → если заблокирован, показываем примеры.
 */
public class LockService extends Service {
    private static final String TAG = "MathLock";
    private static final String CH = "lock";
    /** Жива ли служба (процесс один, статики достаточно). */
    static volatile boolean running;

    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            Prefs p = new Prefs(LockService.this);
            int min = p.checkIntervalMin();
            if (!p.enabled() || min <= 0) return;
            if (!p.locked()) {
                p.setNeedNow(p.checkCount());
                p.setScreenOffAt(0);
                p.setLocked(true);
                showQuiz(LockService.this);
            }
            h.postDelayed(this, min * 60_000L);
        }
    };

    private void schedulePeriodic() {
        h.removeCallbacks(periodic);
        Prefs p = new Prefs(this);
        if (p.enabled() && p.checkIntervalMin() > 0) h.postDelayed(periodic, p.checkIntervalMin() * 60_000L);
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Prefs p = new Prefs(c);
            String act = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                h.removeCallbacks(periodic);
                if (p.enabled()) {
                    if (!p.locked()) { p.setScreenOffAt(SystemClock.elapsedRealtime()); p.setNeedNow(p.needCorrect()); }
                    p.setLocked(true);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
                if (Intent.ACTION_SCREEN_ON.equals(act)) schedulePeriodic();
                maybeShowQuiz(c);
            }
        }
    };

    static void maybeShowQuiz(Context c) {
        Prefs p = new Prefs(c);
        if (!p.enabled() || !p.locked()) return;
        long off = p.screenOffAt();
        if (off > 0 && SystemClock.elapsedRealtime() - off < p.graceSeconds() * 1000L) {
            Log.d(TAG, "grace period, unlock without quiz");
            p.setLocked(false);
            return;
        }
        showQuiz(c);
    }

    /** Экран горит? Пока он погашен, примеры не показываем — их покажет SCREEN_ON. */
    static boolean screenOn(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    static void showQuiz(Context c) {
        // После гашения кнопкой приходят события окон (WatchService) и onStop квиза —
        // запуск квиза в этот момент будил телефон.
        if (!screenOn(c)) { Log.d(TAG, "screen off, quiz postponed to SCREEN_ON"); return; }
        Intent q = new Intent(c, QuizActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { c.startActivity(q); } catch (Exception e) { Log.w(TAG, "cannot start quiz", e); }
    }

    public static void start(Context c) {
        Intent i = new Intent(c, LockService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Блокировка", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Таблица умножения включена")
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Вызывается и после «Сохранить» в настройках: перечитать интервал.
        if (getSystemService(android.os.PowerManager.class).isInteractive()) schedulePeriodic();
        return START_STICKY;
    }
    @Override public void onDestroy() { running = false; h.removeCallbacks(periodic); unregisterReceiver(screen); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
