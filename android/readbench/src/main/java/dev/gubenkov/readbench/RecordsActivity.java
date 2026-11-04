package dev.gubenkov.readbench;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import dev.gubenkov.reading.Leaderboard;
import dev.gubenkov.reading.SpeakerId;

/**
 * Семейная таблица рекордов: каждый чтец — карточка с аватаром, уровнем, лучшим темпом, звёздами за сегодня,
 * серией дней и числом прочитанных текстов. Без противостояния: сортировка по серии дней, затем по прочитанному сегодня.
 * Сверху — победитель последней дуэли (files/duels.jsonl).
 */
public class RecordsActivity extends Activity {
    KidUi.Palette pal;
    Profiles profiles;

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
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(pal.bg);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(18), dp(24), dp(18), dp(24));
        sv.addView(l); setContentView(sv);

        TextView title = text("Рекорды", 34, true, pal.text); title.setGravity(Gravity.CENTER); l.addView(title);

        JSONObject last = DuelActivity.lastDuel(getExternalFilesDir(null));
        if (last != null) {
            JSONObject w = last.optJSONObject("winner");
            TextView t = text("Последняя дуэль:  🏆 " + (w == null ? "—" : w.optString("name")), 18, false, pal.status);
            t.setGravity(Gravity.CENTER); t.setPadding(0, dp(6), 0, dp(14)); l.addView(t);
        } else { TextView sp = text("", 8, false, pal.status); sp.setPadding(0, 0, 0, dp(12)); l.addView(sp); }

        if (profiles.list.isEmpty()) {
            TextView t = text("Пока никого.\nПрочитай текст — и появишься здесь!", 20, false, pal.status); t.setGravity(Gravity.CENTER); l.addView(t);
        }
        long now = System.currentTimeMillis(); TimeZone tz = TimeZone.getDefault();
        List<Object[]> rows = new ArrayList<>();
        for (SpeakerId.Profile p : profiles.list) rows.add(new Object[]{p, Leaderboard.stats(profiles.attempts(p.id), now, tz), profiles.level(p).level()});
        rows.sort((x, y) -> {
            Leaderboard.Stats a = (Leaderboard.Stats) x[1], c = (Leaderboard.Stats) y[1];
            if (a.streak != c.streak) return c.streak - a.streak;
            if (a.today != c.today) return c.today - a.today;
            return c.total - a.total;
        });
        for (Object[] r : rows) l.addView(card((SpeakerId.Profile) r[0], (Leaderboard.Stats) r[1], (Integer) r[2]));
    }

    LinearLayout card(SpeakerId.Profile p, Leaderboard.Stats s, int level) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(pal.card); bg.setCornerRadius(dp(22)); card.setBackground(bg); card.setElevation(dp(1));
        card.addView(new KidUi.Avatar(this, p.color, p.avatar), new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(14), 0, 0, 0);
        TextView name = text(p.name, 24, true, pal.text); col.addView(name);
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < Math.min(5, s.stars()); i++) sb.append("⭐");
        String stars = s.stars() > 5 ? "⭐ × " + s.stars() : sb.toString();
        int awards = dev.gubenkov.reading.Achievements.count(dev.gubenkov.reading.Achievements.all(profiles.level(p), TimeZone.getDefault()));
        TextView today = text((stars.isEmpty() ? "сегодня ещё не читал" : stars) + (s.streak > 0 ? "   🔥 " + s.streak : "")
                + (awards > 0 ? "   🏅 " + awards : ""), 20, false, pal.text);
        today.setPadding(0, dp(2), 0, dp(4)); col.addView(today);
        col.addView(text(String.format(Locale.ROOT, "Уровень %d · %s", level,
                s.best > 0 ? String.format(Locale.ROOT, "лучший темп %.0f сл/мин", s.best) : "рекорда ещё нет"), 15, false, pal.status));
        col.addView(text(String.format(Locale.ROOT, "Прочитано %d · сегодня %d", s.total, s.today), 15, false, pal.status));
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        card.setOnClickListener(v -> startActivity(new android.content.Intent(this, AwardsActivity.class).putExtra("profile", p.id)));
        return card;
    }
}
