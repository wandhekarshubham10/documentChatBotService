package com.rag.documentChatBot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class RagSupport {
    private final StringRedisTemplate redis;

    public RagSupport(StringRedisTemplate redis) { this.redis = redis; }

    public String cached(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException ignored) { return null; }
    }

    public void cache(String key, String value) {
        if (value == null || value.isBlank()) return;
        try { redis.opsForValue().set(key, value, 1, TimeUnit.HOURS); } catch (RuntimeException ignored) { }
    }

    public boolean allow(String userId, String ip, int limit) {
        String key = "rag:bucket:" + sha256(userId + ":" + ip);
        String script = "local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or tonumber(ARGV[1]) "
            + "local updated = tonumber(redis.call('HGET', KEYS[1], 'updated')) or tonumber(ARGV[3]) "
            + "local now = tonumber(ARGV[3]) "
            + "tokens = math.min(tonumber(ARGV[1]), tokens + ((now - updated) / 1000) * tonumber(ARGV[2])) "
            + "local allowed = 0 "
            + "if tokens >= 1 then tokens = tokens - 1; allowed = 1 end "
            + "redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now) "
            + "redis.call('EXPIRE', KEYS[1], 120) return allowed";
        try {
            Long allowed = redis.execute(RedisScript.of(script, Long.class), List.of(key),
                String.valueOf(limit), String.valueOf(limit / 60.0), String.valueOf(System.currentTimeMillis()));
            return Long.valueOf(1).equals(allowed);
        } catch (RuntimeException ignored) { return true; }
    }

    public String cacheKey(String ownerId, String documentId, String query) {
        return "rag:answer:" + sha256(ownerId + ":" + documentId + ":" + query.trim().toLowerCase());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
