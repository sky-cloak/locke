package org.keycloak.models.sessions.redis;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.common.util.Time;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Redis-backed implementation of SingleUseObjectProvider.
 * Used for action tokens, revoked tokens, and other single-use objects.
 *
 * <p>Expiration is enforced twice: Redis TTL reclaims the memory, and a logical
 * expiry timestamp stored with the entry is checked on read against Keycloak's
 * clock. The logical check keeps lifespans correct even when the Redis server's
 * clock drifts from the Keycloak nodes (upstream enforces expiry on the Keycloak
 * side via Infinispan's time service). Logically expired entries are deleted on
 * read.
 */
public class RedisSingleUseObjectProvider implements SingleUseObjectProvider {

    private static final Logger logger = Logger.getLogger(RedisSingleUseObjectProvider.class);
    private static final String CACHE_PREFIX = "singleUse:";
    // Reserved note holding the logical expiry (epoch seconds); stripped from returned maps.
    private static final String EXPIRES_AT_NOTE = "__expiresAt";

    private final KeycloakSession session;
    private final RedisCache<String, Map<String, String>> cache;

    public RedisSingleUseObjectProvider(KeycloakSession session) {
        this.session = session;
        RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);
        this.cache = redisProvider.getCache(RedisConnectionProvider.ACTION_TOKEN_CACHE);
    }

    private String cacheKey(String key) {
        return CACHE_PREFIX + key;
    }

    private static boolean isLogicallyExpired(Map<String, String> data) {
        String expiresAt = data.get(EXPIRES_AT_NOTE);
        if (expiresAt == null) {
            return false;
        }
        try {
            return Long.parseLong(expiresAt) <= Time.currentTime();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, String> withExpiry(Map<String, String> notes, long expiresAt) {
        Map<String, String> data = new HashMap<>(notes != null ? notes : Collections.emptyMap());
        data.put(EXPIRES_AT_NOTE, String.valueOf(expiresAt));
        return data;
    }

    private static Map<String, String> stripExpiry(Map<String, String> data) {
        if (!data.containsKey(EXPIRES_AT_NOTE)) {
            return data;
        }
        Map<String, String> stripped = new HashMap<>(data);
        stripped.remove(EXPIRES_AT_NOTE);
        return stripped;
    }

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
        String cacheK = cacheKey(key);
        if (lifespanSeconds > 0) {
            cache.put(cacheK, withExpiry(notes, Time.currentTime() + lifespanSeconds), lifespanSeconds, TimeUnit.SECONDS);
        } else {
            cache.put(cacheK, notes != null ? notes : Collections.emptyMap());
        }
    }

    @Override
    public Map<String, String> get(String key) {
        String cacheK = cacheKey(key);
        Map<String, String> data = cache.get(cacheK);
        if (data == null) {
            return null;
        }
        if (isLogicallyExpired(data)) {
            cache.remove(cacheK);
            return null;
        }
        return stripExpiry(data);
    }

    @Override
    public Map<String, String> remove(String key) {
        // {@link RedisCache#remove} is implemented via {@code GETDEL} in Lettuce —
        // one round-trip that atomically returns the old value and deletes the key.
        // The previous read-then-delete (2 RTs) had a race where the read could see
        // a value that another node deleted before our delete arrived.
        Map<String, String> data = cache.remove(cacheKey(key));
        if (data == null || isLogicallyExpired(data)) {
            return null;
        }
        return stripExpiry(data);
    }

    @Override
    public boolean replace(String key, Map<String, String> notes) {
        String cacheK = cacheKey(key);
        Map<String, String> existing = cache.get(cacheK);
        if (existing == null) {
            return false;
        }
        if (isLogicallyExpired(existing)) {
            cache.remove(cacheK);
            return false;
        }
        String expiresAt = existing.get(EXPIRES_AT_NOTE);
        if (expiresAt == null) {
            cache.put(cacheK, notes != null ? notes : Collections.emptyMap());
            return true;
        }
        // Preserve the remaining lifespan of the replaced entry.
        long remaining = Long.parseLong(expiresAt) - Time.currentTime();
        if (remaining <= 0) {
            cache.remove(cacheK);
            return false;
        }
        Map<String, String> data = new HashMap<>(notes != null ? notes : Collections.emptyMap());
        data.put(EXPIRES_AT_NOTE, expiresAt);
        cache.put(cacheK, data, remaining, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean putIfAbsent(String key, long lifespanInSeconds) {
        String cacheK = cacheKey(key);
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> existing;
            if (lifespanInSeconds > 0) {
                existing = cache.putIfAbsent(cacheK, withExpiry(Collections.emptyMap(), Time.currentTime() + lifespanInSeconds),
                        lifespanInSeconds, TimeUnit.SECONDS);
            } else {
                existing = cache.putIfAbsent(cacheK, Collections.emptyMap());
            }
            if (existing == null) {
                return true; // key was absent, value stored
            }
            if (!isLogicallyExpired(existing)) {
                return false;
            }
            // Logically expired leftover (Redis TTL lagging): drop it and try once more.
            cache.remove(cacheK);
        }
        return false;
    }

    @Override
    public boolean contains(String key) {
        String cacheK = cacheKey(key);
        Map<String, String> data = cache.get(cacheK);
        if (data == null) {
            return false;
        }
        if (isLogicallyExpired(data)) {
            cache.remove(cacheK);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
    }
}
