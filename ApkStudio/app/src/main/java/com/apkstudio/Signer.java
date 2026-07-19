package com.apkstudio;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Подпись APK официальной библиотекой Google apksig (v1 + v2 + v3).
 *
 * apksig.jar (дексованный) грузится в память через DexClassLoader и
 * вызывается рефлексией, поэтому не требует отдельного процесса.
 *
 * v2/v3 обязательны для targetSdk >= 30, иначе Android 11+ отказывается
 * устанавливать APK. Проверено apksigner verify для API 21..34.
 */
public class Signer {

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /**
     * @param apksigJar дексованный apksig.jar
     * @param optimizedDir writable dir для оптимизированного dex
     * @param minSdk минимальная версия API (обычно minSdkVersion приложения)
     */
    public static void sign(File in, File out, File pk8, File pem,
                            File apksigJar, File optimizedDir, int minSdk) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(readAll(pk8)));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate)
                cf.generateCertificate(new FileInputStream(pem));
        List<X509Certificate> certs = new ArrayList<>();
        certs.add(cert);

        optimizedDir.mkdirs();
        DexClassLoader cl = new DexClassLoader(
                apksigJar.getAbsolutePath(),
                optimizedDir.getAbsolutePath(),
                null,
                Signer.class.getClassLoader());

        Class<?> apkSignerCls   = cl.loadClass("com.android.apksig.ApkSigner");
        Class<?> builderCls     = cl.loadClass("com.android.apksig.ApkSigner$Builder");
        Class<?> signerCfgCls   = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig");
        Class<?> cfgBuilderCls  = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");

        // SignerConfig.Builder("CERT", key, certs).build()
        Constructor<?> cfgCtor = cfgBuilderCls.getConstructor(
                String.class, PrivateKey.class, List.class);
        Object cfgBuilder = cfgCtor.newInstance("CERT", key, certs);
        Object signerConfig = cfgBuilderCls.getMethod("build").invoke(cfgBuilder);

        List<Object> configs = new ArrayList<>();
        configs.add(signerConfig);

        // new ApkSigner.Builder(configs)
        Constructor<?> bCtor = builderCls.getConstructor(List.class);
        Object builder = bCtor.newInstance(configs);

        builderCls.getMethod("setInputApk", File.class).invoke(builder, in);
        builderCls.getMethod("setOutputApk", File.class).invoke(builder, out);
        builderCls.getMethod("setV1SigningEnabled", boolean.class).invoke(builder, true);
        builderCls.getMethod("setV2SigningEnabled", boolean.class).invoke(builder, true);
        builderCls.getMethod("setV3SigningEnabled", boolean.class).invoke(builder, true);
        builderCls.getMethod("setMinSdkVersion", int.class)
                .invoke(builder, Math.max(minSdk, 1));

        Object apkSigner = builderCls.getMethod("build").invoke(builder);
        Method sign = apkSignerCls.getMethod("sign");
        sign.invoke(apkSigner);
    }
}
