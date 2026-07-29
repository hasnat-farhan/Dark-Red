package com.antor.sosblue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Represents a nearby peer device discovered over the SOSBlue Mesh.
 * <p>
 * Now includes the peer's IP endpoint so that ACK packets and direct
 * messages can be sent to a known IP:port rather than relying solely
 * on subnet broadcasts.</p>
 */
public class PeerDevice {

    private final String id;
    private final String name;
    private final int signalStrength;   // 0–4 bars
    private boolean isConnected;

    /** Peer's last-known IP address (dotted-decimal). Null if unknown. */
    private String ipAddress;
    /** Peer's UDP port. Defaults to 41234 if unknown. */
    private int port;
    /** Timestamp of the last heartbeat from this peer. */
    private volatile long lastSeenMs;

    public PeerDevice(@NonNull String id, @NonNull String name,
                      int signalStrength, boolean isConnected) {
        this(id, name, signalStrength, isConnected, null, 41234);
    }

    public PeerDevice(@NonNull String id, @NonNull String name,
                      int signalStrength, boolean isConnected,
                      @Nullable String ipAddress, int port) {
        this.id = id;
        this.name = name;
        this.signalStrength = Math.max(0, Math.min(4, signalStrength));
        this.isConnected = isConnected;
        this.ipAddress = ipAddress;
        this.port = port > 0 ? port : 41234;
        this.lastSeenMs = System.currentTimeMillis();
    }

    @NonNull
    public String getId()               { return id; }
    @NonNull
    public String getName()             { return name; }
    public int getSignalStrength()      { return signalStrength; }
    public boolean isConnected()        { return isConnected; }

    public void setConnected(boolean connected) { isConnected = connected; }

    // ---------------------------------------------------------------
    //  IP endpoint accessors
    // ---------------------------------------------------------------

    @Nullable
    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(@Nullable String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the endpoint string in the form {@code "ip:port"},
     * or just {@code "ip"} if port is the default.
     */
    @Nullable
    public String getEndpoint() {
        if (ipAddress == null) return null;
        return port != 41234 ? ipAddress + ":" + port : ipAddress;
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    public void setLastSeenMs(long lastSeenMs) {
        this.lastSeenMs = lastSeenMs;
    }

    /**
     * Updates the IP endpoint and seens timestamp from a received packet.
     */
    public void updateEndpoint(@NonNull String ipAddress, int port) {
        this.ipAddress = ipAddress;
        this.port = port;
        this.lastSeenMs = System.currentTimeMillis();
    }

    @NonNull
    @Override
    public String toString() {
        return name + " (" + id.substring(0, Math.min(8, id.length())) + "...)"
                + (ipAddress != null ? " @ " + ipAddress + ":" + port : "");
    }
}
