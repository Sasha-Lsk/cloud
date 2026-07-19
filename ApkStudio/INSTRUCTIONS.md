# APK Studio — инструкция (движки уже вложены)

Java-приложение для телефона: декомпиляция APK в **smali/xml** и в **Java**
(для чтения), обратная пересборка **smali → APK** с выравниванием и подписью.
Собирается в AIDE, без ПК.

**Все движки уже встроены и проверены** (реальный round-trip dex→smali→dex
и jadx→Java выполнены успешно). Ничего докачивать не нужно.

---

## 0. Java vs smali — что для чего

- **APK → Java (jadx)** — только для ЧТЕНИЯ. Такой код почти никогда не
  компилируется обратно. Используйте, чтобы ПОНЯТЬ логику и найти место правки.
- **APK → smali → APK** — единственный НАДЁЖНЫЙ путь модификации.
  smali = 1:1 байткод, собирается всегда.

Схема: смотрю Java → нахожу место → правлю в smali → собираю.

---

## 1. Какие движки внутри (assets/engines/) — уже готовы

| Файл | Что делает | main-класс |
|------|-----------|-----------|
| `baksmali_dex.jar` | DEX → smali | `org.jf.baksmali.Main` |
| `smali_dex.jar` | smali → DEX | `org.jf.smali.Main` |
| `jadx_dex.jar` | APK → Java (чтение) | `jadx.cli.JadxCLI` |
| `apksig_dex.jar` | подпись APK (v1+v2) | `com.android.apksig.ApkSigner` (API) |
| `r8_dex.jar` | классы/.jar → DEX (d8) | `com.android.tools.r8.D8` |
| `testkey.pk8`, `testkey.x509.pem` | ключ подписи | — |

Версии: smali/baksmali 2.5.2, jadx 1.5.6, apksig 9.3.0, r8 8.13.19.
Все jar заранее переведены в формат **DEX** (через d8), чтобы их можно было
загрузить на Android через `DexClassLoader`. Это ключевой момент: обычный
ПК-jar на Android не грузится.

Нативные бинарники (assets/bin/<abi>/): `aapt` (все ABI) и `zipalign`
(armeabi-v7a). Если zipalign под ваш ABI нет — выравнивание делает сам apksig
(`setAlignFileSize`), поэтому APK всё равно корректный.

---

## 2. Структура проекта (мировой стандарт Gradle)

```
ApkStudio/                      ← открывать в AIDE ЭТУ папку
├─ settings.gradle
├─ build.gradle                 ← корневой
└─ app/
   ├─ build.gradle              ← модуль (noCompress 'jar' обязателен!)
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/apkstudio/tool/*.java   ← путь = package
      ├─ res/layout, res/values
      └─ assets/
         ├─ bin/<abi>/aapt, zipalign      ← нативные (вложены)
         └─ engines/*.jar, testkey.*      ← движки (вложены)
```

Правила, которые нельзя нарушать:
1. Путь в `java/` = package (`com.apkstudio.tool` → java/com/apkstudio/tool/).
2. Имя класса = имя файла.
3. Ресурсы только в `res/`, manifest в `src/main/`.
4. `assets/` НЕ сжимать (`aaptOptions { noCompress 'jar','dex' }`) — иначе
   извлечённый на телефоне jar/dex будет битым.

---

## 3. Сборка самого приложения в AIDE

1. Скопируйте папку `ApkStudio` в память (напр. `/sdcard/ApkStudio`).
2. Откройте в AIDE **корневую папку `ApkStudio`** (Open folder).
3. AIDE увидит Gradle-проект → **Run / Build APK**.
4. Установите собранный `ApkStudio.apk`, дайте доступ к файлам.

---

## 4. Как пользоваться

ВСЕ результаты сохраняются в ПУБЛИЧНУЮ папку, видимую любым файловым
менеджером (без root):
    /storage/emulated/0/Download/ApkStudio/
