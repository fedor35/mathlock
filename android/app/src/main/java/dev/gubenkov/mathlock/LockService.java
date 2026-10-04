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

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Prefs p = new Prefs(c);
            String act = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                if (p.enabled()) {
                    if (!p.locked()) p.setScreenOffAt(SystemClock.elapsedRealtime());
                    p.setLocked(true);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
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

    static void showQuiz(Context c) {
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

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() { unregisterReceiver(screen); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
