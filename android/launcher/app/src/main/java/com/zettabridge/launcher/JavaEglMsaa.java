package com.zettabridge.launcher;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGL;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;

/**
 * MSAA for games that choose their EGL config in Java (Marmalade, GLSurfaceView): the shared
 * EGL10 that EGLContext.getEGL() hands out is replaced by a proxy whose eglChooseConfig asks for
 * multisampled window configs, falling back to the game's own request. Native EGL goes through
 * egl_manual.cpp (ZB_GFX_MSAA) instead.
 */
final class JavaEglMsaa {
    private static final String TAG = "zb-launcher";
    private static final int EGL_SAMPLE_BUFFERS = 0x3032;
    private static final int EGL_SAMPLES = 0x3031;
    private static final int EGL_SURFACE_TYPE = 0x3033;
    private static final int EGL_WINDOW_BIT = 0x4;
    private static final int EGL_NONE = 0x3038;

    private JavaEglMsaa() {}

    static void install(int samples) {
        try {
            Field field = EGLContext.class.getDeclaredField("EGL_INSTANCE");
            field.setAccessible(true);
            EGL real = (EGL) field.get(null);
            if (Proxy.isProxyClass(real.getClass())) return;
            Object proxy = Proxy.newProxyInstance(EGL10.class.getClassLoader(), real.getClass().getInterfaces(),
                    (self, method, args) -> {
                        if (method.getName().equals("eglChooseConfig") && args != null && args.length == 5) {
                            int[] wanted = withMsaa((int[]) args[1], samples);
                            if (wanted != null) {
                                Object[] tried = args.clone();
                                tried[1] = wanted;
                                Object ok = invoke(method, real, tried);
                                int[] count = (int[]) args[4];
                                boolean got = Boolean.TRUE.equals(ok) && count != null && count[0] > 0;
                                Log.i(TAG, (got ? "MSAA: multisampled config for " : "MSAA: none matches, as asked: ")
                                        + Arrays.toString((int[]) args[1]));
                                if (got) return ok;
                            }
                        }
                        return invoke(method, real, args);
                    });
            field.set(null, proxy);
            Log.i(TAG, "MSAA: " + samples + "x for Java EGL configs");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot enable MSAA for Java EGL: " + e);
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The game's attribute list with samples added, or null to leave it alone. */
    static int[] withMsaa(int[] attribs, int samples) {
        int[] out = new int[(attribs != null ? attribs.length : 0) + 5];
        int n = 0;
        for (int i = 0; attribs != null && i + 1 < attribs.length && attribs[i] != EGL_NONE; i += 2) {
            int name = attribs[i];
            int value = attribs[i + 1];
            if ((name == EGL_SAMPLES || name == EGL_SAMPLE_BUFFERS) && value > 0) return null;
            if (name == EGL_SURFACE_TYPE && (value & EGL_WINDOW_BIT) == 0) return null;
            if (name == EGL_SAMPLES || name == EGL_SAMPLE_BUFFERS) continue;
            out[n++] = name;
            out[n++] = value;
        }
        out[n++] = EGL_SAMPLE_BUFFERS;
        out[n++] = 1;
        out[n++] = EGL_SAMPLES;
        out[n++] = samples;
        out[n++] = EGL_NONE;
        return Arrays.copyOf(out, n);
    }
}
