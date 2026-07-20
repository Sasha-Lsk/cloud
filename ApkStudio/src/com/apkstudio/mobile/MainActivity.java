package com.apkstudio.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Главный экран APK Studio.
 *
 * Две зоны:
 *   • Декомпиляция — выбор APK, кнопки «APK → Smali» и «APK → Java».
 *   • Сборка       — выбор папки проекта, кнопки «Smali → APK» и «Java → APK».
 * Ниже — подробный журнал процессов (Logger) с кнопкой копирования логов
 * и кнопкой очистки (очищает и журнал, и выбранные в пикерах пути).
 *
 * Все тяжёлые операции выполняются в фоновом потоке; журнал обновляется вживую.
 */
public class MainActivity extends Activity {

    private TextView pickDecompileView, pickBuildView, statusView, logView;
    private ScrollView logScroll;
    private ProgressBar progress;

    private File selectedApk;      // выбранный APK для декомпиляции
    private File selectedProject;  // выбранная папка проекта для сборки

    private Logger log;
    private Tools tools;
    private Decompiler decompiler;
    private Builder builder;

    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        pickDecompileView = (TextView) findViewById(R.id.pick_decompile);
        pickBuildView = (TextView) findViewById(R.id.pick_build);
        statusView = (TextView) findViewById(R.id.status);
        logView = (TextView) findViewById(R.id.log);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        progress = (ProgressBar) findViewById(R.id.progress);

        log = new Logger(this, logView, logScroll);
        tools = new Tools(this, log);
        decompiler = new Decompiler(tools, log);
        builder = new Builder(tools, log);

        wire();
        ensureStoragePermission();

