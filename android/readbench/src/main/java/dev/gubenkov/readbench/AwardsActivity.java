package dev.gubenkov.readbench;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import dev.gubenkov.reading.Achievements;
import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.SpeakerId;

/**
 * «Мои награды»: открывается тапом по строке уровня. Сверху — аватары чтецов (переключение), счёт наград и
 * сколько осталось до следующего уровня; ниже — плитки: заработанные цветные (с датой), а в каждой серии
 * ещё и следующая ступень серой с подсказкой, как её получить. Награды выводятся из журнала (Achievements).
 */
public class AwardsActivity extends Activity {
    KidUi.Palette pal;
    Profiles profiles;
    LinearLayout body;
    String current;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView text(String s, float size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.setup(this);
        profiles = new Profiles(getExternalFilesDir(null));
        current = getIntent().getStringExtra("profile");
        if (profiles.get(current) == null && !profiles.list.isEmpty()) current = profiles.list.get(0).id;
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(pal.bg);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16), dp(24), dp(16), dp(24));
        sv.addView(l); setContentView(sv);
        TextView title = text("Награды", 34, true, pal.text); title.setGravity(Gravity.CENTER); l.addView(title);

        if (profiles.list.size() > 1) {   // чтецы: тап — чужие награды (дети любят сравнивать)
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER); row.setPadding(0, dp(10), 0, dp(4));
            for (SpeakerId.Profile p : profiles.list) {
                LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(dp(8), 0, dp(8), 0);
                c.addView(new KidUi.Avatar(this, p.color, p.avatar), new LinearLayout.LayoutParams(dp(52), dp(52)));
                TextView n = text(p.name, 13, false, pal.status); n.setGravity(Gravity.CENTER); c.addView(n);
                c.setTag(p.id);
                c.setOnClickListener(v -> { current = (String) v.getTag(); build(); });
                row.addView(c);
            }
            hs.addView(row); l.addView(hs, new LinearLayout.LayoutParams(-2, -2) {{ gravity = Gravity.CENTER_HORIZONTAL; }});
        }
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); l.addView(body);
        build();
    }

    void build() {
        body.removeAllViews();
        SpeakerId.Profile p = profiles.get(current);
        AdaptiveLevel lv = p != null ? profiles.level(p) : AdaptiveLevel.parse(ReadActivity.START_LEVEL, new ArrayList<>());
        List<Achievements.Badge> all = Achievements.all(lv, TimeZone.getDefault());
        int got = Achievements.count(all);

        TextView who = text((p != null ? p.avatar + " " + p.name : "Чтец") + "  ·  🏅 " + got, 24, true, pal.text);
        who.setGravity(Gravity.CENTER); who.setPadding(0, dp(12), 0, dp(4)); body.addView(who);
        TextView next = text(nextLevelLine(lv), 17, false, pal.status);
        next.setGravity(Gravity.CENTER); next.setPadding(0, 0, 0, dp(12)); body.addView(next);

        // в каждой серии — все заработанные и одна следующая ступень (не заваливать серыми плитками)
        List<Achievements.Badge> show = new ArrayList<>(); Set<String> lockedShown = new HashSet<>();
        for (Achievements.Badge b : all) {
            String series = b.id.substring(0, b.id.indexOf('_'));
            if (b.earned()) show.add(b);
            else if (lockedShown.add(series)) show.add(b);
        }
        // свежие награды — первыми
        show.sort((x, y) -> Long.compare(y.earnedAt, x.earnedAt));
        LinearLayout row = null; int col = 0;
        for (Achievements.Badge b : show) {
            if (col == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(170), 1); lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(tile(b), lp);
            col = (col + 1) % 3;
        }
        if (row != null) while (col != 0 && col < 3) { row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); col++; }
    }

    static String nextLevelLine(AdaptiveLevel lv) {
        int level = lv.level(), left = lv.toNextLevel();
        if (level >= AdaptiveLevel.MAX) return "Уровень " + level + " — самый высокий!";
        return String.format(Locale.ROOT, "Уровень %d · до уровня %d ещё %d %s быстрее %.0f слов в минуту",
                level, level + 1, left, left == 1 ? "текст" : "текста", AdaptiveLevel.target(level));
    }

    View tile(Achievements.Badge b) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(20));
        bg.setColor(b.earned() ? pal.card : pal.bg);
        if (!b.earned()) bg.setStroke(dp(2), pal.chip, dp(6), dp(4));
        t.setBackground(bg); if (b.earned()) t.setElevation(dp(2));
        TextView icon = text(b.icon, 40, false, pal.text); icon.setGravity(Gravity.CENTER);
        if (!b.earned()) icon.setAlpha(0.3f);
        t.addView(icon);
        TextView name = text(b.title, 14, true, b.earned() ? pal.text : pal.status); name.setGravity(Gravity.CENTER); name.setMaxLines(2);
        t.addView(name);
        String sub = b.earned() ? new SimpleDateFormat("d MMM", new Locale("ru")).format(new Date(b.earnedAt)) : b.hint;
        TextView s = text(sub, 11, false, pal.status); s.setGravity(Gravity.CENTER); s.setMaxLines(3); t.addView(s);
        if (!b.earned() && b.need > 0 && b.have > 0) {   // полоска прогресса: «19 / 25»
            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000); bar.setProgress(Math.round(1000 * Math.min(1f, b.have / b.need)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(pal.gold));
            t.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)) {{ topMargin = dp(4); leftMargin = dp(10); rightMargin = dp(10); }});
            TextView c = text(String.format(Locale.ROOT, "%.0f / %.0f", Math.floor(b.have), b.need), 11, true, pal.status); c.setGravity(Gravity.CENTER); t.addView(c);
        }
        return t;
    }
}
