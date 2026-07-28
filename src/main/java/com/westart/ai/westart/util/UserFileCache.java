package com.westart.ai.westart.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UserFileCache {

    private static final Map<String, StoredFile> cache = new ConcurrentHashMap<>();

    public static String store(String userId, String fileName, byte[] data, String mime) {
        String key = userId + "::" + UUID.randomUUID();
        cache.put(key, new StoredFile(fileName, data, mime));
        return key;
    }

    public static StoredFile get(String fileKey) {
        return cache.get(fileKey);
    }

    public static void remove(String fileKey) {
        cache.remove(fileKey);
    }

    public record StoredFile(String fileName, byte[] data, String mime) {}
}
