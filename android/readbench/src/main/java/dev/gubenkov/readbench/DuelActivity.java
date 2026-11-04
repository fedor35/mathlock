package dev.gubenkov.readbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.Leaderboard;
import dev.gubenkov.reading.ReadingText;
import dev.gubenkov.reading.Sounds;
import dev.gubenkov.reading.SpeakerId;

/**
 * Дуэль на одном тексте: выбирается уровень (по умолчанию — последнего чтеца) и случайный текст, затем все читают
 * его по очереди («Читает следующий» → ReadActivity с {@code duel=true}). Кто читал, решает тихое опознание по голосу;
 * неопознанные идут как «Игрок N». Таблица: время, слов/мин, точность, счёт = слов/мин × точность, места; у лидера корона.
 * «Итоги» — победитель, конфетти, игровой звук; дуэль дописывается в {@code files/duels.jsonl} (для «Рекордов»).
 *
 * <p>Отладка без микрофона: {@code --es text_id ru-L5-001 --es wavs a.wav,b.wav} — раунды берут файлы по очереди
 * (как {@code --es wav} в ReadActivity); с {@code --ez auto true} раунды запускаются сами.
 */
public class DuelActivity extends Activity {
    static final String TAG = ReadActivity.TAG;
    static final int REQ_ROUND = 7;

    KidUi.Palette pal;
    Profiles profiles;
    final List<JSONObject> texts = new ArrayList<>();
    final Leaderboard.Duel duel = new Leaderboard.Duel();
    final Random rnd = new Random();
    int level = ReadActivity.START_LEVEL;
    JSONObject text;
    boolean finished;
    int guests;   // сколько неопознанных «Игрок N» уже заведено
    String[] wavs; boolean auto; int round;

    TextView title, preview, hint;
    LinearLayout levels, table, actions;
    KidUi.IconButton nextTextBtn, startBtn;
    TextView finishBtn, startLabel;
    KidUi.ConfettiView confetti;
    TextView badge;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView text(String s, float size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.setup(this);
        File dir = getExternalFilesDir(null);
        profiles = new Profiles(dir);
        loadTexts(dir);
        SpeakerId.Profile last = profiles.last();
        if (last != null) level = profiles.level(last).level();
        if (getIntent().hasExtra("wavs")) wavs = getIntent().getStringExtra("wavs").split(",");
        auto = getIntent().getBooleanExtra("auto", false);

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(pal.bg);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(18), dp(22), dp(18), dp(20));
        sv.addView(l); root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        title = text("Дуэль", 34, true, pal.text); title.setGravity(Gravity.CENTER); l.addView(title);
        hint = text("Какой уровень?", 17, false, pal.status); hint.setGravity(Gravity.CENTER); hint.setPadding(0, dp(4), 0, dp(10)); l.addView(hint);

        // уровни L1…L9 — крупные круглые кнопки в две строки
        levels = new LinearLayout(this); levels.setOrientation(LinearLayout.VERTICAL); levels.setGravity(Gravity.CENTER);
        l.addView(levels); buildLevels();

