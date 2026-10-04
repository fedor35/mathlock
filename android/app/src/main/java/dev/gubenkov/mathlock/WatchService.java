package dev.gubenkov.mathlock;

import android.accessibilityservice.AccessibilityService;
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
        if (!p.enabled() || !p.locked()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastStart < 700) return;
        lastStart = now;
        LockService.showQuiz(this);
    }

    @Override public void onInterrupt() { }
}
