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

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
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
        if (isGuarded(s) && now > p.guardPassUntil()) {
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