        // текст дуэли: карточка с началом текста и кнопка «другой текст»
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.HORIZONTAL); tc.setGravity(Gravity.CENTER_VERTICAL);
        tc.setPadding(dp(18), dp(14), dp(12), dp(14));
        GradientDrawable card = new GradientDrawable(); card.setColor(pal.card); card.setCornerRadius(dp(22)); tc.setBackground(card);
        preview = text("", 18, false, pal.text); preview.setMaxLines(3); preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tc.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
        nextTextBtn = new KidUi.IconButton(this, KidUi.Icon.NEXT, pal.nextChip, pal.nextIcon);
        nextTextBtn.setOnClickListener(v -> { pickText(); showText(); });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(48), dp(48)); nlp.leftMargin = dp(8); tc.addView(nextTextBtn, nlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2); clp.topMargin = dp(14); l.addView(tc, clp);

        table = new LinearLayout(this); table.setOrientation(LinearLayout.VERTICAL); table.setPadding(0, dp(10), 0, 0); l.addView(table);

        // «Читает следующий» — большой микрофон; «Итоги» — текстовая кнопка под ним
        actions = new LinearLayout(this); actions.setOrientation(LinearLayout.VERTICAL); actions.setGravity(Gravity.CENTER); actions.setPadding(0, dp(16), 0, 0);
        startBtn = new KidUi.IconButton(this, KidUi.Icon.MIC, pal.accent, Color.WHITE);
        startBtn.setOnClickListener(v -> startRound());
        actions.addView(startBtn, new LinearLayout.LayoutParams(dp(120), dp(120)));
        startLabel = text("Читает следующий", 20, true, pal.text); startLabel.setGravity(Gravity.CENTER); startLabel.setPadding(0, dp(8), 0, dp(14));
        startLabel.setOnClickListener(v -> startRound()); actions.addView(startLabel);
        finishBtn = text("Итоги", 22, true, Color.WHITE); finishBtn.setGravity(Gravity.CENTER); finishBtn.setPadding(dp(40), dp(14), dp(40), dp(14));
        GradientDrawable fb = new GradientDrawable(); fb.setColor(pal.recordsBtn); fb.setCornerRadius(dp(30)); finishBtn.setBackground(fb);
        finishBtn.setOnClickListener(v -> finishDuel()); finishBtn.setVisibility(View.GONE); actions.addView(finishBtn);
        l.addView(actions);

        confetti = new KidUi.ConfettiView(this); root.addView(confetti, new FrameLayout.LayoutParams(-1, -1));
        badge = text("", 40, true, Color.WHITE); badge.setGravity(Gravity.CENTER); badge.setPadding(dp(32), dp(18), dp(32), dp(18));
        GradientDrawable bd = new GradientDrawable(); bd.setColor(pal.success); bd.setCornerRadius(dp(40)); badge.setBackground(bd);
        badge.setElevation(dp(8)); badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2); blp.gravity = Gravity.CENTER; blp.leftMargin = blp.rightMargin = dp(24);
        root.addView(badge, blp);
        setContentView(root);

        String want = getIntent().getStringExtra("text_id");
        if (want != null) for (JSONObject t : texts) if (t.optString("id").equals(want)) { text = t; level = t.optInt("level", level); }
        if (text == null) pickText();
        showText(); buildLevels(); showTable();
        if (auto && wavs != null) startBtn.postDelayed(this::startRound, 800);
    }

    void loadTexts(File dir) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, "texts.jsonl")), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) texts.add(new JSONObject(line));
        } catch (Exception e) { Log.w(TAG, "texts.jsonl: " + e); }
        if (texts.isEmpty()) try { texts.add(new JSONObject().put("id", "demo-1").put("level", 1).put("text", "Ма́ма мы́ла ра́му.")); } catch (Exception ignored) {}
    }

    void buildLevels() {
        levels.removeAllViews();
        boolean locked = !duel.rounds.isEmpty();
        hint.setText(locked ? "Все читают этот текст по очереди" : "Какой уровень?");
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER);
            for (int lv = row == 0 ? 1 : 6; lv <= (row == 0 ? 5 : 9); lv++) {
                final int L = lv; boolean sel = lv == level;
                TextView t = text(String.valueOf(lv), 22, true, sel ? Color.WHITE : pal.chipIcon); t.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(sel ? pal.accent : pal.chip); t.setBackground(bg);
                t.setAlpha(locked && !sel ? 0.35f : 1f);
                if (!locked) t.setOnClickListener(v -> { level = L; pickText(); showText(); buildLevels(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56)); lp.setMargins(dp(5), dp(4), dp(5), dp(4)); r.addView(t, lp);
            }
            levels.addView(r);
        }
    }

    /** Случайный текст выбранного уровня (ближайший, если таких нет). */
    void pickText() {
        List<JSONObject> cand = new ArrayList<>();
        for (int d = 0; d <= AdaptiveLevel.MAX && cand.isEmpty(); d++)
            for (JSONObject t : texts) if (Math.abs(t.optInt("level", 1) - level) == d && t != text) cand.add(t);
        if (cand.isEmpty()) cand.addAll(texts);
        text = cand.get(rnd.nextInt(cand.size()));
    }

    void showText() {
        ReadingText rt = new ReadingText(text.optString("text"));
        preview.setText(rt.words.size() + " слов · " + text.optString("text").replace(String.valueOf(ReadingText.ACUTE), ""));
        nextTextBtn.setVisibility(duel.rounds.isEmpty() ? View.VISIBLE : View.GONE);
    }

    void startRound() {
        if (finished) return;
        Intent i = new Intent(this, ReadActivity.class).putExtra("duel", true).putExtra("text_id", text.optString("id"));
        if (wavs != null) { if (round >= wavs.length) return; i.putExtra("wav", wavs[round]); }
        round++;
        startActivityForResult(i, REQ_ROUND);
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req != REQ_ROUND || res != RESULT_OK || d == null) return;
        profiles.load();
        String pid = d.getStringExtra("profile"); SpeakerId.Profile p = profiles.get(pid);
        String key, name;
        if (p != null) { key = p.id; name = p.name; }
        else { guests++; key = "guest" + guests; name = "Игрок " + guests; }
        Leaderboard.Round r = new Leaderboard.Round(key, name, d.getFloatExtra("seconds", 0), d.getFloatExtra("wcpm", 0), d.getFloatExtra("accuracy", 0));
        duel.add(r);
        Log.i(TAG, String.format(Locale.ROOT, "дуэль: раунд %d — %s (%s) %.1f с, %.0f сл/мин, %.0f%%, счёт %.0f", duel.rounds.size(), name, key, r.seconds, r.wcpm, r.accuracy * 100, r.score()));
        showText(); buildLevels(); showTable();
        if (auto && wavs != null && round < wavs.length) startBtn.postDelayed(this::startRound, 1500);
    }

    /** Аватар игрока: профиль — его цвет и эмодзи; гость — номер на сером. */
    View avatar(String key, int size) {
        SpeakerId.Profile p = profiles.get(key);
        KidUi.Avatar a = p != null ? new KidUi.Avatar(this, p.color, p.avatar)
                : new KidUi.Avatar(this, pal.night ? Color.rgb(90, 100, 120) : Color.rgb(150, 150, 165), key.replace("guest", ""));
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return a;
    }

    void showTable() {
        table.removeAllViews();
        List<Leaderboard.Row> rows = duel.table();
        finishBtn.setVisibility(rows.isEmpty() || finished ? View.GONE : View.VISIBLE);
        startBtn.setVisibility(finished ? View.GONE : View.VISIBLE); startLabel.setVisibility(finished ? View.GONE : View.VISIBLE);
        for (Leaderboard.Row row : rows) {
            Leaderboard.Round r = row.best; boolean leader = row.place == 1;
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(14), dp(12), dp(16), dp(12));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(pal.card); bg.setCornerRadius(dp(22));
            if (leader) bg.setStroke(dp(3), pal.gold);
            c.setBackground(bg);
            TextView place = text(leader ? "👑" : String.valueOf(row.place), 26, true, pal.status); place.setGravity(Gravity.CENTER);
            c.addView(place, new LinearLayout.LayoutParams(dp(40), -2));
            c.addView(avatar(r.key, 52));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(text(r.name + (row.rounds > 1 ? "  ×" + row.rounds : ""), 21, true, pal.text));
            col.addView(text(String.format(Locale.ROOT, "%.0f с · %.0f сл/мин · %.0f%%", r.seconds, r.wcpm, r.accuracy * 100), 15, false, pal.status));
            c.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView score = text(String.format(Locale.ROOT, "%.0f", r.score()), 30, true, leader ? pal.gold : pal.text); score.setGravity(Gravity.END);
            c.addView(score);
            if (!finished) c.setOnClickListener(v -> reassign(r.key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); table.addView(c, lp);
        }
    }

    /** Необязательная правка: кто на самом деле читал (тап по строке). Не спрашивается само. */
    void reassign(String key) {
        List<String> names = new ArrayList<>(); List<String[]> targets = new ArrayList<>();
        for (SpeakerId.Profile p : profiles.list) if (!p.id.equals(key)) { names.add(p.avatar + " " + p.name); targets.add(new String[]{p.id, p.name}); }
        for (int g = 1; g <= guests + 1; g++) if (!("guest" + g).equals(key)) { names.add("Игрок " + g); targets.add(new String[]{"guest" + g, "Игрок " + g}); }
        new AlertDialog.Builder(this).setTitle("Кто читал?")
                .setItems(names.toArray(new String[0]), (dlg, w) -> {
                    String[] t = targets.get(w);
                    if (t[0].startsWith("guest")) guests = Math.max(guests, Integer.parseInt(t[0].substring(5)));
                    duel.reassign(key, t[0], t[1]); showTable();
                }).setNegativeButton("Отмена", null).show();
    }

    void finishDuel() {
        Leaderboard.Round w = duel.winner(); if (w == null || finished) return;
        finished = true; showTable(); buildLevels();
        hint.setText("Дуэль окончена");
        badge.setText("🏆 " + w.name + "!");
        KidUi.play(Sounds.randomSuccess(44100, rnd), 44100);
        confetti.start(pal.confetti);
        badge.setVisibility(View.VISIBLE); badge.setAlpha(0f); badge.setScaleX(0.3f); badge.setScaleY(0.3f);
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .withEndAction(() -> badge.animate().alpha(0f).setStartDelay(2200).setDuration(500).withEndAction(() -> badge.setVisibility(View.GONE)));
        try {   // запись дуэли — для «Рекордов» (последняя дуэль: победитель)
            JSONArray rows = new JSONArray();
            for (Leaderboard.Row row : duel.table()) rows.put(new JSONObject().put("key", row.best.key).put("name", row.best.name).put("place", row.place)
                    .put("seconds", Math.round(row.best.seconds * 10) / 10.0).put("wcpm", Math.round(row.best.wcpm * 10) / 10.0)
                    .put("accuracy", Math.round(row.best.accuracy * 1000) / 1000.0).put("score", Math.round(row.best.score() * 10) / 10.0).put("rounds", row.rounds));
            JSONObject j = new JSONObject().put("time", System.currentTimeMillis()).put("text_id", text.optString("id")).put("level", level)
                    .put("winner", new JSONObject().put("key", w.key).put("name", w.name)).put("rows", rows);
            try (FileOutputStream o = new FileOutputStream(duelsFile(getExternalFilesDir(null)), true)) { o.write((j + "\n").getBytes(StandardCharsets.UTF_8)); }
            Log.i(TAG, "дуэль окончена: " + j);
        } catch (Exception e) { Log.w(TAG, "duels.jsonl: " + e); }
    }

    static File duelsFile(File dir) { return new File(dir, "duels.jsonl"); }

    /** Последняя записанная дуэль или null. */
    static JSONObject lastDuel(File dir) {
        List<String> ls = Profiles.lines(duelsFile(dir));
        try { return ls.isEmpty() ? null : new JSONObject(ls.get(ls.size() - 1)); } catch (Exception e) { return null; }
    }
}
