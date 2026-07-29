package com.antor.f2p.engine.api;

/**
 * Configuration bundle for the Wandering Fibre Engine.
 * <p>
 * Pass an instance to {@code F2PBridge.startEngine(EngineConfig)} to
 * override all defaults before the engine initialises.
 * </p>
 */
public final class EngineConfig {

    private final String nodeId;
    private final LogLevel logLevel;
    private final int heartbeatIntervalMs;
    private final int peersRequired;
    private final int maxHeartbeatCycles;
    private final int discoveryPort;
    private final long linkTtlMs;
    private final int maxRetryAttempts;
    private final long baseBackoffMs;

    private EngineConfig(Builder b) {
        this.nodeId = b.nodeId;
        this.logLevel = b.logLevel;
        this.heartbeatIntervalMs = b.heartbeatIntervalMs;
        this.peersRequired = b.peersRequired;
        this.maxHeartbeatCycles = b.maxHeartbeatCycles;
        this.discoveryPort = b.discoveryPort;
        this.linkTtlMs = b.linkTtlMs;
        this.maxRetryAttempts = b.maxRetryAttempts;
        this.baseBackoffMs = b.baseBackoffMs;
    }

    public String getNodeId()                     { return nodeId; }
    public LogLevel getLogLevel()                 { return logLevel; }
    public int getHeartbeatIntervalMs()           { return heartbeatIntervalMs; }
    public int getPeersRequired()                 { return peersRequired; }
    public int getMaxHeartbeatCycles()            { return maxHeartbeatCycles; }
    public int getDiscoveryPort()                 { return discoveryPort; }
    public long getLinkTtlMs()                    { return linkTtlMs; }
    public int getMaxRetryAttempts()              { return maxRetryAttempts; }
    public long getBaseBackoffMs()                { return baseBackoffMs; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String nodeId = "sosblue-node";
        private LogLevel logLevel = LogLevel.INFO;
        private int heartbeatIntervalMs = 500;
        private int peersRequired = 3;
        private int maxHeartbeatCycles = 5;
        private int discoveryPort = 41234;
        private long linkTtlMs = 10_000;
        private int maxRetryAttempts = 5;
        private long baseBackoffMs = 200;

        public Builder nodeId(String v)                    { this.nodeId = v; return this; }
        public Builder logLevel(LogLevel v)                { this.logLevel = v; return this; }
        public Builder heartbeatIntervalMs(int v)          { this.heartbeatIntervalMs = v; return this; }
        public Builder peersRequired(int v)                { this.peersRequired = v; return this; }
        public Builder maxHeartbeatCycles(int v)           { this.maxHeartbeatCycles = v; return this; }
        public Builder discoveryPort(int v)                { this.discoveryPort = v; return this; }
        public Builder linkTtlMs(long v)                   { this.linkTtlMs = v; return this; }
        public Builder maxRetryAttempts(int v)              { this.maxRetryAttempts = v; return this; }
        public Builder baseBackoffMs(long v)                { this.baseBackoffMs = v; return this; }

        public EngineConfig build() { return new EngineConfig(this); }
    }
}
