package com.github.tvbox.osc.bbox.util;

/**
 * m3u8 去广告过滤的原生（JNI/C）桥接。
 *
 * P1 阶段交付：原生核心（app/src/main/jni/m3u8_core.c）已完成并单测通过，
 * 本类提供 JNI 入口。默认不启用（useNative() 返回 false），
 * 需在真机回归（对比 Java 版去广告结果一致）后再放开，避免影响播放行为。
 */
public class M3u8Native {

    private static volatile boolean loaded = false;

    static {
        try {
            System.loadLibrary("m3u8native");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false; // 未打进 native 库时静默回退 Java
        }
    }

    /** native 库是否加载成功。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 是否启用原生路径。P1 阶段默认 false，真机回归通过后置为 true。 */
    public static boolean useNative() {
        return false;
    }

    private static native String nativeRemoveMinority(String content, String tsUrlPre, int[] adCount);

    /**
     * 调用原生过滤。返回 null 表示"无法处理 / 未启用 / 加载失败"，调用方应回退到 Java。
     *
     * @param adCount 长度为 1 的 int 数组，返回时回填被移除的切块数
     * @return 过滤后的完整 m3u8 内容，或 null
     */
    public static String removeMinority(String content, String tsUrlPre, int[] adCount) {
        if (!loaded || !useNative()) return null;
        try {
            if (adCount == null) adCount = new int[1];
            return nativeRemoveMinority(content, tsUrlPre, adCount);
        } catch (Throwable t) {
            return null;
        }
    }
}