package com.apkstudio.tool;

import java.io.File;
import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;

/**
 * Загружает jar-движок через DexClassLoader и вызывает его main(String[]).
 *
 * ВАЖНО: на Android нельзя выполнить обычный .jar как на ПК (нет java -jar).
 * Движки должны быть в формате DEX (dex-jar). smali/baksmali/d8/jadx
 * распространяются либо как dex-совместимые, либо их надо один раз
 * пропустить через d8. Подробности — в INSTRUCTIONS.
 */
public class Engine {

    private final File jar;
    private final File dexCache;
    private final ClassLoader parent;

    public Engine(File jar, File dexCache) {
        this.jar = jar;
        this.dexCache = dexCache;
        this.parent = Engine.class.getClassLoader();
        dexCache.mkdirs();
    }

    /** Вызывает статический main указанного класса из jar с аргументами. */
    public void runMain(String mainClass, String[] args) throws Exception {
        if (!jar.exists()) {
            throw new IllegalStateException("Движок не найден: " + jar.getName()
                    + " — положите его в assets/engines (см. инструкцию)");
        }
        DexClassLoader cl = new DexClassLoader(
                jar.getAbsolutePath(),
                dexCache.getAbsolutePath(),
                null,
                parent);
        Class<?> c = cl.loadClass(mainClass);
        Method m = c.getMethod("main", String[].class);
        m.invoke(null, (Object) args);
    }
}
