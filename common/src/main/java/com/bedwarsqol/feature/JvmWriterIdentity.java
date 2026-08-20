package com.bedwarsqol.feature;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

/**
 * Immutable JVM writer identity for {@link LobbyExport}. Captured once per JVM from
 * operational process metadata (PID and start time). Never exposes argv.
 */
public final class JvmWriterIdentity {

    /** Upper bound for a safe JavaScript integer (2^53 - 1). */
    static final long MAX_SAFE_JS_INTEGER = 9007199254740991L;

    /** Maximum supported OS PID on common platforms. */
    static final long MAX_PID = 4_194_304L;

    public final long pid;
    public final long startTimeMs;

    private JvmWriterIdentity(long pid, long startTimeMs) {
        this.pid = pid;
        this.startTimeMs = startTimeMs;
    }

    /**
     * Obtain the current JVM's writer identity, or null when PID/start time cannot be
     * obtained or validated.
     */
    public static JvmWriterIdentity current() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        long start = bean.getStartTime();
        Long pid = pidOf(bean);
        if (pid == null || !isValid(pid, start)) return null;
        return new JvmWriterIdentity(pid, start);
    }

    /**
     * Parse the numeric prefix of {@code RuntimeMXBean.getName()} ({@code pid@host} on Java 8).
     */
    static Long parsePidFromRuntimeName(String name) {
        if (name == null || name.isEmpty()) return null;
        int at = name.indexOf('@');
        String prefix = at >= 0 ? name.substring(0, at) : name;
        if (prefix.isEmpty()) return null;
        try {
            long value = Long.parseLong(prefix);
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reflective {@code ProcessHandle.current().pid()} when available; falls back to runtime name.
     */
    static Long pidOf(RuntimeMXBean bean) {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object current = handleClass.getMethod("current").invoke(null);
            Object pidObj = handleClass.getMethod("pid").invoke(current);
            if (pidObj instanceof Long) {
                return (Long) pidObj;
            }
            if (pidObj instanceof Number) {
                return ((Number) pidObj).longValue();
            }
        } catch (Throwable ignored) {
            // Java 8 or reflective failure — fall through to runtime name.
        }
        return parsePidFromRuntimeName(bean.getName());
    }

    static boolean isValid(long pid, long startTimeMs) {
        return pid > 0
                && pid <= MAX_PID
                && startTimeMs > 0
                && startTimeMs <= MAX_SAFE_JS_INTEGER;
    }

    /** Same-package test helper; not for production use. */
    static JvmWriterIdentity forTest(long pid, long startTimeMs) {
        if (!isValid(pid, startTimeMs)) return null;
        return new JvmWriterIdentity(pid, startTimeMs);
    }

    /**
     * Safe temp basename segment: digits and separators only, e.g. {@code lobby.1234.567890.tmp}.
     */
    public String tempFileName() {
        return "lobby." + pid + "." + startTimeMs + ".tmp";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JvmWriterIdentity)) return false;
        JvmWriterIdentity other = (JvmWriterIdentity) o;
        return pid == other.pid && startTimeMs == other.startTimeMs;
    }

    @Override
    public int hashCode() {
        int h = (int) (pid ^ (pid >>> 32));
        h = 31 * h + (int) (startTimeMs ^ (startTimeMs >>> 32));
        return h;
    }
}
