package com.antor.sosblue.media;

/**
 * Callback for media transfer progress (upload/download chunks).
 */
public interface MediaTransferListener {
    /** Invoked on the main thread as each chunk is sent/received. */
    void onProgress(String transferId, int chunksSent, int totalChunks);

    /** Invoked on the main thread when the entire transfer completes. */
    void onComplete(String transferId, byte[] assembledData);

    /** Invoked on the main thread if the transfer fails. */
    void onFailed(String transferId, String reason);
}
