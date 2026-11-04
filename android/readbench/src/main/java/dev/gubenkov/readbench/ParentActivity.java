package dev.gubenkov.readbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.SpeakerId;

/**
 * Скрытый экран для родителей (долгое нажатие на строку уровня): чтецы, их попытки, уровень и рекорд;
 * имя, ребёнок/взрослый, объединение двух профилей одного человека, ручная переразметка попыток.
 * Ребёнку опознание не показывается нигде, только здесь.
 */
public class ParentActivity extends Activity {
    static final String TAG = ReadActivity.TAG;
    /** Сколько последних попыток показывать для переразметки. */
    static final int RECENT = 40;

    Profiles profiles;
    KidUi.Palette pal;
    LinearLayout list;
    MediaPlayer player;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.of(this);
        getWindow().setStatusBarColor(pal.bg); getWindow().setNavigationBarColor(pal.bg);
        if (!pal.night) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(pal.bg);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(16), dp(24), dp(16), dp(24));
        sv.addView(list);
        setContentView(sv);
        profiles = new Profiles(getExternalFilesDir(null));
        build();
    }

    TextView text(String s, float size, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(bold ? pal.text : pal.status);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }

    String nameOf(String id) {
        if (id == null) return "не определён";
        SpeakerId.Profile p = profiles.get(id); return p == null ? id : p.avatar + " " + p.name;
    }

    static String kindRu(String k) { return SpeakerId.CHILD.equals(k) ? "ребёнок" : SpeakerId.ADULT.equals(k) ? "взрослый" : "тип неизвестен"; }

    void build() {
        list.removeAllViews();
        list.addView(text("Чтецы", 26, true));
        list.addView(text("Опознаются по голосу после каждого чтения. Ребёнку это не показывается.", 14, false));
        if (profiles.list.isEmpty()) list.addView(text("\nПока никого: профиль появится после первого прочитанного текста.", 16, false));
        for (SpeakerId.Profile p : profiles.list) {
            AdaptiveLevel a = profiles.level(p); int lvl = a.level(); float best = a.best(1);
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(8));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(pal.card); bg.setCornerRadius(dp(18)); card.setBackground(bg);
            TextView h = text(p.avatar + "  " + p.name + (p.id.equals(profiles.lastId) ? "  · читал последним" : ""), 20, true);
            h.setTextColor(p.color);
            card.addView(h);
            card.addView(text(String.format(Locale.ROOT, "%s · попыток %d · уровень %d · рекорд %s · образцов голоса %d",
                    kindRu(p.kind), a.history.size(), lvl, best > 0 ? String.format(Locale.ROOT, "%.0f сл/мин", best) : "—", p.n), 15, false));
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.START);
            row.addView(button("Имя", v -> rename(p)));
            row.addView(button(SpeakerId.ADULT.equals(p.kind) ? "→ ребёнок" : "→ взрослый", v -> {
                profiles.setKind(p, SpeakerId.ADULT.equals(p.kind) ? SpeakerId.CHILD : SpeakerId.ADULT); build();
            }));
            if (profiles.list.size() > 1) row.addView(button("Объединить…", v -> mergeDialog(p)));
            card.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
            list.addView(card, lp);
        }
        // последние попытки всех чтецов и «не определённые» — нажатие: послушать / переложить
        List<Object[]> all = new ArrayList<>();
        for (SpeakerId.Profile p : profiles.list) for (AdaptiveLevel.Attempt a : profiles.attempts(p.id)) all.add(new Object[]{p.id, a});
        int unknown = 0;
        for (AdaptiveLevel.Attempt a : profiles.attempts(null)) { all.add(new Object[]{null, a}); unknown++; }
        all.sort((x, y) -> Long.compare(((AdaptiveLevel.Attempt) y[1]).time, ((AdaptiveLevel.Attempt) x[1]).time));
        TextView hh = text("\nПопытки" + (unknown > 0 ? " (не определено: " + unknown + ")" : ""), 22, true);
        list.addView(hh);
        list.addView(text("Нажми на попытку, чтобы послушать или отдать другому чтецу.", 14, false));
        SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT);
        for (int i = 0; i < Math.min(RECENT, all.size()); i++) {
            String id = (String) all.get(i)[0]; AdaptiveLevel.Attempt a = (AdaptiveLevel.Attempt) all.get(i)[1];
            String cos = "";
            if (a.rec != null) {
                JSONObject sp = profiles.speakerOf(a.rec);
                if (sp != null && sp.optJSONObject("cos") != null) {
                    StringBuilder s = new StringBuilder(); JSONObject c = sp.optJSONObject("cos");
                    for (Iterator<String> it = c.keys(); it.hasNext(); ) { String k = it.next(); s.append(' ').append(k).append('=').append(c.optString(k)); }
                    cos = "\n   речь " + sp.optString("speech_s") + " с, сходство:" + s;
                }
            }
            TextView t = text(String.format(Locale.ROOT, "%s · %s · %.0f сл/мин · %.0f%% · %s%s", df.format(new Date(a.time)), a.textId,
                    a.wcpm, a.accuracy * 100, nameOf(id), cos), 15, id == null);
            t.setPadding(0, dp(10), 0, dp(4));
            if (a.rec != null) t.setOnClickListener(v -> attemptDialog(id, a));
            list.addView(t);
        }
    }

    void rename(SpeakerId.Profile p) {
        EditText e = new EditText(this); e.setText(p.name); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Имя чтеца").setView(e)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String n = e.getText().toString().trim(); if (n.isEmpty()) return;
                    p.name = n; profiles.save(p); build();
                }).setNegativeButton("Отмена", null).show();
    }

    void mergeDialog(SpeakerId.Profile from) {
        List<SpeakerId.Profile> others = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (SpeakerId.Profile p : profiles.list) if (p != from) { others.add(p); names.add(p.avatar + " " + p.name); }
        new AlertDialog.Builder(this).setTitle("«" + from.name + "» — это тот же человек, что…")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    SpeakerId.Profile into = others.get(w);
                    new AlertDialog.Builder(this).setMessage("Объединить «" + from.name + "» в «" + into.name + "»? Попытки и голос сольются.")
                            .setPositiveButton("Объединить", (d2, w2) -> { profiles.merge(into, from); build(); })
                            .setNegativeButton("Отмена", null).show();
                }).show();
    }

    /** Попытка: послушать запись или отдать другому чтецу (ручная правка дообучает профиль сильнее, чем авто). */
    void attemptDialog(String fromId, AdaptiveLevel.Attempt a) {
        List<String> items = new ArrayList<>(); items.add("▶ Послушать");
        List<SpeakerId.Profile> targets = new ArrayList<>();
        for (SpeakerId.Profile p : profiles.list) if (!p.id.equals(fromId)) { targets.add(p); items.add("→ " + p.avatar + " " + p.name); }
        items.add("→ новый чтец");
        if (fromId != null) items.add("→ не определён");
        new AlertDialog.Builder(this).setTitle(a.textId + ": " + nameOf(fromId))
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) { play(a.rec); attemptDialog(fromId, a); return; }
                    float[] e = profiles.embOf(a.rec);
                    String to;
                    if (w <= targets.size()) {
                        SpeakerId.Profile p = targets.get(w - 1); to = p.id;
                        if (e != null) { SpeakerId.learn(p, e, true); profiles.save(p); }
                    } else if (w == targets.size() + 1) {
                        if (e == null) return;
                        to = profiles.create(e).id;
                    } else to = null;
                    if (profiles.move(a.rec, fromId, to)) Log.i(TAG, "переразметка: " + a.rec + " " + fromId + " → " + to);
                    build();
                }).show();
    }

    void play(String rec) {
        try {
            if (player != null) player.release();
            player = new MediaPlayer(); player.setDataSource(new File(getExternalFilesDir(null), "recordings/" + rec + ".wav").getPath());
            player.prepare(); player.start();
        } catch (Exception e) { Log.w(TAG, "play " + rec + ": " + e); }
    }

    @Override protected void onDestroy() { if (player != null) player.release(); super.onDestroy(); }
}
