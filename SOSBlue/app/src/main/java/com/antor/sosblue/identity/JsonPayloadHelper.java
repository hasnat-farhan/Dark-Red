package com.antor.sosblue.identity;

/**
 * Utility for extracting fields from simple key-value JSON payloads.
 * Used by both the engine processor and the Android UI layer.
 */
public final class JsonPayloadHelper {

    private JsonPayloadHelper() { }

    /**
     * Extracts a JSON string field value from a simple key-value payload.
     * Handles both quoted and unquoted values.
     *
     * @param json the raw JSON string
     * @param key  the field name to extract
     * @return the extracted value, or {@code null} if not found
     */
    public static String extractField(String json, String key) {
        if (json == null || key == null) return null;
        try {
            String searchKey = "\"" + key + "\"";
            int keyIdx = json.indexOf(searchKey);
            if (keyIdx < 0) {
                searchKey = key;
                keyIdx = json.indexOf(searchKey);
            }
            if (keyIdx < 0) return null;
            int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
            if (colonIdx < 0) return null;
            int valueStart = json.indexOf('"', colonIdx + 1);
            if (valueStart < 0) {
                int valueEnd = json.indexOf(',', colonIdx + 1);
                if (valueEnd < 0) valueEnd = json.indexOf('}', colonIdx + 1);
                if (valueEnd < 0) valueEnd = json.length();
                return json.substring(colonIdx + 1, valueEnd).trim();
            }
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return null;
            return json.substring(valueStart + 1, valueEnd);
        } catch (Exception e) {
            return null;
        }
    }
}
