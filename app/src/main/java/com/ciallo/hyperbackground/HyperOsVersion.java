package com.ciallo.hyperbackground;

/**
 * 运行时判断 HyperOS 大版本。顶栏渐进模糊是 OS3（及更早架构）专属功能，
 * OS4 上不再需要，且其每帧模糊回调会造成设置主页滚动严重掉帧，故用此判断做版本 gate。
 *
 * <p>读取系统属性 {@code ro.mi.os.version.name}（形如 "OS2.0.5.0"、"OS3.0.1.0"），
 * 解析首个数字段作为大版本号。App 进程与被注入的系统进程均可调用（各自读本进程可见的属性）。
 */
public final class HyperOsVersion {

    private static volatile Integer cachedMajor;

    private HyperOsVersion() {}

    /** HyperOS 大版本号；无法解析时返回 -1（按“未知”处理，不启用 OS3 专属逻辑）。 */
    public static int major() {
        Integer value = cachedMajor;
        if (value != null) return value;
        int parsed = parseMajor(readProp("ro.mi.os.version.name"));
        cachedMajor = parsed;
        return parsed;
    }

    /** 是否为 OS3 及更早（含无法识别为 OS4+ 的旧系统），即需要启用顶栏模糊的场景。 */
    public static boolean isOs3OrEarlier() {
        int m = major();
        // 已明确识别到 OS4 及以上 → false；OS3、OS2、OS1 或无法解析 → true（保守保留旧行为）。
        return m < 4;
    }

    private static int parseMajor(String name) {
        if (name == null) return -1;
        // 形如 "OS3.0.1.0"：跳过前导非数字，取第一段连续数字。
        int i = 0, n = name.length();
        while (i < n && !Character.isDigit(name.charAt(i))) i++;
        int start = i;
        while (i < n && Character.isDigit(name.charAt(i))) i++;
        if (start == i) return -1;
        try {
            return Integer.parseInt(name.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
