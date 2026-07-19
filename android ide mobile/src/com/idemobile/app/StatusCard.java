package com.idemobile.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Карточка статуса ИИ-агента (как в web-версии).
 * Показывает:
 *   • строку «••• [op-бейдж] текст ⌄ ×» (op = Waiting/Read/Wrote/…);
 *   • reasoning — курсивом серым (внутренние размышления модели);
 *   • лог инструментов «✓ Wrote /path  N строк · новый файл»;
 *   • раскрываемые блоки файлов с diff (зелёные добавленные / красные удалённые строки).
 * Можно свернуть/развернуть (шеврон) и удалить из чата (× — без подтверждения).
 */
public class StatusCard extends LinearLayout {

    private final TextView opBadge, statusText, toggle, close, reasoningView;
    private final LinearLayout body, logBox;
    // Бейдж с реальным именем модели (по base URL) — как под обычными сообщениями.
    // Виден только когда карточка РАЗВЁРНУТА (лежит в body → скрывается вместе с телом).
    private LinearLayout modelBadge;
    private TextView modelIdView;
    private String modelName = "";
    private final ScrollView reasoningScroll, logScroll;
    private final SpinnerView spinner;
    // Стартуем свёрнутыми: пока нет ни рассуждений, ни операций — видна только
    // строка статуса. При появлении первого контента статус авто-разворачивается
    // (autoExpand), а по завершении задачи снова сворачивается.
    private boolean expanded = false;
    private boolean finished = false;
    private Runnable onRemove;

    // Внутренняя карточка с тёмным фоном (окно статуса). Плашка модели
    // добавляется ПОД ней, снаружи фона — как под обычными сообщениями.
    private final LinearLayout card;

    public StatusCard(Context c) {
        super(c);
        // Внешний контейнер — прозрачный: держит [окно статуса] + [плашка модели].
        setOrientation(VERTICAL);
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outerLp.topMargin = dp(6); outerLp.bottomMargin = dp(6);
        setLayoutParams(outerLp);

        // Внутренняя карточка (тёмный фон = само окно статуса).
        card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        int pad = dp(12);
        card.setPadding(pad, dp(10), pad, dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#1C222B"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#3A424E"));
        card.setBackground(bg);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card);

        // Строка заголовка (кликабельна целиком — сворачивает/разворачивает).
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        // Верхнее выравнивание: при переносе длинного заголовка спиннер/бейдж/
        // кнопки остаются у первой строки, текст переносится ниже (как в web).
        row.setGravity(Gravity.TOP);

        // Стильный анимированный спиннер — вращающаяся дуга-кольцо с градиентом.
        spinner = new SpinnerView(c);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(20), dp(20));
        sp.rightMargin = dp(9);
        spinner.setLayoutParams(sp);
        row.addView(spinner);
        // Анимацию запускаем в onAttachedToWindow (ниже) — если стартовать её
        // прямо в конструкторе, вью ещё не прикреплён к окну и ValueAnimator
        // на многих версиях Android не тикает → дуга «замирает» в одном
        // положении. Именно это вызывало баг «кружок не анимирован».

        opBadge = new TextView(c);
        opBadge.setTextSize(13f);
        opBadge.setTypeface(Typeface.DEFAULT_BOLD);
        opBadge.setPadding(dp(9), dp(3), dp(9), dp(3));
        setBadge("Waiting", "think");
        row.addView(opBadge);