При первом запуске приложение попросит «Доступ ко всем файлам» — разрешите,
иначе запись в Download невозможна (Android 11+).

### APK → smali (для правки)
1. Кнопка **Выбрать APK** (или введите путь).
2. Кнопка **APK → smali**.
3. Результат: `/storage/emulated/0/Download/ApkStudio/target_smali/`:
   `res_raw/` (ресурсы+manifest+dex), `smali/`, `smali_classes2/` …
4. Правьте `.smali` файлы прямо там.

### APK → Java (для чтения)
1. Выбрать APK → **APK → Java**.
2. Результат `/storage/emulated/0/Download/ApkStudio/target_java/` — читаемый
   Java (собрать нельзя, только чтение).

### smali → APK (пересборка)
1. В поле пути укажите ПАПКУ проекта `target_smali`.
2. Кнопка **smali → APK**.
3. Внутри строго: smali→classes*.dex → zip(без META-INF) → zipalign → подпись.
4. Результат `/storage/emulated/0/Download/ApkStudio/built_signed.apk`.

### Выбрать APK / Поделиться
- Кнопка **Выбрать APK** — системный диалог (SAF), APK копируется в рабочую
  папку как input.apk, путь подставляется автоматически.
- Кнопка **Открыть / поделиться готовым APK** — шлёт built_signed.apk через
  системный «Поделиться» (можно сразу открыть установщик).

### jar (классы) → APK  (Java → DEX → APK)
Важно про отличие от AIDE:
- **AIDE** компилирует ВАШ Java-исходник (javac) и собирает НОВЫЙ APK с нуля.
- На Android **нет javac**, поэтому это приложение не компилирует .java из
  исходников. Движок d8 (внутри r8_dex.jar) умеет только .class/.jar → DEX.
- Поэтому режим принимает УЖЕ скомпилированные классы (jar/.class — например
  собранные в AIDE) и кладёт их в выбранный базовый APK.

Как пользоваться:
1. Сначала **Выбрать APK** — это будет базовая оболочка ресурсов.
2. Кнопка **jar (классы) → APK**, выберите jar со скомпилированными .class.
3. Внутри: d8 (классы→dex) → замена classes.dex в APK → подпись.
4. Результат `.../work/built_signed.apk`.

Итого: для правки чужого APK — путь smali. Для добавления своего кода —
скомпилируйте его в AIDE в .class/jar и используйте этот режим.

---

## 5. Правильный ПОРЯДОК (почему без него ошибки)

Декомпиляция:
```
APK --(unzip)--> res_raw/ + classes*.dex
classes*.dex --(baksmali)--> smali/, smali_classes2/ ...
```

Пересборка (строго так):
```
smali/ --(smali assemble)--> classes.dex (+ classesN.dex)
res_raw/ + новые dex --(zip, без старого META-INF)--> unsigned.apk
unsigned.apk --(zipalign 4)--> aligned.apk
aligned.apk --(apksig v1+v2, testkey)--> signed.apk
```

Типичные ошибки:
- `INSTALL_PARSE_FAILED_NO_CERTIFICATES` → не удалён старый META-INF
  (приложение удаляет его автоматически).
- `INSTALL_FAILED_INVALID_APK` / not aligned → выравнивание;
  apksig делает его сам, если zipalign недоступен.
- Java не компилируется → это jadx-Java (только чтение). Правьте smali.
- `ClassNotFoundException` при запуске движка → jar сжат в assets
  (проверьте `noCompress` в app/build.gradle).

---

## 6. Как были подготовлены движки (для истории)

Скачаны официальные сборки (smali/baksmali с bitbucket JesusFreke,
jadx с github skylot, apksig/r8 с maven.google) и один раз переведены в
DEX командой:
```
java -cp r8.jar com.android.tools.r8.D8 --min-api 24 \
     --lib android.jar --output NAME_dex.jar NAME.jar
```
jadx перед конвертацией очищен от вложенных .dex (bundletool), т.к. d8 не
принимает архив со смешанным содержимым class+dex.
