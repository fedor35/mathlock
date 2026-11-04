package dev.gubenkov.readbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.Leaderboard;

/**
 * Родителю: темп по читательским дням (последние 14 дней, где читали). Точки — все попытки (бледные — с точностью
 * ниже 90%), линия — лучший чистый темп дня, пунктир — цель текущего уровня, под осью — дата и число текстов.
 */
final class TempoChart extends View {
    final List<AdaptiveLevel.Attempt> h; final float target; final KidUi.Palette pal; final int color;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final int DAYS = 14;

    TempoChart(Context c, List<AdaptiveLevel.Attempt> history, float target, KidUi.Palette pal, int color) {
        super(c); this.h = history; this.target = target; this.pal = pal; this.color = color;
    }

    @Override protected void onDraw(Canvas cv) {
        float d = getResources().getDisplayMetrics().density;
        TimeZone tz = TimeZone.getDefault();
        TreeMap<Long, List<AdaptiveLevel.Attempt>> byDay = new TreeMap<>();
        for (AdaptiveLevel.Attempt a : h) byDay.computeIfAbsent(Leaderboard.day(a.time, tz), k -> new ArrayList<>()).add(a);
        List<Long> days = new ArrayList<>(byDay.keySet());
        if (days.size() > DAYS) days = days.subList(days.size() - DAYS, days.size());
        float W = getWidth(), H = getHeight(), left = 34 * d, right = 8 * d, top = 8 * d, bottom = 30 * d;
        float pw = W - left - right, ph = H - top - bottom;
        p.setTextSize(11 * d);
        if (days.isEmpty()) { p.setColor(pal.status); cv.drawText("пока нет попыток", left, top + ph / 2, p); return; }
        float max = target;
        for (long day : days) for (AdaptiveLevel.Attempt a : byDay.get(day)) max = Math.max(max, a.wcpm);
        max = (float) Math.ceil(max * 1.1f / 20f) * 20f;
        // сетка и подписи оси темпа
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1 * d); p.setPathEffect(null);
        for (int v = 0; v <= max; v += max > 100 ? 40 : 20) {
            float y = top + ph * (1 - v / max);
            p.setColor(pal.chip); cv.drawLine(left, y, W - right, y, p);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.status); cv.drawText(String.valueOf(v), 2 * d, y + 4 * d, p); p.setStyle(Paint.Style.STROKE);
        }
        // цель уровня
        float ty = top + ph * (1 - target / max);
        p.setColor(pal.success); p.setStrokeWidth(1.5f * d); p.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
        cv.drawLine(left, ty, W - right, ty, p); p.setPathEffect(null);
        float step = days.size() == 1 ? 0 : pw / (days.size() - 1);
        Path line = new Path(); boolean started = false;
        SimpleDateFormat df = new SimpleDateFormat("d.MM", Locale.ROOT);
        for (int i = 0; i < days.size(); i++) {
            float x = left + (days.size() == 1 ? pw / 2 : i * step);
            List<AdaptiveLevel.Attempt> as = byDay.get(days.get(i)); float best = 0;
            p.setStyle(Paint.Style.FILL);
            for (AdaptiveLevel.Attempt a : as) {
                boolean clean = a.accuracy >= 0.9f; if (clean) best = Math.max(best, a.wcpm);
                p.setColor(color); p.setAlpha(clean ? 110 : 40);
                cv.drawCircle(x, top + ph * (1 - a.wcpm / max), 3 * d, p);
            }
            p.setAlpha(255);
            if (best > 0) {
                float y = top + ph * (1 - best / max);
                if (!started) { line.moveTo(x, y); started = true; } else line.lineTo(x, y);
                cv.drawCircle(x, y, 4.5f * d, p);
            }
            p.setColor(pal.status); p.setTextAlign(Paint.Align.CENTER);
            // подписи дней: все, если влезают, иначе через одну
            if (days.size() <= 7 || i % 2 == 0 || i == days.size() - 1) {
                long noon = days.get(i) * Leaderboard.DAY_MS + 12 * 3600000L - tz.getOffset(days.get(i) * Leaderboard.DAY_MS);
                cv.drawText(df.format(new Date(noon)), x, H - 16 * d, p);
                cv.drawText(as.size() + " т.", x, H - 3 * d, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(color); cv.drawPath(line, p);
    }
}
