package com.antor.sosblue.bridge;

import android.util.Log;

import com.antor.sosblue.media.MediaChunker;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Helper for encoding and decoding media chunk metadata into a binary format
 * that can be embedded inside an {@link com.antor.sosblue.identity.F2PMessage}
 * encrypted payload and sent via SMS (DR1 envelope).
 *
 * <p><strong>Wire format:</strong></p>
 * <pre>
 *   [magic:2]        = "MC" (0x4D43)
 *   [version:1]      = 1
 *   [transferIdLen:2] = unsigned short
 *   [transferId]
 *   [chunkIndex:4]   = int
 *   [totalChunks:4]  = int
 *   [fileNameLen:2]  = unsigned short
 *   [fileName]
 *   [mimeTypeLen:2]  = unsigned short
 *   [mimeType]
 *   [contentType:4]  = int
 *   [dataLen:4]      = int
 *   [chunkData]
 * </pre>
 *
 * <p>The entire blob is encrypted with AES-256-GCM and placed inside the
 * F2PMessage's {@code encryptedPayload} field. On the receiving end it is
 * decrypted first, then this class parses the header and extracts the
 * original {@link MediaChunker.MediaChunk}.</p>
 */
public final class SmsMediaHelper {

    private static final String TAG = "SmsMediaHelper";

    /** Magic bytes identifying an SMS media chunk payload. */
    private static final byte[] MAGIC = new byte[]{'M', 'C'};

    /** Current version of the binary format. */
    private static final byte VERSION = 1;

    /** Maximum file size allowed for SMS media transfer (10 KB). */
    public static final long MAX_SMS_FILE_SIZE = 10 * 1024; // 10 KB

    private SmsMediaHelper() {}

    // ---------------------------------------------------------------
    //  Encode
    // ---------------------------------------------------------------

    /**
     * Encodes a {@link MediaChunker.MediaChunk} into a binary byte array
     * suitable for encryption and SMS transport.
     *
     * @param chunk      the media chunk to encode
     * @param contentType the content type constant (TYPE_IMAGE / TYPE_VIDEO)
     * @return binary blob: {@code [header][raw_chunk_data]}
     */
    public static byte[] encodeChunk(MediaChunker.MediaChunk chunk, int contentType) {
        byte[] transferBytes = chunk.transferId.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = chunk.fileName.getBytes(StandardCharsets.UTF_8);
        byte[] mimeBytes = chunk.mimeType.getBytes(StandardCharsets.UTF_8);

        if (transferBytes.length > 65535 || nameBytes.length > 65535
                || mimeBytes.length > 65535) {
            throw new IllegalArgumentException("Metadata field too long for SMS encoding");
        }

        int totalSize = MAGIC.length           // 2
                + 1                            // version: 1
                + 2 + transferBytes.length      // transferId
                + 4                             // chunkIndex
                + 4                             // totalChunks
                + 2 + nameBytes.length          // fileName
                + 2 + mimeBytes.length          // mimeType
                + 4                             // contentType
                + 4 + chunk.data.length;        // chunkData

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.putShort((short) transferBytes.length);
        buf.put(transferBytes);
        buf.putInt(chunk.chunkIndex);
        buf.putInt(chunk.totalChunks);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);
        buf.putShort((short) mimeBytes.length);
        buf.put(mimeBytes);
        buf.putInt(contentType);
        buf.putInt(chunk.data.length);
        buf.put(chunk.data);

        return buf.array();
    }

    // ---------------------------------------------------------------
    //  Decode
    // ---------------------------------------------------------------

    /**
     * Result of decoding a binary SMS media payload back into its
     * constituent parts.
     */
    public static final class DecodedChunk {
        public final String transferId;
        public final int chunkIndex;
        public final int totalChunks;
        public final String fileName;
        public final String mimeType;
        public final int contentType;
        public final byte[] data;

        public DecodedChunk(String transferId, int chunkIndex, int totalChunks,
                            String fileName, String mimeType, int contentType,
                            byte[] data) {
            this.transferId = transferId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.contentType = contentType;
            this.data = data;
        }

        /** Converts this decoded chunk back to a {@link MediaChunker.MediaChunk}. */
        public MediaChunker.MediaChunk toMediaChunk() {
            byte[] checksum = MediaChunker.computeChecksum(data);
            return new MediaChunker.MediaChunk(
                    transferId, chunkIndex, totalChunks,
                    fileName, mimeType, data, checksum
            );
        }
    }

    /**
     * Attempts to parse a decrypted byte array as an SMS media chunk.
     *
     * @param decryptedBytes the full decrypted payload from an F2PMessage
     * @return a parsed {@link DecodedChunk} if the payload starts with the
     *         magic bytes, or {@code null} if it's not an SMS media payload
     *         (i.e. it's a regular text message)
     */
    public static DecodedChunk tryDecode(byte[] decryptedBytes) {
        if (decryptedBytes == null || decryptedBytes.length < MAGIC.length + 1) {
            return null;
        }
        // Check magic bytes
        if (decryptedBytes[0] != MAGIC[0] || decryptedBytes[1] != MAGIC[1]) {
            return null;  // Not an SMS media chunk — regular text
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(decryptedBytes);
            // Skip magic + version
            buf.get(new byte[MAGIC.length]);
            buf.get(); // version

            int transferLen = buf.getShort() & 0xFFFF;
            byte[] transferBytes = new byte[transferLen];
            buf.get(transferBytes);

            int chunkIndex = buf.getInt();
            int totalChunks = buf.getInt();

            int nameLen = buf.getShort() & 0xFFFF;
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);

            int mimeLen = buf.getShort() & 0xFFFF;
            byte[] mimeBytes = new byte[mimeLen];
            buf.get(mimeBytes);

            int contentType = buf.getInt();

            int dataLen = buf.getInt();
            byte[] chunkData = new byte[dataLen];
            buf.get(chunkData);

            return new DecodedChunk(
                    new String(transferBytes, StandardCharsets.UTF_8),
                    chunkIndex, totalChunks,
                    new String(nameBytes, StandardCharsets.UTF_8),
                    new String(mimeBytes, StandardCharsets.UTF_8),
                    contentType,
                    chunkData
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode SMS media chunk", e);
            return null;
        }
    }
}
