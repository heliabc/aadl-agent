package org.slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 极简 slf4j LoggerFactory 兼容层
 */
public class LoggerFactory {
    private static final Map<String, Logger> cache = new HashMap<>();

    public static Logger getLogger(String name) {
        return cache.computeIfAbsent(name, k -> new JulLogger(k));
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    private static class JulLogger implements Logger {
        private final java.util.logging.Logger delegate;

        JulLogger(String name) {
            this.delegate = java.util.logging.Logger.getLogger(name);
        }

        private String fmt(String format, Object... args) {
            if (args == null || args.length == 0) return format;
            // 简单的 {} 替换
            StringBuilder sb = new StringBuilder();
            int argIdx = 0;
            int i = 0;
            while (i < format.length()) {
                if (i < format.length() - 1 && format.charAt(i) == '{' && format.charAt(i + 1) == '}') {
                    if (argIdx < args.length) {
                        sb.append(String.valueOf(args[argIdx++]));
                    } else {
                        sb.append("{}");
                    }
                    i += 2;
                } else {
                    sb.append(format.charAt(i));
                    i++;
                }
            }
            return sb.toString();
        }

        @Override public void info(String msg) { delegate.info(msg); }
        @Override public void info(String format, Object... args) { delegate.info(fmt(format, args)); }
        @Override public void warn(String msg) { delegate.warning(msg); }
        @Override public void warn(String format, Object... args) { delegate.warning(fmt(format, args)); }
        @Override public void error(String msg) { delegate.severe(msg); }
        @Override public void error(String format, Object... args) { delegate.severe(fmt(format, args)); }
        @Override public void debug(String msg) { delegate.fine(msg); }
        @Override public void debug(String format, Object... args) { delegate.fine(fmt(format, args)); }
    }
}
