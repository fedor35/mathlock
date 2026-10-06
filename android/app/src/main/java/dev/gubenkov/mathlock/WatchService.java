package dev.gubenkov.mathlock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Если телефон «заблокирован», а на экране оказалось чужое приложение (ребёнок нажал «Домой»),
 * возвращаем экран с примерами. Содержимое окон не читаем.
 */
public class WatchService extends AccessibilityService {
    private long lastStart;

    /**
     * Систему спецвозможностей Android поднимает сам при загрузке, а BOOT_COMPLETED HyperOS до нас не доносит.
     * Поэтому именно отсюда после загрузки стартует основная служба и ставится блокировка.
     */
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        Prefs p = new Prefs(this);
        if (!p.enabled()) return;
        if (!LockService.running) {
            p.setLocked(true);
            p.setScreenOffAt(0);
            p.setNeedNow(p.needCorrect());
            LockService.start(this);
            LockService.showQuiz(this);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        Prefs p0 = new Prefs(this);
        if (p0.enabled() && !LockService.running) LockService.start(this);
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String s = pkg.toString();
        if (s.equals(getPackageName()) || s.equals("com.android.systemui") || s.startsWith("com.miui.securitycenter")) return;
        Prefs p = new Prefs(this);
        if (!p.enabled()) return;
        long now = SystemClock.elapsedRealtime();
        if (p.locked()) {
            if (now - lastStart < 700) return;
            lastStart = now;
            LockService.showQuiz(this);
            return;
        }
        // Телефон открыт, но ребёнок полез туда, где можно снять защиту: Настройки, центр безопасности, удаление.
        if (isGuarded(s) && now > p.guardPassUntil() && LockService.screenOn(this)) {
            if (now - lastStart < 700) return;
            lastStart = now;
            startActivity(new Intent(this, QuizActivity.class)
                    .putExtra(QuizActivity.EXTRA_GUARD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }

    static boolean isGuarded(String pkg) {
        return pkg.equals("com.android.settings") || pkg.equals("com.miui.securitycenter")
                || pkg.equals("com.miui.packageinstaller") || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.packageinstaller") || pkg.equals("com.miui.securityadd")
                || pkg.equals("com.miui.cleanmaster");
    }

    @Override public void onInterrupt() { }
}
