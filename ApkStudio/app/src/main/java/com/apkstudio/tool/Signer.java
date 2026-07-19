package com.apkstudio.tool;

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
import java.util.Collections;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Подпись APK через библиотеку apksig (apksig_dex.jar), загружаемую
 * DexClassLoader'ом. Используется публичный API com.android.apksig.ApkSigner.
 *
 * Ключ testkey хранится как pk8 (PKCS#8 DER) + x509 pem. Приватный ключ у
 * AOSP testkey — RSA без пароля.
 */
public class Signer {

    public static void sign(Env env, File in, File out, File pk8, File pemCert)
            throws Exception {

        // Загружаем классы apksig из dex-jar
        File jar = new File(env.engines, "apksig_dex.jar");
        DexClassLoader cl = new DexClassLoader(
                jar.getAbsolutePath(),
                new File(env.work, "dexcache").getAbsolutePath(),
                null,
                Signer.class.getClassLoader());

        // Читаем ключ и сертификат стандартными средствами JDK
        PrivateKey key = loadPk8(pk8);
        X509Certificate cert = loadCert(pemCert);

        Class<?> cSignerConfig = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig");
        Class<?> cSignerConfigBuilder = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Class<?> cApkSigner = cl.loadClass("com.android.apksig.ApkSigner");
        Class<?> cApkSignerBuilder = cl.loadClass("com.android.apksig.ApkSigner$Builder");

        // SignerConfig.Builder(name, privateKey, List<X509Certificate>)
        Constructor<?> scCtor = cSignerConfigBuilder.getConstructor(
                String.class, PrivateKey.class, List.class);
        List<X509Certificate> certs = new ArrayList<>();
        certs.add(cert);
        Object scBuilder = scCtor.newInstance("CERT", key, certs);
        Object signerConfig = cSignerConfigBuilder.getMethod("build").invoke(scBuilder);

        List<Object> configs = new ArrayList<>();
        configs.add(signerConfig);

        // ApkSigner.Builder(List<SignerConfig>)
        Constructor<?> asCtor = cApkSignerBuilder.getConstructor(List.class);
        Object asBuilder = asCtor.newInstance(configs);

        cApkSignerBuilder.getMethod("setInputApk", File.class).invoke(asBuilder, in);
        cApkSignerBuilder.getMethod("setOutputApk", File.class).invoke(asBuilder, out);
        // minSdk нужен apksig, чтобы выбрать корректные схемы подписи
        try {
            cApkSignerBuilder.getMethod("setMinSdkVersion", int.class).invoke(asBuilder, 24);
        } catch (NoSuchMethodException ignore) { }
        // Включаем схемы v1 (jar) и v2, чтобы ставилось на всех Android
        cApkSignerBuilder.getMethod("setV1SigningEnabled", boolean.class).invoke(asBuilder, true);
        cApkSignerBuilder.getMethod("setV2SigningEnabled", boolean.class).invoke(asBuilder, true);
        // apksig сам выровняет файлы (замена zipalign, если его нет)
        try {
            cApkSignerBuilder.getMethod("setAlignFileSize", boolean.class).invoke(asBuilder, true);
        } catch (NoSuchMethodException ignore) { }

        Object apkSigner = cApkSignerBuilder.getMethod("build").invoke(asBuilder);
        Method signMethod = cApkSigner.getMethod("sign");
        signMethod.invoke(apkSigner);
    }

    private static PrivateKey loadPk8(File pk8) throws Exception {
        byte[] der = readAll(pk8);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    private static X509Certificate loadCert(File pem) throws Exception {
        try (FileInputStream in = new FileInputStream(pem)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0)
                off += n;
        }
        return buf;
    }
}
