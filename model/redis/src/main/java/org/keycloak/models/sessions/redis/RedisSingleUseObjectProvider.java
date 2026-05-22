package org.keycloak.models.sessions.redis;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Redis-backed implementation of SingleUseObjectProvider.
 * Used for action tokens, revoked tokens, and other single-use objects.
 * Redis TTL handles automatic expiration.
 */
public class RedisSingleUseObjectProvider implements SingleUseObjectProvider {

    private static final Logger logger = Logger.getLogger(RedisSingleUseObjectProvider.class);
    private static final String CACHE_PREFIX = "singleUse:";

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

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
        String cacheK = cacheKey(key);
        Map<String, String> data = notes != null ? notes : Collections.emptyMap();
        if (lifespanSeconds > 0) {
            cache.put(cacheK, data, lifespanSeconds, TimeUnit.SECONDS);
        } else {
            cache.put(cacheK, data);
        }
    }

    @Override
    public Map<String, String> get(String key) {
        Map<String, String> data = cache.get(cacheKey(key));
        return data != null && !data.isEmpty() ? data : null;
    }

    @Override
    public Map<String, String> remove(String key) {
        // {@link RedisCache#remove} is implemented via {@code GETDEL} in Lettuce —
        // one round-trip that atomically returns the old value and deletes the key.
        // The previous read-then-delete (2 RTs) had a race where the read could see
        // a value that another node deleted before our delete arrived.
        return cache.remove(cacheKey(key));
    }

    @Override
    public boolean replace(String key, Map<String, String> notes) {
        String cacheK = cacheKey(key);
        if (!cache.containsKey(cacheK)) {
            return false;
        }
        // Replace preserves the existing TTL in Redis
        cache.put(cacheK, notes);
        return true;
    }

    @Override
    public boolean putIfAbsent(String key, long lifespanInSeconds) {
        String cacheK = cacheKey(key);
        Map<String, String> existing;
        if (lifespanInSeconds > 0) {
            existing = cache.putIfAbsent(cacheK, Collections.emptyMap(), lifespanInSeconds, TimeUnit.SECONDS);
        } else {
            existing = cache.putIfAbsent(cacheK, Collections.emptyMap());
        }
        // putIfAbsent returns null if the key was absent (value was stored successfully)
        return existing == null;
    }

    @Override
    public boolean contains(String key) {
        return cache.containsKey(cacheKey(key));
    }

    @Override
    public void close() {
    }
}
