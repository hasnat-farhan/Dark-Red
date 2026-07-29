package com.antor.f2p.engine.api;

/**
 * Log levels for the Wandering Fibre Engine's async logger.
 * <p>
 * Levels can be changed at runtime via {@code F2PBridge.setLogLevel(LogLevel)}
 * without restarting the engine. Each level includes all levels above it
 * (e.g. WARN includes ERROR).
 * </p>
 */
public enum LogLevel {
    TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);

    final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    /**
     * Returns {@code true} if this level is at least as severe as the
     * given threshold.
     */
    public boolean isEnabled(LogLevel threshold) {
        return this.priority >= threshold.priority;
    }
}
