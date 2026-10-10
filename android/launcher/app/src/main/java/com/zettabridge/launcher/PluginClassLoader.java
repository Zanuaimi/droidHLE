package com.zettabridge.launcher;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.IOException;

/** Child-first plugin dex loader with explicit launcher bridge and native-library routing. */
final class PluginClassLoader extends DexClassLoader {
    private static final String BRIDGE_PREFIX = "com.zettabridge.core.";

    private final PluginRecord record;
    private final File proxyTemplate;
    private final ClassLoader bridgeLoader;
    private final String hostPackage;

    PluginClassLoader(PluginRecord record, File codeCache, File proxyTemplate, ClassLoader bootLoader,
            ClassLoader bridgeLoader, String hostPackage) {
        super(dexPath(record), codeCache.getPath(), record.isTranslated() ? null : record.libDir().getPath(),
                bootLoader);
        this.record = record;
        this.proxyTemplate = proxyTemplate;
        this.bridgeLoader = bridgeLoader;
        this.hostPackage = hostPackage;
    }

    // Android puts the Apache HTTP client on the class path of apps targeting below API 28;
    // old games (and their Flurry/ad SDKs) still call it.
    private static final File APACHE_HTTP = new File("/system/framework/org.apache.http.legacy.jar");

    private static String dexPath(PluginRecord record) {
        String apk = record.apk().getPath();
        return record.targetSdk < 28 && APACHE_HTTP.isFile() ? apk + File.pathSeparator + APACHE_HTTP : apk;
    }

    static boolean delegateToBridge(String name) {
        return name != null && name.startsWith(BRIDGE_PREFIX);
    }

    private static boolean platformClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("dalvik.") || name.startsWith("sun.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (delegateToBridge(name)) {
                    loaded = bridgeLoader.loadClass(name);
                } else if (platformClass(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missing) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    public String findLibrary(String name) {
        try {
            File library = resolve(name);
            // getPackageName() is the launcher's, so an app that names its library after its own
            // package (Marmalade loads lib<last package segment>.so) asks for the launcher's
            // segment instead. Give it the library its own package name points at.
            String own = lastSegment(record.packageName);
            if (library == null && name.equals(lastSegment(hostPackage))
                    && !name.equals(own)) {
                library = resolve(own);
            }
            return library != null ? library.getPath() : null;
        } catch (IOException e) {
            return null;  // ART reports the normal UnsatisfiedLinkError; Diagnostics adds the bridge detail.
        }
    }

    private File resolve(String name) throws IOException {
        return PluginFiles.resolveLibrary(record.libDir(), record.proxyDir(), proxyTemplate, record.isTranslated(),
                name);
    }

    private static String lastSegment(String packageName) {
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }
}