        log.info("APK Studio запущен. Рабочая папка: " + Storage.root().getAbsolutePath());
        log.info("Устройство: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), ABI " + tools.primaryAbi());
    }

    private void wire() {
        findViewById(R.id.pick_decompile).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new FilePickerDialog(MainActivity.this, FilePickerDialog.MODE_APK, new FilePickerDialog.OnPick() {
                    public void onPick(File f) {
                        selectedApk = f;
                        pickDecompileView.setText("📦  " + f.getName());
                        pickDecompileView.setTextColor(0xFFE6EDF3);
                        log.info("Выбран APK для декомпиляции: " + f.getAbsolutePath());
                    }
                }).show();
            }
        });

        findViewById(R.id.pick_build).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new FilePickerDialog(MainActivity.this, FilePickerDialog.MODE_DIR, new FilePickerDialog.OnPick() {
                    public void onPick(File f) {
                        selectedProject = f;
                        pickBuildView.setText("📂  " + f.getName());
                        pickBuildView.setTextColor(0xFFE6EDF3);
                        log.info("Выбрана папка проекта для сборки: " + f.getAbsolutePath());
                    }
                }).show();
            }
        });

        findViewById(R.id.btn_apk_smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkToSmali(); }
        });
        findViewById(R.id.btn_apk_java).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkToJava(); }
        });
        findViewById(R.id.btn_smali_apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runSmaliToApk(); }
        });
        findViewById(R.id.btn_java_apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runJavaToApk(); }
        });

        findViewById(R.id.btn_copy_logs).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyLogs(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearAll(); }
        });
        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHelp(); }
        });
    }

    // ─────────────────────────────────────────── операции

    private void runApkToSmali() {
        if (!checkApk()) return;
        final File apk = selectedApk;
        final File out = new File(Storage.decompileDir(), baseName(apk) + "_smali");
        runTask("APK → Smali", new Task() {
            public void run() throws Exception { decompiler.apkToSmali(apk, out); }
            public void done() { offerOpen(out, false); }
        });
    }

    private void runApkToJava() {
        if (!checkApk()) return;
        final File apk = selectedApk;
        final File out = new File(Storage.decompileDir(), baseName(apk) + "_java");
        runTask("APK → Java", new Task() {
            public void run() throws Exception { decompiler.apkToJava(apk, out); }
            public void done() { offerOpen(out, false); }
        });
    }

    private void runSmaliToApk() {
        if (!checkProject()) return;
        final File proj = selectedProject;
        final File out = new File(Storage.compileDir(), baseName(proj) + ".apk");
        runTask("Smali → APK", new Task() {
            public void run() throws Exception { builder.smaliToApk(proj, out); }
            public void done() { offerOpen(out, true); }
        });
    }

    private void runJavaToApk() {
        if (!checkProject()) return;
        final File proj = selectedProject;
        final File out = new File(Storage.compileDir(), baseName(proj) + ".apk");
        runTask("Java → APK", new Task() {
            public void run() throws Exception { builder.javaToApk(proj, out); }
            public void done() { offerOpen(out, true); }
        });
    }

    private interface Task { void run() throws Exception; void done(); }

    private void runTask(final String name, final Task task) {
        if (busy) { toast("Дождитесь завершения текущей операции"); return; }
        busy = true;
        setBusyUi(true, name + "…");
        new Thread(new Runnable() {
            public void run() {
                final long t0 = System.currentTimeMillis();
                boolean okFlag = false;
                LogBridge bridge = new LogBridge(log);
                bridge.attach();
                try {
                    task.run();
                    okFlag = true;
                } catch (final Throwable e) {
                    log.exception("Операция «" + name + "» не выполнена", e);
                } finally {
                    bridge.detach();
                }
                final boolean ok = okFlag;
                final long dt = System.currentTimeMillis() - t0;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        setBusyUi(false, ok ? (name + " — готово за " + dt + " мс")
                                : (name + " — ошибка (см. журнал)"));
                        if (ok) { log.ok(name + " завершено за " + dt + " мс"); task.done(); }
                        else    { log.err(name + " прервано."); }
                    }
                });
            }
        }, "apkstudio-op").start();
    }

    private void setBusyUi(boolean b, String status) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        statusView.setText(status);
    }

    // ─────────────────────────────────────────── диалоги/утилиты

    /** Диалог после операции: перейти в папку результата (или установить APK). */
    private void offerOpen(final File result, final boolean isApk) {
        try {
            String msg = isApk
                    ? "APK собран:\n" + result.getName() + "\n\nПапка: " + Storage.COMPILE_DIR
                    : "Проект распакован:\n" + result.getName() + "\n\nПапка: " + Storage.DECOMPILE_DIR;
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(isApk ? "Сборка завершена" : "Декомпиляция завершена")
                    .setMessage(msg)
                    .setNegativeButton("Закрыть", null)
                    .setPositiveButton("Открыть папку", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            Storage.openFolder(MainActivity.this, result.getParentFile());
                        }
                    });
            if (isApk) {
                b.setNeutralButton("Установить APK", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Storage.openApk(MainActivity.this, result);
                    }
                });
            }
            b.show();
        } catch (Throwable ignored) {}
    }

    private void copyLogs() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ApkStudio logs", log.fullText()));
        toast("Журнал скопирован в буфер обмена");
    }

    /** Очистка журнала + сброс выбранных в пикерах путей (по требованию ТЗ). */
    private void clearAll() {
        log.clear();
        selectedApk = null;
        selectedProject = null;
        pickDecompileView.setText("📁  Выбрать APK…");
        pickDecompileView.setTextColor(0xFF8B949E);
        pickBuildView.setText("📂  Выбрать папку проекта…");
        pickBuildView.setTextColor(0xFF8B949E);
        statusView.setText("Готов к работе.");
        toast("Журнал и выбранные файлы очищены");
    }

    private boolean checkApk() {
        if (selectedApk == null || !selectedApk.exists()) { toast("Сначала выберите APK"); return false; }
        return true;
    }

    private boolean checkProject() {
        if (selectedProject == null || !selectedProject.exists()) { toast("Сначала выберите папку проекта"); return false; }
        return true;
    }

    private static String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ─────────────────────────────────────────── разрешения

    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) { // Android 11+: MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Нужен доступ к файлам")
                        .setMessage("Для чтения APK и записи результатов в папку Download/ApkStudio "
                                + "предоставьте доступ ко всем файлам.")
                        .setPositiveButton("Открыть настройки", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                try {
                                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                    i.setData(Uri.parse("package:" + getPackageName()));
                                    startActivity(i);
                                } catch (Throwable t) {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                }
                            }
                        })
                        .setNegativeButton("Позже", null)
                        .show();
            }
        } else {
            String[] perms = {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            try { requestPermissions(perms, 1); } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────── справка (инструкция)

    private void showHelp() {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        tv.setTextColor(0xFFC9D1D9);
        tv.setTextSize(13f);
        tv.setTextIsSelectable(true);
        tv.setText(HELP_TEXT);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Как раскладывать файлы для сборки")
                .setView(sv)
                .setPositiveButton("Понятно", null)
                .show();
    }

    private static final String HELP_TEXT =
        "РАБОЧАЯ ПАПКА (общая для распаковки и сборки):\n" +
        "/storage/emulated/0/Download/ApkStudio/\n" +
        "  • decompiler_apk/ — сюда распаковываются проекты\n" +
        "  • compiler_apk/   — сюда сохраняются собранные .apk\n\n" +
        "─── ДЕКОМПИЛЯЦИЯ ───\n" +
        "APK → Smali: полный проект apktool. Структура (мировой стандарт):\n" +
        "  <имя>_smali/\n" +
        "    AndroidManifest.xml   — манифест (декодированный)\n" +
        "    apktool.yml           — метаданные (SDK, framework) — НЕ удалять!\n" +
        "    res/                  — ресурсы (layout, values, drawable…)\n" +
        "    smali/                — код (.smali). Multidex → smali_classes2/…\n" +
        "    assets/  lib/  original/  unknown/\n\n" +
        "APK → Java: читаемые Java-исходники (jadx) в sources/ + ресурсы.\n" +
        "  Рядом создаётся _buildable_smali/ — именно он собирается обратно.\n" +
        "  Java правьте для понимания логики, а изменения переносите в smali,\n" +
        "  затем собирайте кнопкой «Java → APK» (берёт _buildable_smali).\n\n" +
        "─── СБОРКА ───\n" +
        "Smali → APK: выберите папку <имя>_smali (где лежит apktool.yml).\n" +
        "Java → APK: выберите папку <имя>_java (где есть _buildable_smali).\n" +
        "Конвейер: apktool b (нативный aapt2) → zipalign → подпись apksig v1/v2/v3.\n\n" +
        "ЧТОБЫ ПЕРЕСОБРАННОЕ ПРИЛОЖЕНИЕ РАБОТАЛО НА Android 8–12 БЕЗ ОКНА\n" +
        "«разработано для более старой версии системы»:\n" +
        "  В AndroidManifest.xml / apktool.yml поднимите targetSdkVersion\n" +
        "  (targetSdkVersion) минимум до 30–33. minSdkVersion можно оставить\n" +
        "  низким (например 21) — тогда APK ставится и на старые, и на новые\n" +
        "  Android, а системное предупреждение не появляется.\n" +
        "  В apktool.yml это поле targetSdkVersion в блоке sdkInfo.\n\n" +
        "ЧАСТЫЕ ОШИБКИ:\n" +
        "  • Нет apktool.yml → выбрана не та папка.\n" +
        "  • На Android 10+ сборка ресурсов (aapt2) может блокироваться\n" +
        "    системой (запуск бинарника из data). Смотрите журнал — там\n" +
        "    подробная причина; её можно скопировать кнопкой ⧉ вверху.";
}
