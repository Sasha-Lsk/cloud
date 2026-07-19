package com.idemobile.app;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Набор помощников для программной вёрстки настроек: заголовки секций, поля,
 * спойлеры-подсказки (синий заголовок + раскрытие описания), тумблеры, кнопки.
 * Повторяет визуальный стиль web-версии (settings.js).
 */
public class UiKit {

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static TextView sectionHead(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Color.parseColor("#8B949E"));
        t.setTextSize(12f);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 2), dp(c, 18), 0, dp(c, 8));
        return t;
    }

    public static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Color.parseColor("#E6EDF3"));
        t.setTextSize(14f);
        t.setPadding(dp(c, 2), 0, 0, dp(c, 6));
        return t;
    }

    /** Текстовое/числовое поле. */
    public static EditText input(Context c, String value, String hint, int inputType, boolean multiline) {
        EditText e = new EditText(c);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setTextColor(Color.parseColor("#E6EDF3"));
        e.setHintTextColor(Color.parseColor("#5B6570"));
        e.setTextSize(15f);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setText(value == null ? "" : value);
        if (hint != null) e.setHint(hint);
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(2);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(inputType);
            e.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        }
        return e;
    }

    /** Спойлер: синий кликабельный заголовок, при нажатии раскрывается описание. */
    public static View spoiler(final Context c, String title, String description) {
        final LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(c, 4), 0, dp(c, 4));

        final TextView head = new TextView(c);
        head.setText("\u25B8 " + title);
        head.setTextColor(Color.parseColor("#58A6FF"));
        head.setTextSize(13f);
        head.setPadding(0, dp(c, 4), 0, dp(c, 4));

        final TextView body = new TextView(c);
        body.setText(description);
        body.setTextColor(Color.parseColor("#8B949E"));
        body.setTextSize(12.5f);
        body.setLineSpacing(dp(c, 2), 1f);
        body.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 8));
        body.setVisibility(View.GONE);

        head.setOnClickListener(new View.OnClickListener() {
            boolean open = false;
            public void onClick(View v) {
                open = !open;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                head.setText((open ? "\u25BE " : "\u25B8 ") + head.getText().toString().substring(2));
            }
        });

        wrap.addView(head);
        wrap.addView(body);
        return wrap;
    }

    /** Обёртка «поле»: label + control + опциональный спойлер. */
    public static LinearLayout field(Context c) {
        LinearLayout f = new LinearLayout(c);
        f.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, 10);
        f.setLayoutParams(p);
        return f;
    }

    /** Кнопка (variant: "primary" | "danger" | "" ). */
    public static TextView button(Context c, String text, String variant) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        if ("primary".equals(variant)) {
            b.setBackgroundResource(R.drawable.btn_primary);
            b.setTextColor(Color.WHITE);
        } else if ("danger".equals(variant)) {
            b.setBackgroundResource(R.drawable.btn_danger);
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundResource(R.drawable.btn_normal);
            b.setTextColor(Color.parseColor("#E6EDF3"));
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /**
     * Кнопка-иконка (variant: "primary" | "danger" | "" ). Квадратная, компактная —
     * используется вместо текстовых кнопок «Редактировать»/«Удалить».
     */
    public static android.widget.ImageView iconButton(Context c, int iconRes, String variant) {
        android.widget.ImageView b = new android.widget.ImageView(c);
        b.setImageResource(iconRes);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(c, 10);
        b.setPadding(pad, pad, pad, pad);
        if ("primary".equals(variant)) {
            b.setBackgroundResource(R.drawable.btn_primary);
        } else if ("danger".equals(variant)) {
            b.setBackgroundResource(R.drawable.btn_danger);
        } else {
            b.setBackgroundResource(R.drawable.btn_normal);
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Тумблер: чекбокс + подпись в строку. Возвращает саму CheckBox для слушателя. */
    public static CheckBox toggleRow(Context c, LinearLayout parent, String labelText) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(c, 12);
        row.setLayoutParams(rp);

        CheckBox cb = new CheckBox(c);
        TextView t = new TextView(c);
        t.setText(labelText);
        t.setTextColor(Color.parseColor("#E6EDF3"));
        t.setTextSize(14f);
        LinearLayout.LayoutParams tp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.weight = 1;
        tp.leftMargin = dp(c, 8);
        t.setLayoutParams(tp);

        row.addView(cb);
        row.addView(t);
        parent.addView(row);
        return cb;
    }

    /**
     * Тумблер-ползунок (как в web-версии): подпись слева, Switch справа.
     * Вся строка кликабельна (тап по подписи тоже переключает). Возвращает Switch.
     */
    public static android.widget.Switch switchRow(Context c, LinearLayout parent, String labelText) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(c, 12);
        row.setLayoutParams(rp);
        row.setClickable(true);

        TextView t = new TextView(c);
        t.setText(labelText);
        t.setTextColor(Color.parseColor("#E6EDF3"));
        t.setTextSize(14f);
        LinearLayout.LayoutParams tp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.weight = 1;
        t.setLayoutParams(tp);

        final android.widget.Switch sw = new android.widget.Switch(c);
        styleSwitch(sw);

        row.addView(t);
        row.addView(sw);
        // Тап по всей строке переключает ползунок.
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row);
        return sw;
    }

    /** Красит трек/бегунок Switch в фирменные цвета (вкл — синий, выкл — серый). */
    public static void styleSwitch(android.widget.Switch sw) {
        try {
            int on = Color.parseColor("#2F81F7");
            int onTrack = Color.parseColor("#1F4E82");
            int off = Color.parseColor("#8B949E");
            int offTrack = Color.parseColor("#30363D");
            int[][] states = new int[][]{
                    new int[]{ android.R.attr.state_checked },
                    new int[]{}
            };
            android.content.res.ColorStateList thumb = new android.content.res.ColorStateList(
                    states, new int[]{ on, off });
            android.content.res.ColorStateList track = new android.content.res.ColorStateList(
                    states, new int[]{ onTrack, offTrack });
            sw.setThumbTintList(thumb);
            sw.setTrackTintList(track);
        } catch (Throwable ignored) {}
    }

    /**
     * Вложенный ScrollView, который умеет прокручиваться ВНУТРИ другого ScrollView.
     * При касании просит родителя не перехватывать событие (requestDisallowInterceptTouchEvent),
     * поэтому список моделей прокручивается сам, а не двигает всё меню настроек.
     */
    public static android.widget.ScrollView nestedScroll(Context c) {
        return new android.widget.ScrollView(c) {
            private float startY;
            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                // Пока палец внутри списка — забираем управление у родителя.
                if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return super.onInterceptTouchEvent(ev);
            }
            @Override
            public boolean onTouchEvent(android.view.MotionEvent ev) {
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = ev.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        // Если список уже упёрся в край — вернуть прокрутку родителю,
                        // чтобы можно было докрутить само меню.
                        boolean atTop = getScrollY() <= 0;
                        View child = getChildCount() > 0 ? getChildAt(0) : null;
                        int max = child != null ? Math.max(0, child.getHeight() - getHeight()) : 0;
                        boolean atBottom = getScrollY() >= max;
                        float dy = ev.getY() - startY;
                        boolean giveBack = (atTop && dy > 0) || (atBottom && dy < 0);
                        getParent().requestDisallowInterceptTouchEvent(!giveBack);
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.onTouchEvent(ev);
            }
        };
    }

    /** Цветной кружок доступности. */
    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        LinearLayout.LayoutParams p = lp(dp(c, sizeDp), dp(c, sizeDp));
        p.leftMargin = dp(c, 8);
        v.setLayoutParams(p);
        return v;
    }
}
