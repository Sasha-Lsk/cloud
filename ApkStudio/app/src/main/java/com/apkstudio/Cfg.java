package com.apkstudio;

import android.os.Environment;
import java.io.File;

/** Global paths and configuration. Extract folder == build folder, as requested. */
public final class Cfg {

    /** Base working folder: /storage/emulated/0/Download/ApkStudio/ */
    public static final File ROOT =
            new File(Environment.getExternalStorageDirectory(),
                    "Download/ApkStudio");

    /** Decompiled projects (smali / java) live here. */
    public static final File INPUT = new File(ROOT, "input_apk");

    /** Rebuilt (packed) APKs are written here. */
    public static final File OUTPUT = new File(ROOT, "output_apk");

    /** APKs the user drops in to decompile. */
    public static final File APKS = new File(ROOT, "apks");

    /** Build logs. */
    public static final File LOGS = new File(ROOT, "logs");

    private Cfg() {}

    public static void ensureDirs() {
        for (File f : new File[]{ROOT, INPUT, OUTPUT, APKS, LOGS}) {
            if (!f.exists()) f.mkdirs();
        }
    }
}
