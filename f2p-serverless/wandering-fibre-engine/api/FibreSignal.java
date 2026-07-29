package com.antor.f2p.engine.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable signal payload that flows through the Wandering Fibre Engine.
 * Each signal carries a unique identifier, a timestamp, a type tag, and
 * an optional key-value payload map.
 */
public final class FibreSignal {

    private final String id;
    private final long timestamp;
    private final String type;
    private final Map<String, Object> payload;

    /**
     * Constructs a new FibreSignal.
     *
     * @param type    a semantic type tag (e.g. "ping", "data", "control")
     * @param payload key-value data; null is treated as an empty map
     */
    public FibreSignal(String type, Map<String, Object> payload) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.payload = payload != null
                ? Collections.unmodifiableMap(new HashMap<>(payload))
                : Collections.emptyMap();
    }

    /** Unique signal identifier. */
    public String getId() {
        return id;
    }

    /** Epoch millis when this signal was created. */
    public long getTimestamp() {
        return timestamp;
    }

    /** Semantic type tag. */
    public String getType() {
        return type;
    }

    /** Immutable key-value payload. */
    public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FibreSignal)) return false;
        FibreSignal that = (FibreSignal) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FibreSignal{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
