package com.idemobile.app;

import android.app.AlertDialog;
import android.app.Fragment;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Вкладка «Редактор» — порт editor.js из web-версии.
 *
 * Модель точно повторяет web: EditText (редактируемый прозрачный слой) поверх
 * TextView со слоем подсветки синтаксиса. Так сохраняется нативное выделение ОС
 * (ручки, панель копировать/вставить), а подсветка рисуется отдельным слоем.
 *
 * Возможности как в вебе:
 *   • Несколько открытых файлов, вкладки сверху с крестиком закрытия.
 *   • Индикатор несохранённых изменений (точка на вкладке), при закрытии —
 *     диалог «Сохранить / Не сохранять / Отмена».
 *   • Номера строк в жёлобе, синхронный вертикальный скролл.
 *   • Подсветка синтаксиса (Highlight) по расширению файла.
 *   • Сохранение кнопкой в шапке и при закрытии вкладки.
 *   • Просмотр картинок (медиа) для нетекстовых файлов.
 */
public class EditorFragment extends Fragment {

    /** Запись об открытом файле. */
    static class Model {
        String path;
        boolean isMedia;
        String text = "";     // для текстовых
        boolean dirty = false;
        int scrollY = 0;
        byte[] mediaBytes;    // для медиа
        String mime;
        long size;
        /**
         * Файл большой. Такой файл ПО УМОЛЧАНИЮ открывается в виртуализированном
         * просмотрщике (ListView) в режиме чтения: EditText/TextView меряют ВЕСЬ
         * текст при setText (нет виртуализации), из-за чего огромный файл
         * открывался ~20 сек. Но его МОЖНО отредактировать — переключателем
         * «глаз/карандаш» в шапке файл грузится в EditText (с разовой задержкой).
         */
        boolean big = false;
        /**
         * Текущий режим: только чтение (true) или редактирование (false).
         * Большие файлы стартуют в режиме чтения (быстрый ListView), обычные — в
         * режиме редактирования. Пользователь переключает кнопкой в шапке.
         */
        boolean readOnly = false;
        /** Строки файла (кэш для построчного просмотра больших файлов в режиме чтения). */
        String[] lines;
    }

    /**
     * Порог «большого файла»: выше него файл открывается в виртуализированном
     * просмотрщике (ListView, только чтение) вместо EditText. Причина — EditText
     * и слой подсветки (TextView) меряют весь текст при setText, что подвешивает
     * UI на десятки секунд для больших файлов.
     */
    private static final int BIG_FILE_BYTES = 200 * 1024;   // ~200 КБ
    private static final int BIG_FILE_LINES = 5000;         // либо много строк

    /** Дебаунс перерисовки подсветки/жёлоба при редактировании (мс). */
    private static final long HIGHLIGHT_DEBOUNCE_MS = 180;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingHighlight = null;

    private final Map<String, Model> models = new LinkedHashMap<String, Model>();
    private String activePath = null;

    private ExplorerFs fs;
    private LinearLayout tabsRow;
    private HorizontalScrollView tabsScroll;
    private HorizontalScrollView hscroll; // гор. скролл кода (для режима переноса строк)
    private View emptyBox, codeArea, bigArea;
    private ScrollView mediaScroll, vscroll, gutterScroll;
    private LinearLayout mediaBox;
    private TextView gutter, highlightLayer;
    private EditText input;
    private android.widget.ListView bigList;      // виртуализированный просмотр больших файлов
    private BigFileAdapter bigAdapter;
    private ImageView modeBtn;                     // переключатель чтение/редактирование
    /** Горизонтальный сдвиг всех строк в режиме просмотра больших файлов (px). */
    private int bigScrollX = 0;

    /** Настройка «перенос текста» (word wrap). Хранится в Store.settings. */
    private boolean wordWrap = false;

    private boolean suppressWatcher = false;
    private String pendingOpen = null; // путь для открытия, когда view ещё не готов

    /**
     * Подписка на глобальную шину ФС. Порт Editor.onExternalWrite() из web-версии:
     * когда агент меняет файл, открытый в редакторе, содержимое перечитывается
     * СРАЗУ — но только если пользователь его не редактирует (нет несохранённых
     * правок), чтобы не затирать его работу.
     */
    private final FsEvents.Listener fsListener = new FsEvents.Listener() {
        public void onFsChanged() { reloadActiveIfClean(); }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        try {
            return build(inflater, container);
        } catch (Throwable e) {
            App.writeCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView tv = new TextView(getActivity());
            tv.setText("Ошибка вкладки «Редактор»:\n\n" + sw.toString());
            tv.setTextColor(Color.parseColor("#F85149"));
            tv.setTextSize(12f);
            tv.setPadding(24, 24, 24, 24);
            tv.setTextIsSelectable(true);
            ScrollView s = new ScrollView(getActivity());
            s.addView(tv);
            return s;
        }
    }

