package dev.gubenkov.mathlock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран родителя: включение, разрешения, диапазон таблицы, число примеров, код. */
public class MainActivity extends Activity {
    private Prefs prefs;
    private LinearLayout perms;
    private EditText etMin, etMax, etNeed, etGrace, etCode, etInterval, etCheckCount;
    private TextView tvStat;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        Switch sw = new Switch(this);
        sw.setText("Разблокировка примерами");
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sw.setChecked(prefs.enabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            prefs.setEnabled(on);
            if (on) { prefs.setLocked(false); LockService.start(this); AdminReceiver.setupLockTask(this); }
            else { prefs.setLocked(false); stopService(new Intent(this, LockService.class)); }
        });
        root.addView(sw);

        root.addView(label("Разрешения"));
        perms = new LinearLayout(this);
        perms.setOrientation(LinearLayout.VERTICAL);
        root.addView(perms);

        root.addView(label("Таблица умножения"));
        etMin = field("Первый множитель от", prefs.minFactor(), root);
        etMax = field("до", prefs.maxFactor(), root);
        etNeed = field("Правильных ответов для разблокировки", prefs.needCorrect(), root);
        etGrace = field("Не спрашивать, если экран гас меньше (сек)", prefs.graceSeconds(), root);
        root.addView(label("Контрольные примеры во время игры"));
        etInterval = field("Каждые N минут работы экрана (0 = выключить)", prefs.checkIntervalMin(), root);
        etCheckCount = field("Примеров в контрольной проверке", prefs.checkCount(), root);
        root.addView(label("Родительский код (цифры; ввод вместо ответа снимает блокировку)"));
        etCode = new EditText(this);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCode.setText(prefs.parentCode());
        root.addView(etCode);

        Button save = new Button(this);
        save.setText("Сохранить");
        save.setOnClickListener(v -> save());
        root.addView(save);

        Button test = new Button(this);
        test.setText("Показать экран с примерами сейчас");
        test.setOnClickListener(v -> startActivity(new Intent(this, QuizActivity.class)));
        root.addView(test);

        tvStat = new TextView(this);
        root.addView(tvStat);

        TextView how = new TextView(this);
        how.setText("\nКак это работает: системную блокировку поставьте «Провести по экрану» (без PIN). "
                + "Когда экран гаснет, телефон считается заблокированным; при включении поверх всего появляются примеры. "
                + "Служба специальных возможностей возвращает примеры, если нажали «Домой». "
                + "Если приложение остановится, телефон просто откроется без примеров: ничего не ломается.\n\n"
                + "Настройки, центр безопасности и удаление приложений закрыты родительским кодом: при входе туда появляется экран кода, "
                + "после ввода 10 минут можно работать свободно. Чтобы удалить приложение: ввести код, в Настройках снять администратора устройства, затем удалить.");
        root.addView(how);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPerms();
        tvStat.setText("\nСтатистика: задано " + prefs.statAsked() + ", ошибок " + prefs.statWrong());
        if (prefs.enabled()) LockService.start(this);
    }

    private void refreshPerms() {
        perms.removeAllViews();
        permRow("Поверх других приложений", Settings.canDrawOverlays(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        permRow("Специальные возможности: «Таблица»", accessibilityOn(),
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        PowerManager pm = getSystemService(PowerManager.class);
        permRow("Без ограничений батареи", pm.isIgnoringBatteryOptimizations(getPackageName()),
                () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        android.app.admin.DevicePolicyManager dpm = getSystemService(android.app.admin.DevicePolicyManager.class);
        permRow("Администратор устройства (защита от удаления)", dpm.isAdminActive(AdminReceiver.cn(this)),
                () -> startActivity(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.cn(this))
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Чтобы ребёнок не смог удалить приложение. Снять: ввести родительский код, затем здесь же отключить.")));
        permRow("Владелец устройства (через adb, необязательно)", AdminReceiver.isOwner(this), null);
        TextView miui = new TextView(this);
        miui.setText("На MIUI дополнительно: в настройках приложения «Другие разрешения» → «Показывать на экране блокировки» и «Всплывающие окна в фоне», плюс «Автозапуск».");
        perms.addView(miui);
    }

    private boolean accessibilityOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void permRow(String name, boolean ok, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText((ok ? "✅ " : "❌ ") + name);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        if (!ok && fix != null) {
            Button bt = new Button(this);
            bt.setText("Дать");
            bt.setOnClickListener(v -> fix.run());
            row.addView(bt);
        }
        perms.addView(row);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText("\n" + s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        return t;
    }

    private EditText field(String name, int val, LinearLayout root) {
        TextView t = new TextView(this);
        t.setText(name);
        root.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(val));
        root.addView(e);
        return e;
    }

    private int num(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private void save() {
        int min = Math.max(1, Math.min(9, num(etMin, 2)));
        int max = Math.max(min, Math.min(9, num(etMax, 9)));
        prefs.setFactors(min, max);
        prefs.setNeedCorrect(Math.max(1, Math.min(20, num(etNeed, 3))));
        prefs.setGraceSeconds(Math.max(0, num(etGrace, 20)));
        prefs.setCheckIntervalMin(Math.max(0, num(etInterval, 30)));
        prefs.setCheckCount(Math.max(1, Math.min(20, num(etCheckCount, 1))));
        if (prefs.enabled()) LockService.start(this);
        String code = etCode.getText().toString().trim();
        if (!code.isEmpty() && !TextUtils.isDigitsOnly(code)) { Toast.makeText(this, "Код: только цифры", Toast.LENGTH_SHORT).show(); return; }
        prefs.setParentCode(code);
        etMin.setText(String.valueOf(min)); etMax.setText(String.valueOf(max));
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
