package com.antor.sosblue.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for chunking large media files (images, videos) into transportable
 * segments and reassembling them on the receiving end.
 *
 * <p><strong>Why chunk?</strong> The F2P mesh engine carries payloads as
 * {@code byte[]} inside {@code FibreSignal} maps. Very large files would
 * cause OOM or excessive GC pauses. Splitting into ~256 KB chunks keeps
 * each packet within a safe memory budget while still allowing files up
 * to hundreds of MB to flow through the mesh.</p>
 *
 * <h3>Chunk format (wire)</h3>
 * <pre>
 *   [transferId:36UTF][chunkIndex:4][totalChunks:4][fileNameLen:4][fileName]
 *   [mimeTypeLen:4][mimeType][dataLen:4][data][checksum:32]
 * </pre>
 * <ul>
 *   <li><b>transferId</b> — UUID that ties all chunks of one file together</li>
 *   <li><b>chunkIndex</b> — 0-based position in the sequence</li>
 *   <li><b>totalChunks</b> — total number of chunks for this file</li>
 *   <li><b>fileName</b> — original filename (for display)</li>
 *   <li><b>mimeType</b> — e.g. image/jpeg, video/mp4</li>
 *   <li><b>data</b> — raw chunk bytes</li>
 *   <li><b>checksum</b> — SHA-256 of the raw chunk data for integrity</li>
 * </ul>
 */
public final class MediaChunker {

    private static final String TAG = "MediaChunker";

    /** Default chunk size: 256 KB — safe for F2P mesh memory budget. */
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;

    /** Maximum single payload size we allow through the engine. */
    public static final int MAX_PAYLOAD_SIZE = 512 * 1024;

    /** Maximum file size we accept (100 MB). */
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    private MediaChunker() {}

    // ---------------------------------------------------------------
    //  Splitting
    // ---------------------------------------------------------------

