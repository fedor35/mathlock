package dev.gubenkov.readbench;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Детский интерфейс экрана чтения: палитра (день/ночь), круглые кнопки с нарисованными иконками
 * (без эмодзи и подписей — ребёнок читать ещё не умеет), пульс «слушаю» и конфетти на успех.
 * Только стандартный Android: никаких зависимостей.
 */
final class KidUi {
    private KidUi() {}

    /** Цвета экрана. Слоги — два контрастных цвета, читаемых на фоне карточки. */
    static final class Palette {
        final boolean night;
        final int bg, card, text, status, accent, listening, chip, chipIcon, nextChip, nextIcon;
        final int syl0, syl1, okBg, wrongBg, curBg, debug, success;
        final int[] confetti;

        Palette(boolean night) {
            this.night = night;
            if (night) {
                bg = Color.rgb(20, 26, 38); card = Color.rgb(30, 38, 54); text = Color.rgb(240, 238, 230); status = Color.rgb(168, 176, 192);
                accent = Color.rgb(91, 141, 239); listening = Color.rgb(240, 99, 74);
                chip = Color.rgb(43, 53, 71); chipIcon = Color.rgb(208, 214, 224);
                nextChip = Color.rgb(43, 53, 71); nextIcon = Color.rgb(140, 200, 150);
                syl0 = Color.rgb(143, 180, 255); syl1 = Color.rgb(255, 155, 138);
                okBg = Color.rgb(36, 74, 42); wrongBg = Color.rgb(90, 42, 42); curBg = Color.rgb(90, 74, 26);
                debug = Color.rgb(110, 120, 140); success = Color.rgb(60, 170, 100);
            } else {
                bg = Color.rgb(255, 248, 231); card = Color.WHITE; text = Color.rgb(30, 30, 42); status = Color.rgb(91, 91, 107);
                accent = Color.rgb(59, 111, 217); listening = Color.rgb(232, 85, 61);
                chip = Color.rgb(239, 230, 208); chipIcon = Color.rgb(74, 74, 88);
                nextChip = Color.rgb(215, 240, 220); nextIcon = Color.rgb(40, 120, 70);
                syl0 = Color.rgb(31, 63, 158); syl1 = Color.rgb(184, 48, 31);
                okBg = Color.rgb(201, 240, 201); wrongBg = Color.rgb(255, 208, 208); curBg = Color.rgb(255, 233, 168);
                debug = Color.GRAY; success = Color.rgb(46, 160, 90);
            }
            confetti = new int[]{Color.rgb(255, 196, 0), Color.rgb(255, 87, 87), Color.rgb(72, 199, 142),
                    Color.rgb(80, 150, 255), Color.rgb(255, 128, 200), Color.rgb(170, 110, 255)};
        }

