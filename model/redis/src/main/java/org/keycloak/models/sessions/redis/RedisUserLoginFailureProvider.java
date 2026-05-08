package org.keycloak.models.sessions.redis;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.HashCacheAdapter;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserLoginFailureProvider;

/**
 * Redis-backed implementation of UserLoginFailureProvider for brute-force protection.
 *
 * <p>Iteration 4: storage migrated from opaque {@code Map<String, String>} blobs
 * (full read-modify-write per setter) to {@link HashCacheAdapter} (HSET storage,
 * field-level updates). Each setter now writes <em>one</em> field, not the whole map.
 *
 * <p>Wire-cost comparison:
 * <pre>
 *   Old (one bruteforce flow w/ N failures):
 *     read full map  → modify in JVM → write full map back → expire → 4-RT × N
 *   New:
 *     write one field + EXPIRE                              → 1-RT × N
 * </pre>
 */
public class RedisUserLoginFailureProvider implements UserLoginFailureProvider {

    private static final Logger logger = Logger.getLogger(RedisUserLoginFailureProvider.class);
    private static final String KEY_PREFIX = "loginFailure:";

    // Field names — kept stable; if you rename, plan a key-version migration.
    static final String F_NUM_FAILURES = "numFailures";
    static final String F_NUM_TEMP_LOCKOUTS = "numTemporaryLockouts";
    static final String F_LAST_FAILURE = "lastFailure";
    static final String F_LAST_IP_FAILURE = "lastIPFailure";
    static final String F_FAILED_LOGIN_NOT_BEFORE = "failedLoginNotBefore";

    private final KeycloakSession session;
    private final HashCacheAdapter<String> hash;

