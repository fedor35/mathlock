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
    private EditText etMin, etMax, etNeed, etGrace, etCode, etInterval, etCheckCount, etThreshold, etStartLevel;
    private TextView tvStat, tvReading;
    private Button btInstallApk, btPrepare;
    /** Идёт установка пакета или подготовка модели (минуты) — вторую не запускаем. */
    private static volatile boolean readingBusy;
    private static final int REQ_MIC = 11, REQ_TREE = 12;

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
        buildReadingSection(root);
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

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); }

    @Override protected void onResume() {
        super.onResume();
        // Служебный вход для adb: am start -n …/.MainActivity --es wallpaper /sdcard/…jpg
        // Служебный вход для adb: --es clip "текст" кладёт текст в буфер обмена (adb не умеет кириллицу) и закрывается.
        String clip = getIntent().getStringExtra("clip");
        if (clip != null) {
            getIntent().removeExtra("clip");
            getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("text", clip));
            finish();
            return;
        }
        String wp = getIntent().getStringExtra("wallpaper");
        android.util.Log.i("MathLock", "onResume wallpaper extra=" + wp);
        if (wp != null) {
            getIntent().removeExtra("wallpaper");
            try (java.io.FileInputStream in = new java.io.FileInputStream(wp)) {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
                wm.setStream(in, null, true, android.app.WallpaperManager.FLAG_SYSTEM | android.app.WallpaperManager.FLAG_LOCK);
                Toast.makeText(this, "Обои установлены", Toast.LENGTH_SHORT).show();
                android.util.Log.i("MathLock", "wallpaper set OK");
            } catch (Exception e) { Toast.makeText(this, "Обои: " + e, Toast.LENGTH_LONG).show(); android.util.Log.w("MathLock", "wallpaper", e); }
        }
        refreshPerms();
        refreshReading();
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
        if (prefs.taskMode() != TaskPicker.Mode.MATH.ordinal())
            permRow("Микрофон (чтение вслух; без него — примеры)", micGranted(),
                    () -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC));
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
        prefs.setReadThresholdPct(TaskPicker.clampThreshold(num(etThreshold, 100)));
        prefs.setReadStartLevel(Math.max(1, Math.min(9, num(etStartLevel, 5))));
        etThreshold.setText(String.valueOf(prefs.readThresholdPct())); etStartLevel.setText(String.valueOf(prefs.readStartLevel()));
        if (prefs.enabled()) LockService.start(this);
        String code = etCode.getText().toString().trim();
        if (!code.isEmpty() && !TextUtils.isDigitsOnly(code)) { Toast.makeText(this, "Код: только цифры", Toast.LENGTH_SHORT).show(); return; }
        prefs.setParentCode(code);
        etMin.setText(String.valueOf(min)); etMax.setText(String.valueOf(max));
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------------- чтение вслух ----------------

    private boolean micGranted() { return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    private void buildReadingSection(LinearLayout root) {
        root.addView(label("Задание на экране блокировки"));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        String[] names = {"Примеры", "Чтение вслух", "Вперемешку (по очереди)"};
        for (int i = 0; i < names.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(names[i]); rb.setId(1000 + i);
            rg.addView(rb);
        }
        rg.check(1000 + TaskPicker.Mode.of(prefs.taskMode()).ordinal());
        rg.setOnCheckedChangeListener((g, id) -> {
            prefs.setTaskMode(id - 1000);
            if (id != 1000 && !micGranted()) requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            refreshPerms(); refreshReading();
        });
        root.addView(rg);

        tvReading = new TextView(this);
        root.addView(tvReading);

        Button tree = new Button(this);
        tree.setText("Установить пакет чтения из папки…");
        tree.setOnClickListener(v -> {
            if (readingBusy) return;
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE); }
            catch (Exception e) { Toast.makeText(this, "Нет системного выбора папок: " + e, Toast.LENGTH_LONG).show(); }
        });
        root.addView(tree);
        btInstallApk = new Button(this);
        btInstallApk.setText("Установить пакет чтения из приложения");
        btInstallApk.setOnClickListener(v -> runReadingJob("Копирую пакет…", () -> PackInstaller.installFromAssets(this, this::readingProgress), true));
        root.addView(btInstallApk);
        btPrepare = new Button(this);
        btPrepare.setText("Подготовить модель для NPU (несколько минут)");
        btPrepare.setOnClickListener(v -> runReadingJob("Готовлю модель для NPU, не закрывайте экран…", () -> ReadingEngine.prepare(this), false));
        root.addView(btPrepare);

        etThreshold = field("Точность для разблокировки чтением, % (100 = все слова верно, красные перечитать)", prefs.readThresholdPct(), root);
        etStartLevel = field("Стартовый уровень текстов для нового чтеца (1–9)", prefs.readStartLevel(), root);
        Button test = new Button(this);
        test.setText("Показать экран чтения сейчас");
        test.setOnClickListener(v -> startActivity(new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_TASK, "reading")));
        root.addView(test);
    }

    private void readingProgress(String s) { runOnUiThread(() -> { if (tvReading != null) tvReading.setText(s); }); }

    /** Долгая работа с пакетом в фоне; по окончании — статус и, после установки, подготовка модели для NPU. */
    private void runReadingJob(String title, java.util.function.Supplier<String> job, boolean thenPrepare) {
        if (readingBusy) return;
        readingBusy = true;
        tvReading.setText(title);
        new Thread(() -> {
            String r;
            try { r = job.get(); } catch (Throwable e) { r = "Ошибка: " + e; }
            String res = r;
            boolean prep = thenPrepare && ReadingEngine.pack(this).needsPrepare(prefs.htpBroken());
            if (prep) {
                readingProgress(res + "\nГотовлю модель для NPU, это несколько минут…");
                try { r = res + "\n" + ReadingEngine.prepare(this); } catch (Throwable e) { r = res + "\nОшибка: " + e; }
            }
            readingBusy = false;
            String fin = r;
            runOnUiThread(() -> { refreshReading(); Toast.makeText(this, fin, Toast.LENGTH_LONG).show(); tvReading.setText(fin + "\n\n" + tvReading.getText()); });
        }, "reading-job").start();
    }

    private void refreshReading() {
        if (tvReading == null) return;
        if (readingBusy) return;
        ReadingPack p = ReadingEngine.pack(this);
        boolean broken = prefs.htpBroken();
        StringBuilder sb = new StringBuilder();
        int texts = p.textCount();
        sb.append(texts > 0 ? "✅ Тексты: " + texts : "❌ Нет texts.jsonl").append('\n');
        java.util.List<ReadingPack.Candidate> now = p.plan(broken, false);
        sb.append(now.isEmpty() ? "❌ Модель: " + (p.plan(broken, true).isEmpty() ? "нет" : "не подготовлена для NPU") : "✅ Модель: " + now.get(0)).append('\n');
        if (broken) sb.append("⚠ NPU дал сбой — используется CPU. «Подготовить» попробует снова\n");
        sb.append(p.speaker() ? "✅ Голоса чтецов: есть" : "— Голоса чтецов: нет (один общий журнал)").append('\n');
        int tts = p.ttsCount();
        sb.append(tts > 0 ? "✅ Озвучка: " + tts + " текстов" : "— Озвучки нет (кнопка «послушать» скрыта)").append('\n');
        sb.append(micGranted() ? "✅ Микрофон" : "❌ Микрофон не разрешён").append('\n');
        boolean ready = micGranted() && p.ready(broken);
        if (ready) sb.append("Чтение доступно.");
        else if (prefs.taskMode() != TaskPicker.Mode.MATH.ordinal()) sb.append("Чтение недоступно — на экране блокировки будут примеры.");
        String d = ReadingEngine.describe();
        if (!d.isEmpty()) sb.append("\nПоследняя загрузка: ").append(d);
        sb.append("\n\nПакет — папка с texts.jsonl, моделями *.onnx и tts/. Скопируйте её на телефон (по кабелю в «Загрузки» или с флешки) и выберите кнопкой ниже. ")
          .append("Или adb push в ").append(p.dir == null ? "files/" : p.dir.getPath());
        tvReading.setText(sb);
        btInstallApk.setVisibility(PackInstaller.apkHasPack(this) ? View.VISIBLE : View.GONE);
        btPrepare.setVisibility(p.needsPrepare(false) || broken ? View.VISIBLE : View.GONE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri tree = data.getData();
            runReadingJob("Копирую пакет…", () -> PackInstaller.installFromTree(this, tree, this::readingProgress), true);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req == REQ_MIC) { refreshPerms(); refreshReading(); }
    }
}