        static Palette of(Context c) {
            int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return new Palette(m == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    enum Icon { MIC, STOP, SPEAKER, PERSON, COMPARE, NEXT }

    /** Круглая кнопка: фон + иконка векторами. В режиме pulsing вокруг расходятся кольца («слушаю»). */
    static final class IconButton extends View {
        Icon icon; int bg, fg;
        private float pulse = 0; private ValueAnimator anim;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        IconButton(Context c, Icon icon, int bg, int fg) { super(c); this.icon = icon; this.bg = bg; this.fg = fg; setClickable(true); }

        void set(Icon icon, int bg, int fg) { this.icon = icon; this.bg = bg; this.fg = fg; invalidate(); }

        boolean pulsing() { return anim != null; }

        void setPulsing(boolean on) {
            if (on == pulsing()) return;
            if (on) {
                anim = ValueAnimator.ofFloat(0, 1); anim.setDuration(1400); anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
                anim.start();
            } else { anim.cancel(); anim = null; invalidate(); }
        }

        @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.35f); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) / 2 * (pulsing() ? 0.70f : 0.90f);
            if (pulsing()) {
                p.setStyle(Paint.Style.FILL);
                for (int k = 0; k < 2; k++) {
                    float ph = (pulse + k * 0.5f) % 1f;
                    p.setColor(bg); p.setAlpha((int) (110 * (1 - ph)));
                    cv.drawCircle(cx, cy, r * (1 + 0.42f * ph), p);
                }
            }
            p.setStyle(Paint.Style.FILL); p.setColor(bg); p.setAlpha(isPressed() ? 200 : 255);
            cv.drawCircle(cx, cy, r, p);
            p.setColor(fg); p.setAlpha(255); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(r * 0.14f);
            switch (icon) {
                case MIC:
                    p.setStyle(Paint.Style.FILL);
                    rect.set(cx - 0.2f * r, cy - 0.62f * r, cx + 0.2f * r, cy + 0.1f * r); cv.drawRoundRect(rect, 0.2f * r, 0.2f * r, p);
                    p.setStyle(Paint.Style.STROKE);
                    rect.set(cx - 0.4f * r, cy - 0.35f * r, cx + 0.4f * r, cy + 0.3f * r); cv.drawArc(rect, 0, 180, false, p);
                    cv.drawLine(cx, cy + 0.3f * r, cx, cy + 0.55f * r, p);
                    cv.drawLine(cx - 0.22f * r, cy + 0.55f * r, cx + 0.22f * r, cy + 0.55f * r, p);
                    break;
                case STOP:
                    p.setStyle(Paint.Style.FILL);
                    rect.set(cx - 0.36f * r, cy - 0.36f * r, cx + 0.36f * r, cy + 0.36f * r); cv.drawRoundRect(rect, 0.1f * r, 0.1f * r, p);
                    break;
                case SPEAKER:
                    p.setStyle(Paint.Style.FILL); path.reset();
                    path.moveTo(cx - 0.55f * r, cy - 0.2f * r); path.lineTo(cx - 0.3f * r, cy - 0.2f * r); path.lineTo(cx + 0.02f * r, cy - 0.5f * r);
                    path.lineTo(cx + 0.02f * r, cy + 0.5f * r); path.lineTo(cx - 0.3f * r, cy + 0.2f * r); path.lineTo(cx - 0.55f * r, cy + 0.2f * r); path.close();
                    cv.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.11f);
                    rect.set(cx - 0.26f * r, cy - 0.28f * r, cx + 0.3f * r, cy + 0.28f * r); cv.drawArc(rect, -45, 90, false, p);
                    rect.set(cx - 0.5f * r, cy - 0.52f * r, cx + 0.54f * r, cy + 0.52f * r); cv.drawArc(rect, -45, 90, false, p);
                    break;
                case PERSON:
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy - 0.27f * r, 0.24f * r, p);
                    rect.set(cx - 0.5f * r, cy + 0.08f * r, cx + 0.5f * r, cy + 0.95f * r); cv.drawArc(rect, 180, 180, true, p);
                    break;
                case COMPARE:
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.12f);
                    cv.drawLine(cx - 0.5f * r, cy - 0.22f * r, cx + 0.5f * r, cy - 0.22f * r, p);
                    cv.drawLine(cx + 0.5f * r, cy - 0.22f * r, cx + 0.25f * r, cy - 0.45f * r, p);
                    cv.drawLine(cx + 0.5f * r, cy - 0.22f * r, cx + 0.25f * r, cy + 0.01f * r, p);
                    cv.drawLine(cx + 0.5f * r, cy + 0.22f * r, cx - 0.5f * r, cy + 0.22f * r, p);
                    cv.drawLine(cx - 0.5f * r, cy + 0.22f * r, cx - 0.25f * r, cy - 0.01f * r, p);
                    cv.drawLine(cx - 0.5f * r, cy + 0.22f * r, cx - 0.25f * r, cy + 0.45f * r, p);
                    break;
                case NEXT:
                    p.setStyle(Paint.Style.FILL_AND_STROKE); p.setStrokeWidth(r * 0.1f); path.reset();
                    path.moveTo(cx - 0.28f * r, cy - 0.42f * r); path.lineTo(cx + 0.38f * r, cy); path.lineTo(cx - 0.28f * r, cy + 0.42f * r); path.close();
                    cv.drawPath(path, p);
                    break;
            }
        }

        @Override public void setPressed(boolean b) { super.setPressed(b); invalidate(); }
    }

    /** Конфетти на весь экран: ~90 частиц, падают 2,8 с, вращаются, гаснут в конце. Не перехватывает нажатия. */
    static final class ConfettiView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private float[] x0, y0, vx, vy, rot, size; private int[] col;
        private float t = -1;
        private final RectF rect = new RectF();

        ConfettiView(Context c) { super(c); setClickable(false); }

        void start(int[] colors) {
            int n = 90; x0 = new float[n]; y0 = new float[n]; vx = new float[n]; vy = new float[n]; rot = new float[n]; size = new float[n]; col = new int[n];
            float w = getWidth(), h = getHeight();
            for (int i = 0; i < n; i++) {
                x0[i] = rnd.nextFloat() * w; y0[i] = -rnd.nextFloat() * h * 0.4f;
                vx[i] = (rnd.nextFloat() - 0.5f) * w * 0.5f; vy[i] = h * (0.4f + rnd.nextFloat() * 0.5f);
                rot[i] = rnd.nextFloat() * 720 - 360; size[i] = h * (0.012f + rnd.nextFloat() * 0.014f); col[i] = colors[rnd.nextInt(colors.length)];
            }
            ValueAnimator a = ValueAnimator.ofFloat(0, 1); a.setDuration(2800); a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas cv) {
            if (t < 0 || t >= 1 || x0 == null) return;
            float h = getHeight();
            int alpha = (int) (255 * Math.min(1f, (1 - t) * 4));
            for (int i = 0; i < x0.length; i++) {
                float x = x0[i] + vx[i] * t + (float) Math.sin(t * 6 + i) * h * 0.02f;
                float y = y0[i] + vy[i] * t + h * 0.6f * t * t;
                p.setColor(col[i]); p.setAlpha(alpha);
                cv.save(); cv.rotate(rot[i] * t, x, y);
                float s = size[i];
                if (i % 3 == 0) cv.drawCircle(x, y, s * 0.5f, p);
                else { rect.set(x - s * 0.6f, y - s * 0.35f, x + s * 0.6f, y + s * 0.35f); cv.drawRect(rect, p); }
                cv.restore();
            }
        }
    }
}
