package org.slf4j;

/**
 * 极简 slf4j Logger 兼容层（仅用于 plugin 版本避免依赖）
 * 实际委托给 java.util.logging
 */
public interface Logger {
    void info(String msg);
    void info(String format, Object... args);
    void warn(String msg);
    void warn(String format, Object... args);
    void error(String msg);
    void error(String format, Object... args);
    void debug(String msg);
    void debug(String format, Object... args);
}
