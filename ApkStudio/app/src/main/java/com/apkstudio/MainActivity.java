package com.apkstudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements Log {

    private TextView console;
    private Spinner apkSpinner, projSpinner;
    private Engine engine;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        console = findViewById(R.id.console);
        console.setMovementMethod(new ScrollingMovementMethod());
        apkSpinner = findViewById(R.id.spinner_apk);
        projSpinner = findViewById(R.id.spinner_proj);

        engine = new Engine(this, this);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshLists());
        findViewById(R.id.btn_guide).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));

        findViewById(R.id.btn_smali).setOnClickListener(v ->
                task(() -> engine.decompileSmali(selectedApk()), "Декомпиляция в smali"));

        findViewById(R.id.btn_java).setOnClickListener(v ->
                task(() -> engine.decompileJava(selectedApk()), "Декомпиляция в java"));

        ((MaterialButton) findViewById(R.id.btn_build)).setOnClickListener(v ->
                task(() -> engine.build(selectedProj(), 30), "Сборка APK"));

        requestPerms();
        Cfg.ensureDirs();
        line("ApkStudio готов.");
        line("Рабочая папка: " + Cfg.ROOT.getAbsolutePath());
        line("• APK для распаковки кладите в: apks/");
        line("• Проекты (smali/java): input_apk/");
        line("• Готовые APK: output_apk/");
        refreshLists();
    }

    // ---- permissions ----
    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ---- list files ----
    private void refreshLists() {
        Cfg.ensureDirs();
        fill(apkSpinner, listByExt(Cfg.APKS, ".apk"));
        fill(projSpinner, listDirs(Cfg.INPUT));
    }

    private void fill(Spinner sp, List<String> items) {
        if (items.isEmpty()) items.add("(пусто)");
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        sp.setAdapter(a);
    }

    private List<String> listByExt(File dir, String ext) {
        List<String> r = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs)
            if (f.isFile() && f.getName().toLowerCase().endsWith(ext)) r.add(f.getName());
        return r;
    }
    private List<String> listDirs(File dir) {
        List<String> r = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) r.add(f.getName());
        return r;
    }

    private File selectedApk() throws Exception {
        Object s = apkSpinner.getSelectedItem();
        if (s == null || s.toString().startsWith("(")) throw new Exception("Выберите APK в папке apks/");
        return new File(Cfg.APKS, s.toString());
    }
    private File selectedProj() throws Exception {
        Object s = projSpinner.getSelectedItem();
        if (s == null || s.toString().startsWith("(")) throw new Exception("Выберите проект в input_apk/");
        return new File(Cfg.INPUT, s.toString());
    }

    // ---- task runner ----
    interface Job { File run() throws Exception; }

    private void task(Job job, String title) {
        line("\n=== " + title + " ===");
        new Thread(() -> {
            try {
                File r = job.run();
                runOnUiThread(() -> {
                    line("✓ Успех: " + (r != null ? r.getAbsolutePath() : ""));
                    Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show();
                    refreshLists();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    line("✗ ОШИБКА: " + e.getMessage());
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ---- Log ----
    @Override
    public void line(String s) {
        runOnUiThread(() -> {
            console.append(s + "\n");
            final int amount = console.getLayout() != null
                    ? console.getLayout().getLineTop(console.getLineCount()) - console.getHeight()
                    : 0;
            if (amount > 0) console.scrollTo(0, amount);
        });
    }
}
