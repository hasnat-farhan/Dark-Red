package com.antor.sosblue;

import androidx.annotation.NonNull;

/**
 * In-memory representation of a single chat message.
 */
public class MessageModel {

    private final long id;
    private final String text;
    private final long timestamp;
    private final boolean isSent;

    private static long nextId = 0;

    public MessageModel(@NonNull String text, boolean isSent) {
        this.id = nextId++;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
        this.isSent = isSent;
    }

    public long getId()             { return id; }
    @NonNull
    public String getText()         { return text; }
    public long getTimestamp()      { return timestamp; }
    public boolean isSent()         { return isSent; }
}
