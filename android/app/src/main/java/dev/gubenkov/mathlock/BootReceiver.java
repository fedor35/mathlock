package dev.gubenkov.mathlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        if (!p.enabled()) return;
        p.setLocked(true);
        p.setScreenOffAt(0);
        LockService.start(c);
    }
}
