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
            if (!p.enabled() || min <= 0 || p.limitOn()) return;   // с дневным лимитом контрольных нет: время копится заранее (просьба сына 18.11)
            int bank = p.checkBankMin();   // запас за дополнительные примеры — отодвигает проверку
            if (bank > 0) { p.setCheckBankMin(0); h.postDelayed(this, bank * 60_000L); return; }
            long free = p.freeLeftMs();   // бонусное время за новый уровень — проверка после него
            if (free > 0) { h.postDelayed(this, free + min * 60_000L); return; }
            if (!p.locked()) {
                accountTime(false);   // дотикать открытое время до блокировки
                p.setNeedNow(p.checkCount());
                p.setScreenOffAt(0);
                p.setLocked(true);
                showQuiz(LockService.this);
            }
            h.postDelayed(this, min * 60_000L);
        }
    };

    // ---- дневной лимит (ScreenTime): счёт времени, пока экран горит и телефон открыт ----
    static final long TICK_MS = 30_000L;
    private long lastTick;
    /** На прошлом тике телефон был открыт (не экран примеров/чтения) — интервал считается, только если открыт на обоих концах. */
    private boolean lastCountable;
    private final Runnable timeTick = new Runnable() {
        @Override public void run() { accountTime(true); }
    };

    /** Сейчас идёт время телефона: открыт и на экране не «Таблица» с заданием. */
    private static boolean countable(Prefs p) { return !p.locked() && !QuizActivity.visible; }

    /**
     * Учесть время с прошлого тика; time up — блокировка.
     * @param again перепланировать тик (false — только учесть: при гашении и перед блокировкой проверкой).
     */
    private void accountTime(boolean again) {
        if (again) h.removeCallbacks(timeTick);
        Prefs p = new Prefs(this);
        long now = SystemClock.elapsedRealtime();
        long delta = lastTick == 0 ? 0 : now - lastTick;
        boolean cnt = countable(p), was = lastCountable;
        lastTick = now; lastCountable = cnt;
        if (!p.enabled() || !p.limitOn()) { updateNotification(p); return; }
        int warn = 0; boolean up;
        synchronized (Prefs.TIME_LOCK) {
            ScreenTime.State st = p.timeState(); ScreenTime.Rules r = p.limitRules();
            if (cnt && was) ScreenTime.use(st, delta / 1000, 2 * TICK_MS / 1000);
            if (cnt) warn = ScreenTime.warnNow(st, r);   // пока на экране задание, предупреждение не тратим
            up = ScreenTime.leftSec(st, r) <= 0;
            if (up) ScreenTime.clampUsed(st, r);
            p.saveTimeState(st);
        }
        if (warn != 0) p.logTime(warn == ScreenTime.WARN_1 ? "warn1" : "warn5", 0);
        if (warn != 0) {
            String msg = warn == ScreenTime.WARN_1 ? "Осталась 1 минута! Потом можно заработать ещё" : "Осталось 5 минут телефона на сегодня";
            android.widget.Toast.makeText(this, "⏰ " + msg, android.widget.Toast.LENGTH_LONG).show();
        }
        if (up && !p.locked() && screenOn(this)) {
            Log.i(TAG, "дневное время вышло — блокировка");
            p.logTime("timeup", 0);
            p.setNeedNow(p.needCorrect()); p.setScreenOffAt(0); p.setLocked(true);
            showQuiz(this);
        }
        updateNotification(p);
        if (again && screenOn(this)) h.postDelayed(timeTick, TICK_MS);
    }

    /** Идёт звонок (звонит, разговор, связь в мессенджере) — экран заданий его не перекрывает. */
    static boolean inCall(Context c) {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        if (am == null) return false;
        int m = am.getMode();
        return m == android.media.AudioManager.MODE_RINGTONE || m == android.media.AudioManager.MODE_IN_CALL
                || m == android.media.AudioManager.MODE_IN_COMMUNICATION;
    }

    private void updateNotification(Prefs p) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, notification(p));
    }

    private Notification notification(Prefs p) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String title = "Таблица умножения включена";
        if (p.enabled() && p.limitOn()) {
            long left = p.timeLeftSec();
            title = left > 0 ? "Осталось сегодня: " + ScreenTime.fmt(left) : "Время на сегодня вышло";
        }
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void schedulePeriodic() {
        h.removeCallbacks(periodic);
        Prefs p = new Prefs(this);
        if (p.enabled() && p.checkIntervalMin() > 0 && !p.limitOn()) h.postDelayed(periodic, p.freeLeftMs() + p.checkIntervalMin() * 60_000L);
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Prefs p = new Prefs(c);
            String act = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                h.removeCallbacks(periodic);
                accountTime(false); lastTick = 0;
                if (p.enabled() && (p.freeLeftMs() == 0 || p.timeUp())) {   // в бонусное время гашение экрана не блокирует
                    if (!p.locked()) { p.setScreenOffAt(SystemClock.elapsedRealtime()); p.setNeedNow(p.needCorrect()); }
                    p.setLocked(true);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
                if (Intent.ACTION_SCREEN_ON.equals(act)) { schedulePeriodic(); AdbKeeper.start(c); lastTick = SystemClock.elapsedRealtime(); lastCountable = false; accountTime(true); }
                maybeShowQuiz(c);
            }
        }
    };

    static void maybeShowQuiz(Context c) {
        Prefs p = new Prefs(c);
        if (!p.enabled() || !p.locked()) return;
        long off = p.screenOffAt();
        if (off > 0 && !p.timeUp() && SystemClock.elapsedRealtime() - off < p.graceSeconds() * 1000L) {
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
        if (inCall(c)) { Log.d(TAG, "call in progress, quiz postponed"); return; }   // вернёт WatchService/SCREEN_ON после звонка
        Intent q = new Intent(c, QuizActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { c.startActivity(q); } catch (Exception e) { Log.w(TAG, "cannot start quiz", e); }
    }

    /** Пересчитать остаток и уведомление (после заработка, разблокировки, смены настроек). */
    public static void refresh(Context c) { if (running) start(c); }

    public static void start(Context c) {
        Intent i = new Intent(c, LockService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        MediaHush.recover(this);
        AdbKeeper.start(this);
        VolumeGuard.start(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Блокировка", NotificationManager.IMPORTANCE_MIN));
        Notification n = notification(new Prefs(this));
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
        if (getSystemService(android.os.PowerManager.class).isInteractive()) {
            schedulePeriodic();
            if (lastTick == 0) { lastTick = SystemClock.elapsedRealtime(); lastCountable = false; }
            accountTime(true);   // в т.ч. после разблокировки/заработка — сразу обновить остаток
        }
        return START_STICKY;
    }
    @Override public void onDestroy() { running = false; h.removeCallbacks(periodic); h.removeCallbacks(timeTick); unregisterReceiver(screen); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