    /**
     * Reads the entire content of a URI into memory and splits it into chunks.
     *
     * @param context     Android context for ContentResolver access
     * @param uri         the media URI (content:// or file://)
     * @param fileName    display name of the file
     * @param mimeType    MIME type (e.g. image/jpeg)
     * @param chunkSize   bytes per chunk (use {@link #DEFAULT_CHUNK_SIZE})
     * @return array of {@link MediaChunk} objects, ready for sequential dispatch
     */
    public static MediaChunk[] split(Context context, Uri uri, String fileName,
                                      String mimeType, int chunkSize) {
        // Validate chunk size
        if (chunkSize <= 0) chunkSize = DEFAULT_CHUNK_SIZE;
        if (chunkSize > MAX_PAYLOAD_SIZE) chunkSize = MAX_PAYLOAD_SIZE;

        // Check file size limit
        long fileSize = getFileSize(context, uri);
        if (fileSize > MAX_FILE_SIZE) {
            Log.e(TAG, "File too large: " + fileSize + " bytes (max=" + MAX_FILE_SIZE + ")");
            return new MediaChunk[0];
        }

        // Stream chunks directly — never hold the full file in memory
        String transferId = UUID.randomUUID().toString();
        java.util.List<MediaChunk> chunkList = new java.util.ArrayList<>();

        try {
            ContentResolver resolver = context.getContentResolver();
            try (InputStream is = resolver.openInputStream(uri)) {
                if (is == null) {
                    Log.e(TAG, "Failed to open URI: " + uri);
                    return new MediaChunk[0];
                }

                int chunkIndex = 0;
                byte[] buffer = new byte[chunkSize];
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    byte[] chunkData = (bytesRead == chunkSize)
                            ? buffer.clone()
                            : Arrays.copyOf(buffer, bytesRead);
                    byte[] checksum = sha256(chunkData);

                    // Enforce per-chunk size limit
                    if (chunkData.length > MAX_PAYLOAD_SIZE) {
                        Log.w(TAG, "Chunk " + chunkIndex + " exceeds max payload, skipping");
                        continue;
                    }

                    chunkList.add(new MediaChunk(
                            transferId, chunkIndex, -1, // totalChunks set below
                            fileName, mimeType, chunkData, checksum
                    ));
                    chunkIndex++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to split media: " + fileName, e);
            return new MediaChunk[0];
        }

        // Set correct totalChunks on all chunks
        int totalChunks = chunkList.size();
        MediaChunk[] chunks = new MediaChunk[totalChunks];
        for (int i = 0; i < totalChunks; i++) {
            MediaChunk c = chunkList.get(i);
            chunks[i] = new MediaChunk(
                    c.transferId, c.chunkIndex, totalChunks,
                    c.fileName, c.mimeType, c.data, c.checksum
            );
        }

        Log.d(TAG, "Split " + fileName + " into " + totalChunks
                + " chunks (transferId=" + transferId + ")");
        return chunks;
    }

    /**
     * Convenience overload using default chunk size.
     */
    public static MediaChunk[] split(Context context, Uri uri, String fileName,
                                      String mimeType) {
        return split(context, uri, fileName, mimeType, DEFAULT_CHUNK_SIZE);
    }

    // ---------------------------------------------------------------
    //  Reassembly
    // ---------------------------------------------------------------

    /** In-flight transfers keyed by transferId. */
    private static final ConcurrentHashMap<String, ReassemblyBuffer> buffers =
            new ConcurrentHashMap<>();

    /**
     * Feeds a received chunk into the reassembly buffer.
     *
     * @return the assembled file bytes once all chunks arrive, or {@code null}
     *         if more chunks are still pending.
     */
    public static byte[] feedChunk(MediaChunk chunk) {
        // Verify chunk integrity before accepting
        if (!chunk.verifyIntegrity()) {
            Log.w(TAG, "Chunk integrity check failed for " + chunk.fileName
                    + " chunk " + chunk.chunkIndex + " — discarding");
            return null;
        }

        ReassemblyBuffer buf = buffers.computeIfAbsent(
                chunk.transferId,
                id -> new ReassemblyBuffer(chunk.totalChunks, chunk.fileName, chunk.mimeType)
        );

        buf.putChunk(chunk);

        if (buf.isComplete()) {
            byte[] assembled = buf.getAssembled();
            buffers.remove(chunk.transferId);
            Log.d(TAG, "Reassembly complete for " + chunk.fileName
                    + " (" + assembled.length + " bytes, " + chunk.totalChunks + " chunks)");
            return assembled;
        }

        Log.d(TAG, "Chunk " + (chunk.chunkIndex + 1) + "/" + chunk.totalChunks
                + " received for " + chunk.fileName);
        return null;
    }

    /**
     * Returns the number of chunks received so far for a transfer.
     */
    public static int getReceivedChunkCount(String transferId) {
        ReassemblyBuffer buf = buffers.get(transferId);
        return buf != null ? buf.getReceivedCount() : 0;
    }

    /**
     * Returns the total chunks expected for a transfer.
     */
    public static int getTotalChunkCount(String transferId) {
        ReassemblyBuffer buf = buffers.get(transferId);
        return buf != null ? buf.totalChunks : 0;
    }

    /**
     * Cleans up stale reassembly buffers older than the given age (ms).
     */
    public static void cleanupStaleBuffers(long maxAgeMs) {
        long now = System.currentTimeMillis();
        buffers.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().createdAt;
            if (age > maxAgeMs) {
                Log.w(TAG, "Cleaning up stale buffer: " + entry.getKey()
                        + " (" + entry.getValue().fileName + ")");
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static byte[] readAllBytes(Context context, Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            try (InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (Exception e) {
            Log.e(TAG, "readAllBytes failed for " + uri, e);
            return null;
        }
    }

    /**
     * Returns the file size for a content URI, or -1 if unknown.
     */
    public static long getFileSize(Context context, Uri uri) {
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (idx >= 0) return cursor.getLong(idx);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Returns the display name (filename) for a content URI.
     */
    public static String getFileName(Context context, Uri uri) {
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) return cursor.getString(idx);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) {}
        // Fallback: use last path segment
        String path = uri.getLastPathSegment();
        return path != null ? path : "unknown_file";
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ---------------------------------------------------------------
    //  Inner: MediaChunk
    // ---------------------------------------------------------------

    /**
     * A single chunk of a media file, ready for wire transmission.
     */
    public static final class MediaChunk {
        public final String transferId;
        public final int chunkIndex;
        public final int totalChunks;
        public final String fileName;
        public final String mimeType;
        public final byte[] data;
        public final byte[] checksum;

        public MediaChunk(String transferId, int chunkIndex, int totalChunks,
                          String fileName, String mimeType, byte[] data, byte[] checksum) {
            this.transferId = transferId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.data = data;
            this.checksum = checksum;
        }

        /** Serializes this chunk for wire transmission. */
        public byte[] serialize() {
            byte[] transferBytes = transferId.getBytes(StandardCharsets.UTF_8);
            byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            byte[] mimeBytes = mimeType.getBytes(StandardCharsets.UTF_8);

            int totalSize = 4 + transferBytes.length
                    + 4   // chunkIndex
                    + 4   // totalChunks
                    + 4 + nameBytes.length
                    + 4 + mimeBytes.length
                    + 4 + data.length
                    + 4 + checksum.length;

            ByteBuffer buf = ByteBuffer.allocate(totalSize);
            buf.putInt(transferBytes.length);
            buf.put(transferBytes);
            buf.putInt(chunkIndex);
            buf.putInt(totalChunks);
            buf.putInt(nameBytes.length);
            buf.put(nameBytes);
            buf.putInt(mimeBytes.length);
            buf.put(mimeBytes);
            buf.putInt(data.length);
            buf.put(data);
            buf.putInt(checksum.length);
            buf.put(checksum);
            return buf.array();
        }

        /** Deserializes a chunk from wire bytes. */
        public static MediaChunk deserialize(byte[] wire) {
            ByteBuffer buf = ByteBuffer.wrap(wire);

            int transferLen = buf.getInt();
            byte[] transferBytes = new byte[transferLen];
            buf.get(transferBytes);

            int chunkIndex = buf.getInt();
            int totalChunks = buf.getInt();

            int nameLen = buf.getInt();
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);

            int mimeLen = buf.getInt();
            byte[] mimeBytes = new byte[mimeLen];
            buf.get(mimeBytes);

            int dataLen = buf.getInt();
            byte[] data = new byte[dataLen];
            buf.get(data);

            int csLen = buf.getInt();
            byte[] checksum = new byte[csLen];
            buf.get(checksum);

            return new MediaChunk(
                    new String(transferBytes, StandardCharsets.UTF_8),
                    chunkIndex, totalChunks,
                    new String(nameBytes, StandardCharsets.UTF_8),
                    new String(mimeBytes, StandardCharsets.UTF_8),
                    data, checksum
            );
        }

        /**
         * Checks if this chunk's data integrity is valid.
         */
        public boolean verifyIntegrity() {
            byte[] computed = sha256(data);
            return Arrays.equals(computed, checksum);
        }
    }

    // ---------------------------------------------------------------
    //  Inner: ReassemblyBuffer
    // ---------------------------------------------------------------

    private static final class ReassemblyBuffer {
        final String fileName;
        final String mimeType;
        final int totalChunks;
        final long createdAt;
        private final byte[][] chunks;
        private int receivedCount;

        ReassemblyBuffer(int totalChunks, String fileName, String mimeType) {
            this.totalChunks = totalChunks;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.chunks = new byte[totalChunks][];
            this.receivedCount = 0;
            this.createdAt = System.currentTimeMillis();
        }

        synchronized void putChunk(MediaChunk chunk) {
            if (chunk.chunkIndex >= 0 && chunk.chunkIndex < totalChunks
                    && chunks[chunk.chunkIndex] == null) {
                chunks[chunk.chunkIndex] = chunk.data;
                receivedCount++;
            }
        }

        synchronized boolean isComplete() {
            return receivedCount == totalChunks;
        }

        synchronized int getReceivedCount() {
            return receivedCount;
        }

        synchronized byte[] getAssembled() {
            int totalSize = 0;
            for (byte[] c : chunks) {
                if (c != null) totalSize += c.length;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(totalSize);
            for (byte[] c : chunks) {
                if (c != null) bos.write(c, 0, c.length);
            }
            return bos.toByteArray();
        }
    }
}