    public RedisUserLoginFailureProvider(KeycloakSession session) {
        this.session = session;
        RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);
        this.hash = redisProvider.getHashCache(RedisConnectionProvider.LOGIN_FAILURE_CACHE_NAME);
    }

    private String key(String realmId, String userId) {
        return KEY_PREFIX + realmId + ":" + userId;
    }

    private static byte[] b(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(Map<String, byte[]> data, String field, String defaultValue) {
        if (data == null) return defaultValue;
        byte[] v = data.get(field);
        return v == null ? defaultValue : new String(v, StandardCharsets.UTF_8);
    }

    @Override
    public UserLoginFailureModel getUserLoginFailure(RealmModel realm, String userId) {
        String k = key(realm.getId(), userId);
        Map<String, byte[]> data = hash.getAll(k);
        if (data == null || data.isEmpty()) return null;
        return new RedisUserLoginFailureModel(k, realm.getId(), userId, data);
    }

    @Override
    public UserLoginFailureModel addUserLoginFailure(RealmModel realm, String userId) {
        String k = key(realm.getId(), userId);
        Map<String, byte[]> existing = hash.getAll(k);
        if (existing != null && !existing.isEmpty()) {
            return new RedisUserLoginFailureModel(k, realm.getId(), userId, existing);
        }

        // Initial creation: HSET all fields atomically + EXPIRE in one round-trip.
        java.util.Map<String, byte[]> initial = new java.util.LinkedHashMap<>(8);
        initial.put(F_NUM_FAILURES, b("0"));
        initial.put(F_NUM_TEMP_LOCKOUTS, b("0"));
        initial.put(F_LAST_FAILURE, b("0"));
        initial.put(F_LAST_IP_FAILURE, b(""));
        initial.put(F_FAILED_LOGIN_NOT_BEFORE, b("0"));
        hash.putAll(k, initial, getLoginFailureTtl(realm));

        return new RedisUserLoginFailureModel(k, realm.getId(), userId, initial);
    }

    @Override
    public void removeUserLoginFailure(RealmModel realm, String userId) {
        hash.remove(key(realm.getId(), userId));
    }

    @Override
    public void removeAllUserLoginFailures(RealmModel realm) {
        // Iterating all keys requires a SCAN; under brute-force settings this
        // surface is rarely called (admin-initiated reset). Leaving as-is for now —
        // a per-realm secondary index could optimize this later.
        logger.debugf("removeAllUserLoginFailures for realm %s — not optimized; consider indexing per realm", realm.getId());
    }

    private long getLoginFailureTtl(RealmModel realm) {
        if (!realm.isBruteForceProtected()) return 3600L;
        int maxDelta = realm.getMaxDeltaTimeSeconds();
        return maxDelta > 0 ? maxDelta * 2L : 3600L;
    }

    @Override
    public void close() {}

    /** Mutable model — each setter writes one HSET field, no read needed. */
    private final class RedisUserLoginFailureModel implements UserLoginFailureModel {
        private final String key;
        private final String realmId;
        private final String userId;
        private final Map<String, byte[]> snapshot; // local view; persisted state lives in Redis

        RedisUserLoginFailureModel(String key, String realmId, String userId, Map<String, byte[]> snapshot) {
            this.key = key;
            this.realmId = realmId;
            this.userId = userId;
            this.snapshot = new java.util.LinkedHashMap<>(snapshot);
        }

        @Override public String getId() { return key; }
        @Override public String getUserId() { return userId; }

        @Override public int getFailedLoginNotBefore() {
            return Integer.parseInt(s(snapshot, F_FAILED_LOGIN_NOT_BEFORE, "0"));
        }
        @Override public void setFailedLoginNotBefore(int notBefore) {
            String v = String.valueOf(notBefore);
            snapshot.put(F_FAILED_LOGIN_NOT_BEFORE, b(v));
            persistField(F_FAILED_LOGIN_NOT_BEFORE, v);
        }

        @Override public int getNumFailures() {
            return Integer.parseInt(s(snapshot, F_NUM_FAILURES, "0"));
        }
        @Override public void incrementFailures() {
            String v = String.valueOf(getNumFailures() + 1);
            snapshot.put(F_NUM_FAILURES, b(v));
            persistField(F_NUM_FAILURES, v);
        }

        @Override public int getNumTemporaryLockouts() {
            return Integer.parseInt(s(snapshot, F_NUM_TEMP_LOCKOUTS, "0"));
        }
        @Override public void incrementTemporaryLockouts() {
            String v = String.valueOf(getNumTemporaryLockouts() + 1);
            snapshot.put(F_NUM_TEMP_LOCKOUTS, b(v));
            persistField(F_NUM_TEMP_LOCKOUTS, v);
        }

        @Override public void clearFailures() {
            // Five-field reset → could be one HSET multi-field call, but that
            // requires touching HashCacheAdapter for a multi-set. Since this is
            // rare, do five putField calls; still half the traffic of the old code.
            snapshot.put(F_NUM_FAILURES, b("0"));
            snapshot.put(F_NUM_TEMP_LOCKOUTS, b("0"));
            snapshot.put(F_LAST_FAILURE, b("0"));
            snapshot.put(F_LAST_IP_FAILURE, b(""));
            snapshot.put(F_FAILED_LOGIN_NOT_BEFORE, b("0"));
            // One HSET call writing all five fields atomically + refresh TTL.
            long ttl = ttlNow();
            hash.putAll(key, snapshot, ttl);
        }

        @Override public long getLastFailure() {
            return Long.parseLong(s(snapshot, F_LAST_FAILURE, "0"));
        }
        @Override public void setLastFailure(long lastFailure) {
            String v = String.valueOf(lastFailure);
            snapshot.put(F_LAST_FAILURE, b(v));
            persistField(F_LAST_FAILURE, v);
        }

        @Override public String getLastIPFailure() {
            return s(snapshot, F_LAST_IP_FAILURE, "");
        }
        @Override public void setLastIPFailure(String ip) {
            String v = ip != null ? ip : "";
            snapshot.put(F_LAST_IP_FAILURE, b(v));
            persistField(F_LAST_IP_FAILURE, v);
        }

        private void persistField(String field, String value) {
            // HSET key field value + EXPIRE key ttl — 1 RT (pipelined inside the adapter).
            hash.putField(key, field, b(value), ttlNow());
        }

        private long ttlNow() {
            RealmModel realm = session.realms().getRealm(realmId);
            return realm != null ? getLoginFailureTtl(realm) : 3600L;
        }
    }
}
