package dev.gubenkov.mathlock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Держит «Беспроводную отладку» включённой — чтобы родитель мог зайти по adb без похода к телефону.
 * Android сам выключает её при каждой смене точки доступа (BSSID: 2,4↔5 ГГц, mesh, переподключение во сне).
 * Работает только если родитель выдал по adb: {@code pm grant dev.gubenkov.mathlock android.permission.WRITE_SECURE_SETTINGS}
 * и включена «Отладка по USB». Без этого — ничего не делает (публичная сборка по умолчанию).
 * На незнакомой точке доступа система спросит «Разрешить в этой сети?» — не чаще раза в {@link #BACKOFF_MS}.
 */
final class AdbKeeper {
    private static final String TAG = "AdbKeeper";
    private static final String ADB_WIFI = "adb_wifi_enabled";
    static final long DELAY_MS = 5_000, REJECT_MS = 30_000, BACKOFF_MS = 30 * 60_000L;

    private static AdbKeeper inst;
    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastSet = -1, quietUntil;
    private final Runnable check = this::check;

    private AdbKeeper(Context c) { this.c = c.getApplicationContext(); }

    static synchronized void start(Context c) {
        if (inst != null) return;
        if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        inst = new AdbKeeper(c);
        inst.register();
    }

    private void register() {
        c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, new ContentObserver(h) {
            @Override public void onChange(boolean self) { onFlag(); }
        });
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { schedule(); }
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) { schedule(); }
                }, h);
        schedule();
        Log.i(TAG, "запущен");
    }

    private void onFlag() {
        if (get(ADB_WIFI) == 1) return;
        // выключили почти сразу после нашего включения — точка недоверенная (окно) или отказ: не настаивать
        if (lastSet > 0 && SystemClock.elapsedRealtime() - lastSet < REJECT_MS) {
            quietUntil = SystemClock.elapsedRealtime() + BACKOFF_MS;
            Log.i(TAG, "отладку выключили сразу после включения — пауза " + BACKOFF_MS / 60_000 + " мин");
        }
        schedule();
    }

    private void schedule() { h.removeCallbacks(check); h.postDelayed(check, DELAY_MS); }

    private void check() {
        long now = SystemClock.elapsedRealtime();
        if (now < quietUntil) { h.postDelayed(check, quietUntil - now); return; }
        if (get(Settings.Global.ADB_ENABLED) != 1 || get(ADB_WIFI) == 1 || !onWifi()) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), ADB_WIFI, 1);
            lastSet = now;
            Log.i(TAG, "беспроводная отладка включена снова");
        } catch (Throwable e) { Log.w(TAG, "не включить: " + e); }
    }

    private boolean onWifi() {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private int get(String k) { return Settings.Global.getInt(c.getContentResolver(), k, 0); }
}
