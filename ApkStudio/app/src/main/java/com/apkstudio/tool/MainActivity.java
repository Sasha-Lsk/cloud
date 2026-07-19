package com.apkstudio.tool;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 101;
    private static final int REQ_PICK_JAR = 102;

    private EditText etApk;
    private TextView tvLog;
    private Log log;
    private Env env;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        etApk = findViewById(R.id.etApkPath);
        tvLog = findViewById(R.id.tvLog);
        log = new Log(this, tvLog);

        findViewById(R.id.btnPick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickApk(); }
        });
        findViewById(R.id.btnToSmali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { run(1); }
        });
        findViewById(R.id.btnToJava).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { run(2); }
        });
        findViewById(R.id.btnBuild).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { run(3); }
        });
        findViewById(R.id.btnClasses).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickJar(); }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareBuilt(); }
        });

        ensureStoragePermission();

        new Thread(new Runnable() {
            public void run() {
                try {
                    env = new Env(MainActivity.this);
                    env.prepare(log);
                } catch (Exception e) {
                    log.line("Ошибка подготовки: " + e);
                }
            }
        }).start();
    }

    // ---- Доступ ко всем файлам (нужен для записи в Download на Android 11+) ----
    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {          // Android 11+
                if (!Environment.isExternalStorageManager()) {
                    log.line("Дайте приложению доступ ко всем файлам (откроется настройка).");
                    Intent i = new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    try { startActivity(i); }
                    catch (Exception e) {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            } else {                                     // Android 6..10
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, 200);
            }
        } catch (Exception e) {
            log.line("Не удалось запросить доступ к файлам: " + e);
        }
    }

    // ---- Выбор APK через системный диалог (SAF) ----
    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Нет файлового менеджера", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickJar() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_JAR);
        } catch (Exception e) {
            Toast.makeText(this, "Нет файлового менеджера", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_PICK) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (env == null) env = new Env(MainActivity.this);
                        File dst = new File(env.work, "input.apk");
                        copyUri(uri, dst);
                        final String p = dst.getAbsolutePath();
                        runOnUiThread(new Runnable() {
                            public void run() { etApk.setText(p); }
                        });
                        log.line("APK выбран -> " + p);
                    } catch (Exception e) {
                        log.line("Ошибка чтения APK: " + e);
                    }
                }
            }).start();
        } else if (req == REQ_PICK_JAR) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (env == null) env = new Env(MainActivity.this);
                        File jar = new File(env.work, "classes_input.jar");
                        copyUri(uri, jar);
                        File base = new File(env.work, "input.apk");
                        log.line("jar с классами выбран, базовый APK: " + base);
                        Pipeline p = new Pipeline(env, log);
                        p.buildFromClasses(jar, base);
                    } catch (Exception e) {
                        log.line("ОШИБКА jar->APK: " + e);
                    }
                }
            }).start();
        }
    }

    private void copyUri(Uri uri, File dst) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---- Поделиться / открыть готовый APK ----
    private void shareBuilt() {
        try {
            if (env == null) env = new Env(this);
            File apk = new File(env.out, "built_signed.apk");
            if (!apk.exists()) {
                Toast.makeText(this, "Сначала соберите APK", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = ApkProvider.uriFor("built_signed.apk");
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Поделиться APK"));
        } catch (Exception e) {
            log.line("Ошибка share: " + e);
        }
    }

    private void run(final int mode) {
        final String path = etApk.getText().toString().trim();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (env == null) env = new Env(MainActivity.this);
                    Pipeline p = new Pipeline(env, log);
                    if (mode == 1) {
                        p.decodeToSmali(new File(path));
                    } else if (mode == 2) {
                        p.decodeToJava(new File(path));
                    } else {
                        p.buildFromSmali(new File(path));
                    }
                } catch (Exception e) {
                    log.line("ОШИБКА: " + e);
                    for (StackTraceElement s : e.getStackTrace())
                        log.line("  at " + s);
                }
            }
        }).start();
    }
}
