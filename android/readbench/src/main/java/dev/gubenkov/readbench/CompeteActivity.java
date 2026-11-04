package dev.gubenkov.readbench;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Меню «Соревнования»: две большие кнопки — «Дуэль» и «Рекорды». Открывается кубком с экрана чтения. */
public class CompeteActivity extends Activity {
    KidUi.Palette pal;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.setup(this);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setBackgroundColor(pal.bg);
        l.setGravity(Gravity.CENTER); l.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this); title.setText("Соревнования"); title.setTextSize(34); title.setTextColor(pal.text);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); title.setGravity(Gravity.CENTER); title.setPadding(0, 0, 0, dp(40));
        l.addView(title);
        l.addView(big(KidUi.Icon.DUEL, pal.duelBtn, "Дуэль", "кто читает лучше", v -> startActivity(new Intent(this, DuelActivity.class))));
        l.addView(big(KidUi.Icon.TROPHY, pal.recordsBtn, "Рекорды", "вся семья", v -> startActivity(new Intent(this, RecordsActivity.class))));
        setContentView(l);
    }

    /** Большая круглая кнопка с иконкой, под ней название и подпись. Нажимается и кружок, и подпись. */
    View big(KidUi.Icon icon, int color, String name, String sub, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER); c.setPadding(0, dp(12), 0, dp(24));
        KidUi.IconButton btn = new KidUi.IconButton(this, icon, color, android.graphics.Color.WHITE);
        c.addView(btn, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView t = new TextView(this); t.setText(name); t.setTextSize(28); t.setTextColor(pal.text); t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setPadding(0, dp(10), 0, 0); c.addView(t);
        TextView st = new TextView(this); st.setText(sub); st.setTextSize(16); st.setTextColor(pal.status); st.setGravity(Gravity.CENTER); c.addView(st);
        btn.setOnClickListener(click); c.setOnClickListener(click); c.setClickable(true);
        return c;
    }
}