        statusText = new TextView(c);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15f);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(10), 0, dp(6), 0);
        // Длинный заголовок операции переносится на несколько строк (как в web) —
        // текст виден полностью, без обрезки многоточием.
        statusText.setSingleLine(false);
        statusText.setEllipsize(null);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.weight = 1; statusText.setLayoutParams(stp);
        row.addView(statusText);

        toggle = new TextView(c);
        toggle.setText("\u2304");
        toggle.setTextColor(Color.parseColor("#8B949E"));
        toggle.setTextSize(20f);
        toggle.setPadding(dp(8), 0, dp(8), 0);
        row.addView(toggle);

        close = new TextView(c);
        close.setText("\u2715");
        close.setTextColor(Color.parseColor("#8B949E"));
        close.setTextSize(17f);
        close.setPadding(dp(6), 0, dp(4), 0);
        close.setVisibility(GONE); // появляется после завершения
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                stopDotsAnim();
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.removeView(StatusCard.this);
                if (onRemove != null) onRemove.run();
            }
        });
        row.addView(close);

        // Клик по всей строке (кроме крестика) — свернуть/развернуть.
        row.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { expanded = !expanded; applyExpanded(); }
        });
        card.addView(row);

        // Тело (reasoning + лог)
        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        card.addView(body);

        // Бейдж «• модель: <id>» — ПОД окном статуса (снаружи тёмного фона),
        // как плашка модели под обычными сообщениями. Виден всегда, когда известна.
        modelBadge = new LinearLayout(c);
        modelBadge.setOrientation(HORIZONTAL);
        modelBadge.setGravity(Gravity.CENTER_VERTICAL);
        modelBadge.setPadding(dp(10), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mbLp.topMargin = dp(4);
        mbLp.leftMargin = dp(4);
        modelBadge.setLayoutParams(mbLp);
        android.graphics.drawable.GradientDrawable mbBg = new android.graphics.drawable.GradientDrawable();
        mbBg.setCornerRadius(dp(14));
        mbBg.setColor(Color.parseColor("#141A22"));
        mbBg.setStroke(dp(1), Color.parseColor("#2E3742"));
        modelBadge.setBackground(mbBg);
        // Зелёная точка-индикатор.
        View dotView = new View(c);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#3FB950"));
        dotView.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotView.setLayoutParams(dotLp);
        modelBadge.addView(dotView);
        TextView mbLbl = new TextView(c);
        mbLbl.setText("  модель: ");
        mbLbl.setTextColor(Color.parseColor("#8B949E"));
        mbLbl.setTextSize(12f);
        modelBadge.addView(mbLbl);
        modelIdView = new TextView(c);
        modelIdView.setText("—");
        modelIdView.setTextColor(Color.WHITE);
        modelIdView.setTextSize(12f);
        modelIdView.getPaint().setFakeBoldText(true);
        modelIdView.setTypeface(Typeface.MONOSPACE);
        modelIdView.setTextIsSelectable(true);
        modelBadge.addView(modelIdView);
        // Долгое нажатие — копирование id модели в буфер.
        modelBadge.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (modelName != null && !modelName.isEmpty()) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("model", modelName));
                        android.widget.Toast.makeText(getContext(), "ID модели скопирован: " + modelName,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
                return true;
            }
        });
        modelBadge.setVisibility(GONE); // пока модель неизвестна — скрыт
        // Добавляем в ВНЕШНИЙ контейнер, ПОД карточкой (card добавлен раньше).
        addView(modelBadge);

        // Окно статуса делится на два контейнера: сверху рассуждения (в фокусе
        // всегда начало), снизу — лог операций (автоскролл к последнему действию).
        // Высота каждого контейнера ПОДГОНЯЕТСЯ под содержимое (wrap_content) и
        // ограничена максимумом — при переполнении включается прокрутка. Пустые
        // контейнеры не занимают места (пустой view = 0 высоты).
        reasoningScroll = nestedScroll(c, dp(REASON_MAX_DP));
        LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rsp.topMargin = dp(4);
        reasoningScroll.setLayoutParams(rsp);
        reasoningScroll.setVisibility(GONE);

        reasoningView = new TextView(c);
        reasoningView.setTextColor(Color.parseColor("#9DA7B3"));
        reasoningView.setTextSize(14.5f);
        reasoningView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        reasoningView.setPadding(0, dp(8), 0, dp(4));
        reasoningView.setTextIsSelectable(true); // ручное выделение/копирование текста статуса
        reasoningScroll.addView(reasoningView);
        body.addView(reasoningScroll);

        logScroll = nestedScroll(c, dp(LOG_MAX_DP));
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lsp.topMargin = dp(6);
        logScroll.setLayoutParams(lsp);
        logScroll.setVisibility(GONE);

        logBox = new LinearLayout(c);
        logBox.setOrientation(VERTICAL);
        logScroll.addView(logBox);
        body.addView(logScroll);

        // Изначально свёрнуто — тело скрыто, видна только строка статуса.
        applyExpanded();
    }

    // Максимальная высота (в dp) каждого из двух контейнеров развёрнутого статуса.
    // Ниже — контейнер подгоняется под содержимое; выше — включается прокрутка.
    private static final int REASON_MAX_DP = 180;
    private static final int LOG_MAX_DP = 180;

    /**
     * ScrollView, который: (1) подгоняет высоту под содержимое, но не выше
     * maxHeightPx (при переполнении — прокрутка); (2) прокручивается ВНУТРИ чата
     * (внешнего ScrollView) — пока палец внутри, просит родителя не перехватывать
     * касание, а на краю возвращает управление, чтобы можно было докрутить чат.
     */
    private static ScrollView nestedScroll(Context c, final int maxHeightPx) {
        return new ScrollView(c) {
            private float startY;
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Ограничиваем максимальную высоту, но позволяем быть меньше
                // (wrap_content под содержимое).
                int hs = android.view.View.MeasureSpec.makeMeasureSpec(
                        maxHeightPx, android.view.View.MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, hs);
            }
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN && getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(ev);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = ev.getY();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        boolean atTop = getScrollY() <= 0;
                        View child = getChildCount() > 0 ? getChildAt(0) : null;
                        int max = child != null ? Math.max(0, child.getHeight() - getHeight()) : 0;
                        boolean atBottom = getScrollY() >= max;
                        float dy = ev.getY() - startY;
                        boolean giveBack = (atTop && dy > 0) || (atBottom && dy < 0);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(!giveBack);
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.onTouchEvent(ev);
            }
        };
    }

    public void setOnRemove(Runnable r) { this.onRemove = r; }

    /**
     * Стартуем/останавливаем анимацию по факту прикрепления вью к окну —
     * это единственный надёжный момент для ValueAnimator (в конструкторе окна
     * ещё нет, и аниматор не тикает). Пока карточка не завершена (finished),
     * дуга крутится; после finish() — стоит.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!finished) startDotsAnim();
    }

    /** Запустить самоанимируемый спиннер (см. SpinnerView). */
    private void startDotsAnim() {
        spinner.startSpin();
    }
    private void stopDotsAnim() {
        spinner.stopSpin();
        spinner.setVisibility(GONE);
    }

    /**
     * Стильный анимированный индикатор работы агента: вращающаяся незамкнутая
     * дуга-кольцо с плавным градиентом (сине-голубой), с закруглёнными концами.
     * Заменяет прежние «три точки».
     */
    static class SpinnerView extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float angle = 0f;
        // Самоанимация: угол считаем от системных часов, а перерисовку планируем
        // каждый кадр через postInvalidateOnAnimation. Так вращение НЕ зависит от
        // ValueAnimator и от системной настройки «animator duration scale» (при
        // scale=0 ValueAnimator мгновенно завершается — из-за этого дуга и
        // «замирала»). Пока вью прикреплён и виден — крутимся.
        private boolean spinning = false;
        private long startNs = 0L;
        private static final float DEG_PER_MS = 360f / 900f; // полный оборот за 900мс

        SpinnerView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            float sw = 2.6f * d;
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(sw);
            track.setColor(Color.parseColor("#22314A"));
            arc.setStyle(android.graphics.Paint.Style.STROKE);
            arc.setStrokeWidth(sw);
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arc.setColor(Color.parseColor("#58A6FF"));
        }

        /** Совместимость со старым внешним вызовом (ValueAnimator). */
        void setAngle(float a) { angle = a; invalidate(); }

        void startSpin() {
            spinning = true;
            startNs = System.nanoTime();
            setVisibility(VISIBLE);
            postInvalidateOnAnimation();
        }
        void stopSpin() { spinning = false; }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (spinning) { startNs = System.nanoTime(); postInvalidateOnAnimation(); }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float pad = arc.getStrokeWidth();
            box.set(pad, pad, w - pad, h - pad);
            // Градиент вдоль окружности (голубой → прозрачный) для «кометного» хвоста.
            android.graphics.SweepGradient sg = new android.graphics.SweepGradient(
                    w / 2f, h / 2f,
                    new int[]{ Color.parseColor("#003A7BFF"), Color.parseColor("#58A6FF"), Color.parseColor("#9AD1FF") },
                    new float[]{ 0f, 0.7f, 1f });
            arc.setShader(sg);
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            if (spinning) {
                long elapsed = (System.nanoTime() - startNs) / 1000000L; // мс
                angle = (elapsed * DEG_PER_MS) % 360f;
            }
            cv.drawArc(box, 0, 360, false, track);
            cv.save();
            cv.rotate(angle, getWidth() / 2f, getHeight() / 2f);
            cv.drawArc(box, 0, 290, false, arc);
            cv.restore();
            // Планируем следующий кадр, пока крутимся и прикреплены к окну.
            if (spinning && isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }

    /** Op-бейдж + цвет по типу (think/ok/err). */
    private void setBadge(String label, String kind) {
        opBadge.setText(label);
        int bgc, fg;
        if ("err".equals(kind)) { bgc = Color.parseColor("#3A1D1F"); fg = Color.parseColor("#F85149"); }
        else if ("ok".equals(kind)) { bgc = Color.parseColor("#12321C"); fg = Color.parseColor("#3FB950"); }
        else { bgc = Color.parseColor("#12233F"); fg = Color.parseColor("#58A6FF"); }
        android.graphics.drawable.GradientDrawable b = new android.graphics.drawable.GradientDrawable();
        b.setColor(bgc); b.setCornerRadius(dp(7));
        opBadge.setBackground(b);
        opBadge.setTextColor(fg);
    }

    /** Обновить op-строку. */
    public void setOp(String label, String detail, String kind) {
        setBadge(label, kind);
        statusText.setText(detail == null ? "" : detail);
    }

    /** Установить реальное имя модели (по base URL). Показывается под окном статуса. */
    public void setModel(String model) {
        modelName = model == null ? "" : model.trim();
        if (modelName.isEmpty()) { modelBadge.setVisibility(GONE); return; }
        modelIdView.setText(modelName);
        // ПОД окном статуса — виден только когда окно РАЗВЁРНУТО.
        modelBadge.setVisibility(expanded ? VISIBLE : GONE);
    }

    /** Показать reasoning (внутренний диалог модели). */
    public void setReasoning(String text) {
        if (text == null || text.trim().isEmpty()) { reasoningScroll.setVisibility(GONE); return; }
        reasoningView.setText(text.trim());
        // Появилось рассуждение → статус авто-разворачивается (если ещё не завершён).
        autoExpand();
        reasoningScroll.setVisibility(expanded ? VISIBLE : GONE);
        // В фокусе держим НАЧАЛО рассуждений (не автоскроллим вниз), контейнер
        // подгоняется под содержимое и прокручивается внутри себя при переполнении.
        reasoningScroll.post(new Runnable() { public void run() { reasoningScroll.scrollTo(0, 0); } });
    }

    // Авто-разворот статуса при появлении первого рассуждения/операции.
    // Не разворачиваем завершённый статус (он должен оставаться свёрнутым в истории).
    private void autoExpand() {
        if (finished) return;
        if (!expanded) { expanded = true; applyExpanded(); }
    }

    // Максимум элементов в логе статуса (строки + блоки превью). Как в web-версии
    // (ai.js: while(log.children.length>60) log.removeChild(log.firstChild)):
    // старые записи удаляются, чтобы дерево View не разрасталось бесконечно и
    // чат оставался быстрым при длинных сессиях с большими файлами.
    private static final int MAX_LOG_ITEMS = 60;
    private void trimLog() {
        while (logBox.getChildCount() > MAX_LOG_ITEMS) logBox.removeViewAt(0);
    }

    // Составные глаголы (две части) — как в web splitVerb(): «Read binary»,
    // «Created folder» и т.п. считаются единым глаголом операции.
    private static final String[] TWO_WORD_VERBS = {
        "Read binary", "Read entry", "Created folder", "Created archive",
        "Extracting archive", "Creating archive", "Creating folder",
        "Got status", "Git status", "GitHub API", "HTTP fetch",
        "Initializing repo", "Viewed image", "Viewing image",
        "Listed archive", "Searched web"
    };

    /**
     * Добавить строку в лог инструмента: «✓ Wrote /path  N строк · Δ+5 строк · 31ms».
     * Рендер построчно детализирован как в web-версии:
     *   • маркер ✓ (зелёный) / ✗ (красный);
     *   • глагол (Read/Wrote/…) — жирный, цветом по типу действия;
     *   • путь/аргументы — обычным цветом текста;
     *   • числа в сводке — раскрашены: +N зелёным, -N/Δ- красным, «N замен» пурпурным.
     */
    public void appendLog(String logLine, boolean ok) {
        TextView t = new TextView(getContext());
        String mark = ok ? "\u2713 " : "\u2717 ";

        // Разбор глагола (учитывая составные фразы из двух слов).
        String verb = logLine;
        int verbEnd = logLine.length();
        for (String tw : TWO_WORD_VERBS) {
            if (logLine.regionMatches(true, 0, tw, 0, tw.length())) {
                verb = logLine.substring(0, tw.length());
                verbEnd = tw.length();
                break;
            }
        }
        if (verbEnd == logLine.length()) {
            int sp = logLine.indexOf(' ');
            if (sp > 0) { verb = logLine.substring(0, sp); verbEnd = sp; }
        }

        int verbColor = ok ? actColor(verb) : Color.parseColor("#F85149");
        int markColor = ok ? Color.parseColor("#3FB950") : Color.parseColor("#F85149");

        android.text.SpannableStringBuilder ss = new android.text.SpannableStringBuilder(mark + logLine);
        int base = mark.length();

        // Маркер ✓/✗ — жирный, цветной.
        ss.setSpan(new android.text.style.ForegroundColorSpan(markColor), 0, mark.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, mark.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Глагол — жирный, цветом действия.
        int vEnd = Math.min(ss.length(), base + verbEnd);
        ss.setSpan(new android.text.style.ForegroundColorSpan(verbColor), base, vEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), base, vEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Раскраска чисел в сводке: +N зелёным, -N/Δ- красным, «N замен» пурпурным.
        colorizeNumbers(ss, base + verbEnd, ss.length());

        t.setText(ss);
        t.setTextColor(Color.parseColor("#C9D1D9"));
        t.setTextSize(14f);
        t.setPadding(0, dp(6), 0, dp(2));
        enableLazySelection(t); // ленивое выделение/копирование (без дорогой инициализации)
        logBox.addView(t);
        trimLog();
        // Появилась операция → статус авто-разворачивается, контейнер лога показан.
        autoExpand();
        logScroll.setVisibility(expanded ? VISIBLE : GONE);
        scrollLogToBottom();
    }

    // Цвета чисел в сводке (совпадают с web: num-add/num-del/num-repl).
    private static final int NUM_ADD  = Color.parseColor("#3FB950");
    private static final int NUM_DEL  = Color.parseColor("#F85149");
    private static final int NUM_REPL = Color.parseColor("#C586C0");
    private static final java.util.regex.Pattern NUM_RE = java.util.regex.Pattern.compile(
            "(\u0394[+\\-]?\\d+)|((?<![\\d\\w])[+\\-]\\d+)|(\\d+\\s+замен)");

    /** Раскрашивает числа Δ±N / ±N / «N замен» в диапазоне [from,to) билдера. */
    private void colorizeNumbers(android.text.SpannableStringBuilder ss, int from, int to) {
        if (from < 0) from = 0;
        if (to > ss.length()) to = ss.length();
        if (from >= to) return;
        CharSequence sub = ss.subSequence(from, to);
        java.util.regex.Matcher m = NUM_RE.matcher(sub);
        while (m.find()) {
            String g = m.group();
            int color;
            if (m.group(3) != null) color = NUM_REPL;            // «N замен»
            else if (g.indexOf('-') >= 0) color = NUM_DEL;       // -N / Δ-N
            else color = NUM_ADD;                                 // +N / Δ+N
            int s = from + m.start(), e = from + m.end();
            ss.setSpan(new android.text.style.ForegroundColorSpan(color), s, e,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s, e,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Автопрокрутка лога операций к последнему действию (внутри своего контейнера). */
    private void scrollLogToBottom() {
        logScroll.post(new Runnable() {
            public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // Цвет глагола по типу действия — 1:1 с web (verbClass + CSS act-*):
    //   чтение/просмотр — голубой; запись/создание — зелёный; патч/замена —
    //   пурпурный; поиск — жёлтый; удаление — красный; git — оранжевый; сеть — синий.
    private int actColor(String verb) {
        String v = verb.toLowerCase();
        if (v.startsWith("read") || v.startsWith("list") || v.startsWith("view")
                || v.startsWith("got") || v.startsWith("fetched")) return Color.parseColor("#7EC5F0");
        if (v.startsWith("wrote") || v.startsWith("writing") || v.startsWith("append")
                || v.startsWith("creat") || v.startsWith("archiv") || v.startsWith("extract")) return Color.parseColor("#4EC9B0");
        if (v.startsWith("replac") || v.startsWith("patch")) return Color.parseColor("#C586C0");
        if (v.startsWith("search")) return Color.parseColor("#DCDCAA");
        if (v.startsWith("delet") || v.startsWith("remov")) return Color.parseColor("#F14C4C");
        if (v.startsWith("commit") || v.startsWith("pull") || v.startsWith("push")
                || v.startsWith("clon") || v.startsWith("init") || v.startsWith("renam")) return Color.parseColor("#FF9D5C");
        if (v.startsWith("http") || v.startsWith("github")) return Color.parseColor("#569CD6");
        return Color.parseColor("#4EC9B0");
    }

    /**
     * Добавить раскрываемый блок файла с diff.
     * head: «/path  +N -M  · байт · строк · новый файл»
     * diff: JSON { hunks:[{type,text,oldNo,newNo}], added, removed }.
     */
    public void appendFilePreview(String path, JSONObject result, String tool) {
        if (result == null) return;
        final LinearLayout blk = new LinearLayout(getContext());
        blk.setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#0D1117"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#232A33"));
        blk.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        blk.setLayoutParams(blp);

        // Заголовок блока
        final LinearLayout hd = new LinearLayout(getContext());
        hd.setOrientation(HORIZONTAL);
        // Верхнее выравнивание — чтобы при переносе длинного пути значок/шеврон
        // оставались у первой строки (как flex-start в web).
        hd.setGravity(Gravity.TOP);
        hd.setPadding(dp(10), dp(10), dp(10), dp(10));

        final TextView chev = new TextView(getContext());
        chev.setText("\u25B8");
        chev.setTextColor(Color.parseColor("#8B949E"));
        chev.setTextSize(13f);
        chev.setPadding(0, 0, dp(8), 0);
        hd.addView(chev);

        // Значок типа: для delete/mkdir/rename — спец. иконка, иначе — по расширению.
        TextView icon = new TextView(getContext());
        icon.setText(iconFor(path, tool));
        icon.setTextSize(15f);
        icon.setPadding(0, 0, dp(8), 0);
        hd.addView(icon);

        // Для rename показываем «old → new».
        String titleText;
        if ("fs_rename".equals(tool)) {
            String from = result.optString("from", path);
            String to = result.optString("to", "");
            titleText = from + (to.isEmpty() ? "" : "  \u2192  " + to);
        } else {
            titleText = result.optString("path", path == null ? "" : path);
        }
        TextView title = new TextView(getContext());
        title.setText(titleText);
        title.setTextColor(Color.parseColor("#E6EDF3"));
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.MONOSPACE);
        // Длинный путь/заголовок файла переносится на несколько строк (как в web),
        // чтобы был виден полностью.
        title.setSingleLine(false);
        title.setEllipsize(null);
        title.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.weight = 1; title.setLayoutParams(tp);
        hd.addView(title);

        JSONObject diff = result.optJSONObject("diff");
        int added = diff != null ? diff.optInt("added") : 0;
        int removed = diff != null ? diff.optInt("removed") : 0;
        if (added > 0) hd.addView(badge("+" + added, Color.parseColor("#12321C"), Color.parseColor("#3FB950")));
        if (removed > 0) hd.addView(badge("-" + removed, Color.parseColor("#3A1D1F"), Color.parseColor("#F85149")));

        // Мета-строка справа (размер) — как «6.5KB» / «398B» в web.
        String meta = metaFor(result, tool);
        if (!meta.isEmpty()) {
            TextView m = new TextView(getContext());
            m.setText(meta);
            m.setTextColor(Color.parseColor("#6E7681"));
            m.setTextSize(12.5f);
            m.setTypeface(Typeface.DEFAULT_BOLD);
            m.setPadding(dp(8), 0, 0, 0);
            hd.addView(m);
        }
        blk.addView(hd);

        // Тело (diff/содержимое) строится ЛЕНИВО — только при первом разворачивании.
        // Это критично для производительности: при редактировании большого файла
        // статус-карточек много, и если у каждой заранее строить сотни diff-строк
        // (пусть даже скрытых через GONE) — дерево View разрастается на тысячи
        // элементов и скролл чата дико тормозит. Свёрнутая карточка теперь = только
        // лёгкий заголовок; тяжёлое содержимое материализуется по клику.
        final HorizontalScrollView hsv = new HorizontalScrollView(getContext());
        hsv.setVisibility(GONE);
        final LinearLayout diffCol = new LinearLayout(getContext());
        diffCol.setOrientation(VERTICAL);
        diffCol.setPadding(dp(8), dp(4), dp(8), dp(8));
        hsv.addView(diffCol);
        blk.addView(hsv);

        // Финальные ссылки для отложенного построения тела.
        final JSONObject fResult = result;
        final String fTool = tool;
        final String fPath = path;
        hd.setOnClickListener(new OnClickListener() {
            boolean open = false;
            boolean built = false;
            public void onClick(View v) {
                open = !open;
                if (open && !built) {
                    built = true;
                    buildPreviewBody(diffCol, fResult, fTool, fPath);
                }
                hsv.setVisibility(open ? VISIBLE : GONE);
                chev.setText(open ? "\u25BE" : "\u25B8");
            }
        });

        logBox.addView(blk);
        trimLog();
        autoExpand();
        logScroll.setVisibility(expanded ? VISIBLE : GONE);
        scrollLogToBottom();
    }

    /** Наполняет тело превью (вызывается лениво при первом развороте карточки). */
    private void buildPreviewBody(LinearLayout diffCol, JSONObject result, String tool, String path) {
        if ("fs_delete".equals(tool)) diffCol.addView(statusLine("Удалено"));
        else if ("fs_mkdir".equals(tool)) diffCol.addView(statusLine("Папка создана"));
        else if ("fs_rename".equals(tool)) diffCol.addView(statusLine("Готово"));
        else if ("archive_create".equals(tool))
            diffCol.addView(statusLine("Архив создан · " + result.optInt("files") + " файлов"));
        else if ("archive_extract".equals(tool))
            diffCol.addView(statusLine("Распаковано · " + result.optInt("extracted") + " файлов → " + result.optString("to")));
        else if ("fs_archive".equals(tool) || (path != null && path.toLowerCase().endsWith(".zip")))
            diffCol.addView(statusLine("Архив создан"));
        else buildDiff(diffCol, result, tool);
    }

    private void buildDiff(LinearLayout col, JSONObject result, String tool) {
        JSONObject diff = result.optJSONObject("diff");
        JSONArray hunks = diff != null ? diff.optJSONArray("hunks") : null;
        if (hunks == null) {
            // Нет построчного diff (fs_read или большой файл) — покажем content.
            String content = result.optString("content", "");
            if (content.isEmpty()) {
                TextView t = mono("(нет предпросмотра)", "#8B949E");
                col.addView(t);
                return;
            }
            String[] lines = content.split("\n", -1);
            int limit = Math.min(lines.length, 400);
            for (int i = 0; i < limit; i++) col.addView(diffLine("ctx", i + 1, 0, lines[i]));
            if (lines.length > limit) col.addView(mono("…ещё " + (lines.length - limit) + " строк", "#8B949E"));
            return;
        }
        int limit = Math.min(hunks.length(), 500);
        for (int i = 0; i < limit; i++) {
            JSONObject h = hunks.optJSONObject(i);
            if (h == null) continue;
            col.addView(diffLine(h.optString("type"), h.optInt("oldNo"), h.optInt("newNo"), h.optString("text")));
        }
        if (hunks.length() > limit) col.addView(mono("…ещё " + (hunks.length() - limit) + " строк", "#8B949E"));
    }

    /** Строка diff: номер + знак + текст, с фоновой подсветкой. */
    private View diffLine(String type, int oldNo, int newNo, String text) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        int bgc, fg; String sign;
        if ("add".equals(type)) { bgc = Color.parseColor("#0E2B17"); fg = Color.parseColor("#7EE787"); sign = "+"; }
        else if ("del".equals(type)) { bgc = Color.parseColor("#3A0E12"); fg = Color.parseColor("#FF9A9A"); sign = "-"; }
        else { bgc = Color.TRANSPARENT; fg = Color.parseColor("#8B949E"); sign = " "; }
        row.setBackgroundColor(bgc);
        row.setPadding(dp(4), dp(1), dp(4), dp(1));

        TextView num = new TextView(getContext());
        int n = "del".equals(type) ? oldNo : (newNo > 0 ? newNo : oldNo);
        num.setText(n > 0 ? String.valueOf(n) : "");
        num.setTextColor(Color.parseColor("#565f6b"));
        num.setTextSize(12.5f);
        num.setTypeface(Typeface.MONOSPACE);
        num.setWidth(dp(34));
        num.setGravity(Gravity.RIGHT);
        num.setPadding(0, 0, dp(8), 0);
        row.addView(num);

        TextView tv = new TextView(getContext());
        tv.setText(sign + " " + text);
        tv.setTextColor(fg);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.MONOSPACE);
        enableLazySelection(tv); // копирование доступно (по долгому нажатию), но без дорогой инициализации
        row.addView(tv);
        return row;
    }

    // Включение выделения текста (setTextIsSelectable) — дорогая операция: создаёт
    // editable-механику, курсор, spannable. При массовом построении diff-строк это
    // главный тормоз скролла. Поэтому включаем выделение ЛЕНИВО — только когда
    // пользователь реально долго нажал на строку (собираясь копировать). Функция
    // копирования полностью сохранена, но не платится за неё на каждой строке.
    private void enableLazySelection(final TextView tv) {
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                // Активируем выделение и снимаем свой listener, чтобы дальше долгие
                // нажатия обрабатывал системный механизм выделения/меню «Копировать».
                tv.setOnLongClickListener(null);
                tv.setTextIsSelectable(true);
                tv.post(new Runnable() {
                    public void run() {
                        try { tv.requestFocus(); tv.performLongClick(); }
                        catch (Exception ignored) {}
                    }
                });
                return true;
            }
        });
    }

    private TextView mono(String s, String color) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(Color.parseColor(color));
        t.setTextSize(12.5f); t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(4), 0, dp(4));
        enableLazySelection(t); // ленивое копирование
        return t;
    }

    /** Значок типа: спец. для delete/mkdir/rename/archive, иначе по расширению файла (как web fileIcon). */
    private String iconFor(String path, String tool) {
        if ("fs_delete".equals(tool)) return "\uD83D\uDDD1"; // 🗑
        if ("fs_mkdir".equals(tool)) return "\uD83D\uDCC1";  // 📁
        if ("fs_rename".equals(tool)) return "\u270F\uFE0F"; // ✏️
        if ("archive_create".equals(tool) || "archive_extract".equals(tool)) return "\uD83D\uDCE6"; // 📦
        String p = path == null ? "" : path;
        String ext = "";
        int dot = p.lastIndexOf('.');
        if (dot >= 0) ext = p.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "js": case "ts": case "jsx": case "tsx": case "mjs": case "cjs": return "\uD83D\uDCDC"; // 📜
            case "py": return "\uD83D\uDC0D"; // 🐍
            case "json": case "yaml": case "yml": case "toml": return "\u2699\uFE0F"; // ⚙️
            case "md": case "txt": case "rst": return "\uD83D\uDCDD"; // 📝
            case "html": case "htm": case "xml": return "\uD83C\uDF10"; // 🌐
            case "css": case "scss": case "less": return "\uD83C\uDFA8"; // 🎨
            case "zip": case "tar": case "gz": case "7z": case "rar": return "\uD83D\uDCE6"; // 📦
            case "mp3": case "wav": case "ogg": case "flac": case "m4a": return "\uD83C\uDFB5"; // 🎵
            case "mp4": case "webm": case "mov": case "mkv": case "avi": return "\uD83C\uDFAC"; // 🎬
            case "pdf": return "\uD83D\uDCD5"; // 📕
            default: return "\uD83D\uDCC4"; // 📄
        }
    }

    /** Правая мета-строка блока (размер) — как «6.5KB» в web. */
    private String metaFor(JSONObject result, String tool) {
        long size = result.optLong("size", -1);
        if (size < 0) return "";
        return humanSize(size);
    }
    private String humanSize(long n) {
        if (n < 1024) return n + "B";
        if (n < 1048576) return String.format(java.util.Locale.US, "%.1fKB", n / 1024.0);
        return String.format(java.util.Locale.US, "%.2fMB", n / 1048576.0);
    }

    /** Простая статус-строка внутри раскрытого блока (для delete/mkdir/rename/archive). */
    private TextView statusLine(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Color.parseColor("#C9D1D9"));
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(2), dp(2), dp(2), dp(2));
        return t;
    }

    private TextView badge(String s, int bgc, int fg) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(fg); t.setTextSize(12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(7), dp(2), dp(7), dp(2));
        android.graphics.drawable.GradientDrawable b = new android.graphics.drawable.GradientDrawable();
        b.setColor(bgc); b.setCornerRadius(dp(5));
        t.setBackground(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6); t.setLayoutParams(lp);
        return t;
    }

    /** Завершение работы: op = Готово/Ошибка, показать крестик, свернуть тело. */
    public void finish(String label, String kind, boolean collapse) {
        finished = true;
        stopDotsAnim();
        setBadge(label, kind);
        // Обновляем главный текст, чтобы не осталось «Продолжаю…» после завершения.
        statusText.setText("err".equals(kind) ? "Задача прервана" : "Задача завершена");
        close.setVisibility(VISIBLE);
        if (collapse) { expanded = false; applyExpanded(); }
    }

    private void applyExpanded() {
        body.setVisibility(expanded ? VISIBLE : GONE);
        toggle.setText(expanded ? "\u2304" : "\u203A");
        // Плашка модели под окном: видна только когда окно развёрнуто и модель известна.
        boolean hasModel = modelName != null && !modelName.isEmpty();
        modelBadge.setVisibility(expanded && hasModel ? VISIBLE : GONE);
        if (expanded) {
            // Показываем только НЕПУСТЫЕ контейнеры — пустые не занимают места.
            boolean hasReason = reasoningView.getText() != null && reasoningView.getText().length() > 0;
            reasoningScroll.setVisibility(hasReason ? VISIBLE : GONE);
            logScroll.setVisibility(logBox.getChildCount() > 0 ? VISIBLE : GONE);
            // При разворачивании: рассуждения — в начало, лог — к последнему действию.
            reasoningScroll.post(new Runnable() { public void run() { reasoningScroll.scrollTo(0, 0); } });
            scrollLogToBottom();
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