    private View build(LayoutInflater inflater, ViewGroup container) {
        View root = inflater.inflate(R.layout.fragment_editor, container, false);
        fs = new ExplorerFs(getActivity());

        tabsRow = (LinearLayout) root.findViewById(R.id.ed_tabs);
        tabsScroll = (HorizontalScrollView) root.findViewById(R.id.ed_tabs_scroll);
        emptyBox = root.findViewById(R.id.ed_empty);
        codeArea = root.findViewById(R.id.ed_code_area);
        bigArea = root.findViewById(R.id.ed_big_area);
        bigList = (android.widget.ListView) root.findViewById(R.id.ed_big_list);
        mediaScroll = (ScrollView) root.findViewById(R.id.ed_media);
        mediaBox = (LinearLayout) root.findViewById(R.id.ed_media_box);
        vscroll = (ScrollView) root.findViewById(R.id.ed_vscroll);
        gutterScroll = (ScrollView) root.findViewById(R.id.ed_gutter_scroll);
        gutter = (TextView) root.findViewById(R.id.ed_gutter);
        highlightLayer = (TextView) root.findViewById(R.id.ed_highlight);
        input = (EditText) root.findViewById(R.id.ed_input);
        hscroll = (HorizontalScrollView) root.findViewById(R.id.ed_hscroll);

        // Читаем сохранённое состояние «перенос текста» и применяем при построении.
        wordWrap = Store.get(getActivity()).settings().optBoolean("editorWordWrap", false);

        root.findViewById(R.id.ed_menu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAbout(); }
        });
        modeBtn = (ImageView) root.findViewById(R.id.ed_mode);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMode(); }
        });
        root.findViewById(R.id.ed_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showEditorSettings(); }
        });
        root.findViewById(R.id.ed_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(activePath); }
        });
        root.findViewById(R.id.ed_open_explorer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).switchToExplorer(); }
        });

        // Правки текста → dirty + перерисовка подсветки/номеров строк.
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                if (suppressWatcher) return;
                Model m = active();
                if (m == null || m.isMedia) return;
                m.text = e.toString();
                if (!m.dirty) { m.dirty = true; renderTabs(); }
                scheduleHighlight();
            }
        });

        // Синхронизация вертикального скролла кода и жёлоба номеров строк.
        vscroll.getViewTreeObserver().addOnScrollChangedListener(
            new android.view.ViewTreeObserver.OnScrollChangedListener() {
                public void onScrollChanged() {
                    if (gutterScroll != null && vscroll != null) gutterScroll.setScrollY(vscroll.getScrollY());
                    Model m = active();
                    if (m != null) m.scrollY = vscroll.getScrollY();
                }
            });

        // Горизонтальный «скролл» строк в режиме просмотра больших файлов:
        // при преимущественно горизонтальном жесте сдвигаем весь текст, при
        // вертикальном — отдаём событие ListView для обычной прокрутки.
        setupBigHorizontalScroll();

        renderTabs();
        applyWordWrap(); // применить сохранённый режим переноса строк
        // Отложенное открытие (проводник вызвал openFile до построения view).
        if (pendingOpen != null) {
            final String p = pendingOpen; pendingOpen = null;
            input.post(new Runnable() { public void run() { openFile(p); } });
        }
        FsEvents.addListener(fsListener);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // ФС могла смениться (открыт проект в проводнике) — пересоздаём.
        fs = new ExplorerFs(getActivity());
        FsEvents.addListener(fsListener);
    }

    @Override
    public void onDestroyView() {
        FsEvents.removeListener(fsListener);
        super.onDestroyView();
    }

    /**
     * Перечитать активный файл, если он изменён извне (агентом) и НЕ содержит
     * несохранённых правок пользователя. Медиа/бинарные и dirty-файлы не трогаем.
     */
    private void reloadActiveIfClean() {
        if (input == null || activePath == null) return;
        final Model m = models.get(activePath);
        if (m == null || m.isMedia || m.dirty) return;
        // GitHub-файлы не перечитываем на каждое FS-событие (сеть в UI-потоке нельзя,
        // и кеш уже актуален после наших коммитов).
        try { if (GithubFs.get(getActivity()).isRemotePath(ExplorerFs.normPath(activePath))) return; } catch (Throwable ignored) {}
        if (getActivity() != null) fs = new ExplorerFs(getActivity());
        String fresh;
        try {
            byte[] data = fs.readBytes(activePath);
            if (looksBinary(data) && !isTextExt(ExplorerFs.extname(activePath))) return;
            fresh = new String(data, "UTF-8");
        } catch (Exception e) { return; }
        // Ничего не изменилось — не трогаем (иначе сбросим позицию/выделение).
        if (fresh.equals(m.text)) return;
        m.text = fresh;
        // Большой файл в режиме просмотра — обновляем построчный список, а не EditText.
        if (usesBigViewer(m)) {
            m.lines = fresh.split("\n", -1);
            if (activePath.equals(m.path) && bigAdapter != null) bigAdapter.notifyDataSetChanged();
            return;
        }
        // Обновляем поле, сохраняя позицию курсора в разумных пределах.
        int caret = input.getSelectionStart();
        suppressWatcher = true;
        input.setText(fresh);
        suppressWatcher = false;
        try { input.setSelection(Math.min(caret, fresh.length())); } catch (Exception ignored) {}
        renderHighlight();
    }

    private int dp(int v) { return UiKit.dp(getActivity(), v); }
    private void toast(String s) { if (getActivity() != null) Toast.makeText(getActivity(), s, Toast.LENGTH_SHORT).show(); }
    private Model active() { return activePath == null ? null : models.get(activePath); }

    // =========================================================== ОТКРЫТИЕ
    /** Открыть файл (вызывается проводником через MainActivity). */
    public void openFile(String path) {
        // View ещё не построен (фрагмент только добавляется) — отложим открытие.
        if (input == null) { pendingOpen = path; return; }
        // Гарантируем актуальный источник ФС (мог смениться проект в проводнике).
        if (getActivity() != null) fs = new ExplorerFs(getActivity());
        final String p = ExplorerFs.normPath(path);
        if (models.containsKey(p)) { setActive(p); return; }
        // GitHub-файл читаем в фоне (сетевой запрос нельзя в UI-потоке).
        boolean gh = false;
        try { gh = GithubFs.get(getActivity()).isRemotePath(p); } catch (Throwable ignored) {}
        if (gh) {
            toast("Загрузка из GitHub…");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final byte[] data = fs.readBytes(p);
                        if (getActivity() != null) getActivity().runOnUiThread(new Runnable() {
                            public void run() { buildModelAndActivate(p, data); }
                        });
                    } catch (final Exception e) {
                        if (getActivity() != null) getActivity().runOnUiThread(new Runnable() {
                            public void run() { toast("Не удалось открыть: " + e.getMessage()); }
                        });
                    }
                }
            }).start();
            return;
        }
        byte[] data;
        try { data = fs.readBytes(p); }
        catch (Exception e) { toast("Не удалось открыть: " + e.getMessage()); return; }
        buildModelAndActivate(p, data);
    }

    /** Построить модель по прочитанным байтам и активировать вкладку. */
    private void buildModelAndActivate(String path, byte[] data) {
        Model m = new Model();
        m.path = path;
        m.size = data.length;
        if (isImage(path)) {
            m.isMedia = true;
            m.mediaBytes = data;
            m.mime = mimeOf(path);
        } else {
            m.isMedia = false;
            if (looksBinary(data) && !isTextExt(ExplorerFs.extname(path))) {
                StringBuilder sb = new StringBuilder();
                sb.append("// Бинарный файл: ").append(path).append('\n');
                sb.append("// Размер: ").append(ExplorerFs.bytesToStr(data.length)).append('\n');
                sb.append("// Первые 512 байт (hex):\n\n");
                int lim = Math.min(512, data.length);
                for (int i = 0; i < lim; i++) {
                    sb.append(String.format("%02x ", data[i] & 0xFF));
                    if ((i + 1) % 16 == 0) sb.append('\n');
                }
                m.text = sb.toString();
            } else {
                try { m.text = new String(data, "UTF-8"); } catch (Exception e) { m.text = ""; }
            }
            // Большой файл → по умолчанию открывается в быстром просмотре (ListView,
            // только чтение); обычный — сразу в редактировании. Режим переключается
            // кнопкой «глаз/карандаш» в шапке.
            m.big = isBigText(m.text, data.length);
            m.readOnly = m.big;
            if (m.big) m.lines = m.text.split("\n", -1);
        }
        models.put(path, m);
        setActive(path);
    }

    private void setActive(String path) {
        activePath = path;
        final Model m = models.get(path);
        if (m == null) { renderTabs(); return; }
        if (m.isMedia) {
            renderMedia(m);
        } else if (usesBigViewer(m)) {
            renderBig(m);
        } else {
            suppressWatcher = true;
            input.setText(m.text);
            suppressWatcher = false;
            // Режим чтения обычного файла → EditText недоступен для правки.
            input.setFocusable(!m.readOnly);
            input.setFocusableInTouchMode(!m.readOnly);
            input.setCursorVisible(!m.readOnly);
            // Перенос строк переприменяем при активации: ограничение ширины слоёв
            // считается по вьюпорту через hscroll.post, а при открытии нового файла
            // оно не переустанавливалось — из-за этого перенос «отваливался» и
            // лечился лишь повторным выкл→вкл в настройках.
            applyWordWrap();
            renderHighlight();
            final int sy = m.scrollY;
            vscroll.post(new Runnable() { public void run() { vscroll.setScrollY(sy); if (gutterScroll != null) gutterScroll.setScrollY(sy); } });
        }
        updateModeButton();
        renderTabs();
        // Прокрутить полосу вкладок к активной.
        tabsScroll.post(new Runnable() { public void run() { scrollTabsToActive(); } });
    }

    /** Файл сейчас показывается в быстром просмотрщике (ListView, только чтение). */
    private boolean usesBigViewer(Model m) {
        return m != null && !m.isMedia && m.big && m.readOnly;
    }

    /**
     * Обновить кнопку режима в шапке. Скрыта для медиа и пустого редактора.
     * Глаз = сейчас режим чтения (нажатие включит редактирование), карандаш =
     * сейчас режим редактирования (нажатие включит чтение).
     */
    private void updateModeButton() {
        if (modeBtn == null) return;
        Model m = active();
        if (m == null || m.isMedia) { modeBtn.setVisibility(View.GONE); return; }
        modeBtn.setVisibility(View.VISIBLE);
        modeBtn.setImageResource(m.readOnly ? R.drawable.ic_eye : R.drawable.ic_pencil);
        modeBtn.setContentDescription(m.readOnly ? "Режим чтения (нажмите для редактирования)"
                                                 : "Режим редактирования (нажмите для чтения)");
    }

    /**
     * Переключить режим активного файла между чтением и редактированием. Для
     * большого файла это переключает быстрый просмотрщик (ListView) ↔ EditText;
     * вход в редактирование большого файла может занять секунду (EditText меряет
     * весь текст), поэтому показываем подсказку.
     */
    private void toggleMode() {
        final Model m = active();
        if (m == null || m.isMedia) return;
        boolean goingToEdit = m.readOnly; // сейчас чтение → станет редактирование
        // Перед сменой режима синхронизируем текст из EditText, если он был активен.
        if (!m.readOnly && !usesBigViewer(m)) m.text = input.getText().toString();
        m.readOnly = !m.readOnly;
        if (goingToEdit && m.big) toast("Открываю большой файл для редактирования…");
        // Перерисовать активную вкладку в нужном режиме.
        setActive(activePath);
    }

    // =========================================================== ВКЛАДКИ
    private void renderTabs() {
        if (tabsRow == null) return;
        tabsRow.removeAllViews();
        boolean any = !models.isEmpty();
        tabsScroll.setVisibility(any ? View.VISIBLE : View.GONE);

        // Видимость областей: пусто / медиа / большой файл (ListView) / обычный код.
        Model act = active();
        boolean showEmpty = !any;
        boolean showMedia = any && act != null && act.isMedia;
        boolean showBig = any && usesBigViewer(act);
        boolean showCode = any && !showMedia && !showBig;
        emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        mediaScroll.setVisibility(showMedia ? View.VISIBLE : View.GONE);
        if (bigArea != null) bigArea.setVisibility(showBig ? View.VISIBLE : View.GONE);
        codeArea.setVisibility(showCode ? View.VISIBLE : View.GONE);

        for (final Map.Entry<String, Model> en : models.entrySet()) {
            final String path = en.getKey();
            final Model m = en.getValue();
            boolean isActive = path.equals(activePath);

            LinearLayout tab = new LinearLayout(getActivity());
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(14), dp(11), dp(10), dp(11));
            tab.setBackgroundColor(isActive ? Color.parseColor("#0E1116") : Color.parseColor("#0B0E13"));

            // Индикатор dirty (точка) + имя.
            if (m.dirty) {
                TextView dot = new TextView(getActivity());
                dot.setText("\u25CF");
                dot.setTextColor(Color.parseColor("#E3B341"));
                dot.setTextSize(10f);
                dot.setPadding(0, 0, dp(6), 0);
                tab.addView(dot);
            }
            TextView label = new TextView(getActivity());
            label.setText(ExplorerFs.basename(path));
            label.setTextColor(isActive ? Color.WHITE : Color.parseColor("#8B949E"));
            label.setTextSize(15f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            tab.addView(label);

            TextView close = new TextView(getActivity());
            close.setText("\u2715");
            close.setTextColor(Color.parseColor("#8B949E"));
            close.setTextSize(15f);
            close.setPadding(dp(12), 0, dp(2), 0);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { closeFile(path); }
            });
            tab.addView(close);

            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setActive(path); }
            });

            // Синяя полоска-подчёркивание активной вкладки.
            LinearLayout wrap = new LinearLayout(getActivity());
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(tab);
            View underline = new View(getActivity());
            underline.setBackgroundColor(isActive ? Color.parseColor("#1E88FF") : Color.TRANSPARENT);
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
            underline.setLayoutParams(up);
            wrap.addView(underline);
            wrap.setTag(path);

            tabsRow.addView(wrap);
        }
    }

    private void scrollTabsToActive() {
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            View w = tabsRow.getChildAt(i);
            if (activePath != null && activePath.equals(w.getTag())) {
                tabsScroll.smoothScrollTo(w.getLeft(), 0);
                break;
            }
        }
    }

    // =========================================================== ПОДСВЕТКА / ЖЁЛОБ
    private void renderHighlight() {
        Model m = active();
        // Медиа и большие файлы через EditText не рисуются (у больших — свой
        // виртуализированный ListView-просмотр).
        if (m == null || m.isMedia || usesBigViewer(m)) { highlightLayer.setText(""); gutter.setText(""); return; }
        String text = input.getText().toString();
        try {
            highlightLayer.setText(Highlight.byPath(text, activePath));
        } catch (Throwable e) {
            highlightLayer.setText(text);
        }
        // хвостовой перевод строки, чтобы высота слоя совпадала с EditText
        highlightLayer.append("\n");
        renderGutter(text);
    }

    /**
     * Перерисовка подсветки при вводе. Обычные файлы — сразу; большой файл в
     * режиме РЕДАКТИРОВАНИЯ подсвечивается с дебаунсом (подсветка всего большого
     * текста дорогая), чтобы набор не лагал.
     */
    private void scheduleHighlight() {
        Model m = active();
        boolean big = m != null && m.big;   // большой файл в режиме редактирования
        if (!big) { renderHighlight(); return; }
        if (pendingHighlight != null) uiHandler.removeCallbacks(pendingHighlight);
        pendingHighlight = new Runnable() { public void run() { pendingHighlight = null; renderHighlight(); } };
        uiHandler.postDelayed(pendingHighlight, HIGHLIGHT_DEBOUNCE_MS);
    }
    private void renderGutter(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) sb.append(i).append('\n');
        gutter.setText(sb.toString());
    }

    // =========================================================== БОЛЬШИЕ ФАЙЛЫ
    /**
     * Показать большой файл в виртуализированном ListView. Каждая строка —
     * отдельная переиспользуемая вьюха; подсветка считается построчно и только
     * для видимых строк, поэтому открытие мгновенно даже для файлов на сотни
     * тысяч строк. Режим — только чтение (текст выделяется, но не редактируется).
     */
    private void renderBig(Model m) {
        if (bigList == null) return;
        // Всегда перечитываем строки из текущего текста: файл мог быть отредактирован
        // в режиме EditText, а затем переключён обратно в просмотр.
        m.lines = m.text.split("\n", -1);
        bigScrollX = 0;
        bigAdapter = new BigFileAdapter(m);
        bigList.setAdapter(bigAdapter);
        bigList.post(new Runnable() { public void run() { bigList.setSelection(0); } });
    }

    /**
     * Настроить горизонтальное панорамирование текста в просмотре больших файлов.
     * Отслеживаем жест: если он горизонтальный — двигаем bigScrollX и просим
     * ListView не перехватывать событие (иначе он начнёт вертикальный скролл);
     * если вертикальный — не мешаем стандартной прокрутке списка.
     */
    private void setupBigHorizontalScroll() {
        if (bigList == null) return;
        bigList.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, lastX;
            boolean horiz = false;
            final int slop = android.view.ViewConfiguration.get(getActivity()).getScaledTouchSlop();
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = lastX = e.getX(); downY = e.getY(); horiz = false;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!horiz && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                            horiz = true;
                        }
                        if (horiz) {
                            int delta = (int) (lastX - e.getX());
                            lastX = e.getX();
                            bigScrollX = Math.max(0, bigScrollX + delta);
                            applyBigScrollX();
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            return true; // поглощаем — список не должен скроллиться вертикально
                        }
                        return false;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        boolean was = horiz; horiz = false;
                        return was;
                }
                return false;
            }
        });
    }

    /** Сдвинуть все видимые строки большого файла по горизонтали (общий скролл). */
    private void applyBigScrollX() {
        if (bigList == null) return;
        for (int i = 0; i < bigList.getChildCount(); i++) {
            View row = bigList.getChildAt(i);
            if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() >= 2) {
                View code = ((LinearLayout) row).getChildAt(1);
                code.scrollTo(bigScrollX, 0);
            }
        }
    }

    /**
     * Адаптер построчного просмотра большого файла. Возвращает по TextView на
     * строку: слева номер (жёлоб), справа подсвеченный код. Подсветка одной
     * строки дёшева, а ListView запрашивает только видимые строки.
     */
    private class BigFileAdapter extends android.widget.BaseAdapter {
        private final Model m;
        private final String lang;
        private final int gutterChars;
        BigFileAdapter(Model m) {
            this.m = m;
            this.lang = Highlight.langFromPath(m.path);
            this.gutterChars = String.valueOf(Math.max(1, m.lines.length)).length();
        }
        public int getCount() { return m.lines == null ? 0 : m.lines.length; }
        public Object getItem(int pos) { return m.lines[pos]; }
        public long getItemId(int pos) { return pos; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView num, code;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                num = (TextView) row.getChildAt(0);
                code = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(getActivity());
                row.setOrientation(LinearLayout.HORIZONTAL);
                num = new TextView(getActivity());
                num.setTypeface(Typeface.MONOSPACE);
                num.setTextSize(14f);
                num.setTextColor(Color.parseColor("#6E7681"));
                num.setBackgroundColor(Color.parseColor("#0B0E13"));
                num.setPadding(dp(10), 0, dp(10), 0);
                num.setGravity(Gravity.RIGHT);
                num.setMinWidth((gutterChars * dp(9)) + dp(20));
                code = new TextView(getActivity());
                code.setTypeface(Typeface.MONOSPACE);
                code.setTextSize(14f);
                code.setTextColor(Color.parseColor("#D4D4D4"));
                code.setPadding(dp(10), 0, dp(16), 0);
                code.setSingleLine(true);            // одна экранная строка = одна строка файла
                code.setHorizontallyScrolling(true);
                code.setEllipsize(null);
                code.setTextIsSelectable(false);
                row.addView(num);
                // Код занимает оставшуюся ширину (0dp + weight): TextView не меряет
                // полную ширину длинной строки, а рисует видимую часть; длинные
                // строки просматриваются горизонтальным жестом (scrollTo).
                row.addView(code, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            num.setText(String.valueOf(pos + 1));
            String line = m.lines[pos];
            try {
                code.setText(Highlight.highlight(line, lang));
            } catch (Throwable e) {
                code.setText(line);
            }
            // Горизонтальный сдвиг общий для всех строк (эмуляция гор. скролла).
            code.scrollTo(bigScrollX, 0);
            return row;
        }
    }

    // =========================================================== МЕДИА
    private void renderMedia(Model m) {
        mediaBox.removeAllViews();
        TextView info = new TextView(getActivity());
        info.setText(m.path + " · " + ExplorerFs.bytesToStr(m.size) + " · " + m.mime);
        info.setTextColor(Color.parseColor("#8B949E"));
        info.setTextSize(12f);
        info.setPadding(dp(4), dp(4), dp(4), dp(10));
        mediaBox.addView(info);
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                    .decodeByteArray(m.mediaBytes, 0, m.mediaBytes.length);
            if (bmp != null) {
                ImageView iv = new ImageView(getActivity());
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                mediaBox.addView(iv);
            } else {
                TextView t = new TextView(getActivity());
                t.setText("Не удалось отобразить изображение");
                t.setTextColor(Color.parseColor("#F85149"));
                mediaBox.addView(t);
            }
        } catch (Throwable e) {
            TextView t = new TextView(getActivity());
            t.setText("Ошибка: " + e.getMessage());
            t.setTextColor(Color.parseColor("#F85149"));
            mediaBox.addView(t);
        }
    }

    // =========================================================== СОХРАНЕНИЕ / ЗАКРЫТИЕ
    public void save(final String path) {
        if (path == null) return;
        final Model m = models.get(path);
        if (m == null || m.isMedia) return;
        if (usesBigViewer(m)) return; // просмотр (только чтение) — сохранять нечего
        // Читаем актуальный текст из EditText, только если файл сейчас в нём (не в
        // просмотрщике). Большой файл в режиме редактирования тоже здесь.
        if (path.equals(activePath) && !usesBigViewer(m)) m.text = input.getText().toString();
        boolean gh = false;
        try { gh = GithubFs.get(getActivity()).isRemotePath(ExplorerFs.normPath(path)); } catch (Throwable ignored) {}
        if (gh) {
            // Сохранение GitHub-файла = коммит на сайт → в фоне.
            toast("Коммит в GitHub…");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        fs.writeText(path, m.text);
                        if (getActivity() != null) getActivity().runOnUiThread(new Runnable() {
                            public void run() { m.dirty = false; renderTabs(); toast("Сохранено на GitHub"); }
                        });
                    } catch (final Exception e) {
                        if (getActivity() != null) getActivity().runOnUiThread(new Runnable() {
                            public void run() { toast("Ошибка сохранения: " + e.getMessage()); }
                        });
                    }
                }
            }).start();
            return;
        }
        try {
            fs.writeText(path, m.text);
            m.dirty = false;
            renderTabs();
            toast("Сохранено");
        } catch (Exception e) { toast("Ошибка сохранения: " + e.getMessage()); }
    }

    /** Закрыть вкладку. Если есть несохранённые правки — спросить. */
    private void closeFile(final String path) {
        final Model m = models.get(path);
        if (m == null) return;
        if (path.equals(activePath) && !m.isMedia && !usesBigViewer(m)) m.text = input.getText().toString();
        if (m.dirty && !m.isMedia) {
            new AlertDialog.Builder(getActivity())
                    .setTitle("Несохранённые изменения")
                    .setMessage("Сохранить изменения в «" + ExplorerFs.basename(path) + "»?")
                    .setPositiveButton("Сохранить", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { save(path); doClose(path); }
                    })
                    .setNeutralButton("Не сохранять", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { doClose(path); }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } else {
            doClose(path);
        }
    }
    private void doClose(String path) {
        models.remove(path);
        if (path.equals(activePath)) {
            activePath = null;
            if (!models.isEmpty()) {
                String next = models.keySet().iterator().next();
                setActive(next);
            } else {
                suppressWatcher = true; input.setText(""); suppressWatcher = false;
                highlightLayer.setText(""); gutter.setText("");
                updateModeButton();
                renderTabs();
            }
        } else {
            renderTabs();
        }
    }

    // =========================================================== ВСПОМОГАТЕЛЬНОЕ
    private void showAbout() {
        new AlertDialog.Builder(getActivity())
                .setTitle("Редактор")
                .setMessage("Редактор кода с подсветкой синтаксиса.\n\n"
                        + "Открывайте файлы из проводника. Изменённые файлы помечаются точкой; "
                        + "при закрытии вкладки предлагается сохранить. Кнопка сохранения — в шапке.")
                .setPositiveButton("OK", null)
                .show();
    }

    // =========================================================== НАСТРОЙКИ РЕДАКТОРА
    /**
     * Меню настроек редактора (пока одна настройка — «перенос текста» с ползунком,
     * внизу кнопки ОК/Отмена). При «Отмена» изменения откатываются, при «ОК» —
     * применяются и сохраняются в Store.
     */
    private void showEditorSettings() {
        final boolean prev = wordWrap;

        LinearLayout wrap = new LinearLayout(getActivity());
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        wrap.setPadding(pad, dp(8), pad, dp(4));

        // Строка настройки: подпись слева + переключатель (ползунок) справа.
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(getActivity());
        label.setText("Перенос текста");
        label.setTextColor(Color.parseColor("#E6EDF3"));
        label.setTextSize(16f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, llp);

        final android.widget.Switch sw = new android.widget.Switch(getActivity());
        sw.setChecked(wordWrap);
        row.addView(sw);
        wrap.addView(row);

        // Ползунок сразу меняет режим (живой предпросмотр); при «Отмена» откатим.
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                wordWrap = checked;
                applyWordWrap();
            }
        });

        final AlertDialog dlg = new AlertDialog.Builder(getActivity())
                .setTitle("Настройки редактора")
                .setView(wrap)
                .setPositiveButton("ОК", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Store.get(getActivity()).setSetting("editorWordWrap", wordWrap);
                    }
                })
                .setNegativeButton("Отмена", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        wordWrap = prev; // откат
                        applyWordWrap();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    public void onCancel(android.content.DialogInterface d) {
                        wordWrap = prev; // откат при закрытии вне кнопок
                        applyWordWrap();
                    }
                })
                .create();
        dlg.show();
    }

    /**
     * Применить режим переноса строк к слою кода. ВКЛ — строки переносятся по
     * ширине экрана, горизонтальная прокрутка выключается; ВЫКЛ — длинные строки
     * не переносятся, работает горизонтальный скролл (как в web-редакторе).
     */
    private void applyWordWrap() {
        if (input == null || hscroll == null || highlightLayer == null) return;
        input.setHorizontallyScrolling(!wordWrap);
        highlightLayer.setHorizontallyScrolling(!wordWrap);
        if (wordWrap) {
            // Ограничиваем ширину слоёв шириной видимой области — тогда текст
            // переносится, а HorizontalScrollView не может прокручиваться вширь.
            hscroll.post(new Runnable() {
                public void run() {
                    int w = hscroll.getWidth();
                    if (w <= 0) return;
                    input.setMaxWidth(w);
                    highlightLayer.setMaxWidth(w);
                    // Фиксируем ширину слоёв, чтобы перенос считался по вьюпорту.
                    setLayerWidth(input, w);
                    setLayerWidth(highlightLayer, w);
                    renderHighlight();
                }
            });
        } else {
            input.setMaxWidth(Integer.MAX_VALUE);
            highlightLayer.setMaxWidth(Integer.MAX_VALUE);
            setLayerWidth(input, ViewGroup.LayoutParams.WRAP_CONTENT);
            setLayerWidth(highlightLayer, ViewGroup.LayoutParams.WRAP_CONTENT);
            renderHighlight();
        }
    }

    /** Установить ширину layout-параметров вью (px или WRAP_CONTENT). */
    private void setLayerWidth(View v, int width) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = width;
        v.setLayoutParams(lp);
    }

    private boolean isImage(String path) {
        String e = ExplorerFs.extname(path);
        return e.equals("png") || e.equals("jpg") || e.equals("jpeg") || e.equals("gif")
                || e.equals("webp") || e.equals("bmp");
    }
    private String mimeOf(String path) {
        String e = ExplorerFs.extname(path);
        if (e.equals("png")) return "image/png";
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("bmp")) return "image/bmp";
        return "application/octet-stream";
    }
    private boolean isTextExt(String e) {
        switch (e) {
            case "txt": case "md": case "markdown": case "js": case "ts": case "jsx": case "tsx":
            case "json": case "html": case "htm": case "css": case "scss": case "less": case "xml":
            case "yaml": case "yml": case "toml": case "ini": case "conf": case "py": case "rb":
            case "go": case "rs": case "c": case "h": case "cpp": case "cc": case "hpp": case "java":
            case "kt": case "kts": case "swift": case "php": case "sh": case "bash": case "sql":
            case "gradle": case "properties": case "gitignore": case "csv": case "log": case "":
                return true;
            default: return false;
        }
    }
    /**
     * Большой ли текст для целей подсветки: по размеру в байтах ИЛИ по числу строк.
     * Число строк считаем с ранним выходом (не сканируем гигантский файл целиком).
     */
    private boolean isBigText(String text, long bytes) {
        if (bytes >= BIG_FILE_BYTES) return true;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') { lines++; if (lines > BIG_FILE_LINES) return true; }
        }
        return false;
    }

    private boolean looksBinary(byte[] data) {
        int lim = Math.min(data.length, 4096);
        for (int i = 0; i < lim; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return true;
        }
        return false;
    }

    // Уведомления от проводника об изменениях путей.
    public void onPathDeleted(String path) {
        List<String> toRemove = new ArrayList<String>();
        for (String p : models.keySet()) if (p.equals(path) || p.startsWith(path + "/")) toRemove.add(p);
        for (String p : toRemove) models.remove(p);
        if (activePath != null && toRemove.contains(activePath)) {
            activePath = models.isEmpty() ? null : models.keySet().iterator().next();
            if (activePath != null) setActive(activePath); else renderTabs();
        } else renderTabs();
    }
}
