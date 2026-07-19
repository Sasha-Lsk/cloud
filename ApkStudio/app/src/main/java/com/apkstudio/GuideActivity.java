package com.apkstudio;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** In-app instructions: correct file/folder layout for successful builds. */
public class GuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_guide);
        TextView tv = findViewById(R.id.guide_text);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setText(GUIDE);
    }

    private static final String GUIDE =
        "ИНСТРУКЦИЯ ПО РАСКЛАДКЕ ФАЙЛОВ\n" +
        "(мировой стандарт apktool)\n\n" +

        "Все папки находятся в:\n" +
        "/storage/emulated/0/Download/ApkStudio/\n\n" +

        "1) apks/\n" +
        "   Сюда кладите исходный .apk, который хотите разобрать.\n\n" +

        "2) input_apk/  — сюда идёт результат распаковки.\n" +
        "   • <имя>/       — smali + ресурсы (для сборки обратно)\n" +
        "   • <имя>_java/  — java-исходники (только для чтения)\n\n" +

        "3) output_apk/\n" +
        "   Сюда падает готовый, подписанный и выровненный .apk.\n\n" +

        "4) logs/  — журналы сборки.\n\n" +

        "СТРУКТУРА SMALI-ПРОЕКТА (для сборки):\n" +
        "input_apk/MyApp/\n" +
        "  ├─ AndroidManifest.xml   (текстовый, редактируемый)\n" +
        "  ├─ apktool.yml           (НЕ УДАЛЯТЬ! версии SDK и т.д.)\n" +
        "  ├─ smali/                (код: smali/ classes)\n" +
        "  ├─ smali_classes2/       (если было несколько dex)\n" +
        "  ├─ res/                  (layout, drawable, values...)\n" +
        "  ├─ assets/               (как есть)\n" +
        "  ├─ lib/                  (нативные .so по ABI)\n" +
        "  └─ original/, unknown/   (мета — НЕ ТРОГАТЬ)\n\n" +

        "ПОРЯДОК РАБОТЫ:\n" +
        "1. Положите APK в apks/.\n" +
        "2. Нажмите «В SMALI» — правка кода в smali/.\n" +
        "   (или «В JAVA» — чтобы посмотреть логику в java).\n" +
        "3. Отредактируйте нужные .smali / res / xml.\n" +
        "4. Нажмите «СОБРАТЬ APK».\n" +
        "5. Установите файл из output_apk/.\n\n" +

        "ВАЖНО про старые приложения\n" +
        "(окно «разработано для более старой версии»):\n" +
        "Это НЕ ошибка сборки, а системное предупреждение,\n" +
        "когда targetSdkVersion слишком низкий.\n" +
        "ApkStudio при сборке автоматически поднимает\n" +
        "targetSdkVersion до 30 — приложение перестаёт\n" +
        "показывать это окно и нормально запускается на\n" +
        "Android 8, 9, 10, 11, 12.\n" +
        "minSdkVersion при этом НЕ меняется, поэтому\n" +
        "приложение остаётся совместимым со старой логикой.\n\n" +

        "ЧАСТЫЕ ОШИБКИ СБОРКИ:\n" +
        "• Удалён apktool.yml → сборка невозможна. Не удаляйте.\n" +
        "• Неверный синтаксис smali → apktool покажет строку.\n" +
        "• Изменены public.xml / ID ресурсов вручную → ошибки\n" +
        "  aapt. Меняйте значения, а не идентификаторы.\n" +
        "• Битый .png в res/ → замените корректным.\n\n" +

        "ПОДПИСЬ:\n" +
        "APK подписывается встроенным ключом схемами\n" +
        "v1 + v2 + v3 (официальная библиотека apksig).\n" +
        "Это обязательно для targetSdk 30+, иначе Android 11+\n" +
        "откажется устанавливать приложение.\n" +
        "Для публикации в Play используйте свой релизный ключ.\n";
}
