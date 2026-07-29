package com.antor.sosblue;

import androidx.annotation.NonNull;

/**
 * Represents a nearby peer device discovered over the SOSBlue Mesh.
 */
public class PeerDevice {

    private final String id;
    private final String name;
    private final int signalStrength;   // 0–4 bars
    private boolean isConnected;

    public PeerDevice(@NonNull String id, @NonNull String name,
                      int signalStrength, boolean isConnected) {
        this.id = id;
        this.name = name;
        this.signalStrength = Math.max(0, Math.min(4, signalStrength));
        this.isConnected = isConnected;
    }

    @NonNull
    public String getId()               { return id; }
    @NonNull
    public String getName()             { return name; }
    public int getSignalStrength()      { return signalStrength; }
    public boolean isConnected()        { return isConnected; }

    public void setConnected(boolean connected) { isConnected = connected; }

    @NonNull
    @Override
    public String toString() {
        return name + " (" + id.substring(0, Math.min(8, id.length())) + "...)";
    }
}
