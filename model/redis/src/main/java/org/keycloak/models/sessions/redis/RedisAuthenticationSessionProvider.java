package org.keycloak.models.sessions.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.HashCacheAdapter;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.redis.entities.RedisAuthenticationSessionEntity;
import org.keycloak.models.sessions.redis.entities.RedisRootAuthenticationSessionEntity;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * Redis-backed AuthenticationSessionProvider — iteration 5: HSET storage.
 *
 * <p>Each root authentication session is one Redis hash. Top-level scalars
 * (id/realmId/timestamp) and each child auth session (tab) are separate fields
 * inside that hash:
 *
 * <pre>
 *   authSession:&lt;rootId&gt;   HASH
 *     id          → root id (UTF-8)
 *     realmId     → realm id (UTF-8)
 *     timestamp   → epoch seconds (UTF-8)
 *     tab:&lt;tabId&gt; → Java-serialized RedisAuthenticationSessionEntity bytes
 *   TTL on the key (EXPIRE).
 * </pre>
 *
 * <p>Hot-path mutations write a single field instead of re-serializing the entire
 * tree. {@code setTimestamp(t)} = {@code HSET timestamp t} (≈ 10 bytes).
 * {@code onChildUpdated(tabId, child)} = {@code HSET tab:&lt;tabId&gt; &lt;bytes&gt;}
 * (only that one tab's worth of bytes, not all tabs combined).
 *
 * <p>Reads still cost one round-trip ({@code HGETALL} returns all fields), so
 * read latency is unchanged. The win is on the write path under sustained load.
 */
public class RedisAuthenticationSessionProvider implements AuthenticationSessionProvider {

    private static final Logger logger = Logger.getLogger(RedisAuthenticationSessionProvider.class);
    private static final String CACHE_PREFIX = "authSession:";
    private static final String F_ID = "id";
    private static final String F_REALM_ID = "realmId";
    private static final String F_TIMESTAMP = "timestamp";
    private static final String TAB_FIELD_PREFIX = "tab:";

    private final KeycloakSession session;
    private final HashCacheAdapter<String> hash;
    private final int authSessionsLimit;

    public RedisAuthenticationSessionProvider(KeycloakSession session, int authSessionsLimit) {
        this.session = session;
        this.authSessionsLimit = authSessionsLimit;
        RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);
        this.hash = redisProvider.getHashCache(RedisConnectionProvider.AUTHENTICATION_SESSIONS_CACHE_NAME);
    }

    private String cacheKey(String sessionId) {
        return CACHE_PREFIX + sessionId;
    }

    private long getAuthSessionLifespan(RealmModel realm) {
        // Same lifespan as upstream (max of login timeout / user action / access code),
        // not just access-code lifespan (60s) which cut logins off mid-flow.
        int lifespan = SessionExpiration.getAuthSessionLifespan(realm);
        return lifespan > 0 ? lifespan : 300;
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
        return createRootAuthenticationSession(realm, null);
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm, String id) {
        if (id == null) {
            id = SecretGenerator.getInstance().randomString(24);
        }
        RedisRootAuthenticationSessionEntity entity = new RedisRootAuthenticationSessionEntity(id, realm.getId(), Time.currentTime());
        // Initial creation: HSET all top-level fields + EXPIRE in one round-trip.
        Map<String, byte[]> fields = new LinkedHashMap<>(8);
        fields.put(F_ID, utf8(id));
        fields.put(F_REALM_ID, utf8(realm.getId()));
        fields.put(F_TIMESTAMP, utf8(String.valueOf(entity.getTimestamp())));
        hash.putAll(cacheKey(id), fields, getAuthSessionLifespan(realm));
        return new RedisRootAuthenticationSessionAdapter(session, this, realm, entity, authSessionsLimit);
    }

    @Override
    public RootAuthenticationSessionModel getRootAuthenticationSession(RealmModel realm, String authenticationSessionId) {
        if (authenticationSessionId == null) return null;
        Map<String, byte[]> fields = hash.getAll(cacheKey(authenticationSessionId));
        if (fields == null || fields.isEmpty()) return null;

        RedisRootAuthenticationSessionEntity entity = reconstruct(fields);
        if (entity == null || !realm.getId().equals(entity.getRealmId())) return null;

        return new RedisRootAuthenticationSessionAdapter(session, this, realm, entity, authSessionsLimit);
    }

    @Override
    public void removeRootAuthenticationSession(RealmModel realm, RootAuthenticationSessionModel authenticationSession) {
        hash.remove(cacheKey(authenticationSession.getId()));
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        // Realm removal is rare and admin-initiated; the previous SCAN-based fallback
        // is still here. A per-realm secondary index would optimize this but adds
        // write-path cost for an op that runs once per realm-delete.
        logger.debugf("onRealmRemoved %s — full-keyspace SCAN not implemented in HSET schema; sessions will expire via TTL", realm.getId());
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        // No-op — deprecated in upstream, all Infinispan implementations are empty
    }

    @Override
    public void updateNonlocalSessionAuthNotes(AuthenticationSessionCompoundId compoundId, Map<String, String> authNotesFragment) {
        // Deprecated in upstream. With Redis as shared store, auth notes are already visible to all nodes.
    }

    /**
     * Initial whole-entity persist (used by the adapter on creation paths that
     * write multiple fields at once). One HSET multi-field call + EXPIRE.
     */
    void persistRootAuthSession(RealmModel realm, RedisRootAuthenticationSessionEntity entity) {
        Map<String, byte[]> fields = new LinkedHashMap<>(8 + entity.getAuthenticationSessions().size() * 2);
        fields.put(F_ID, utf8(entity.getId()));
        fields.put(F_REALM_ID, utf8(entity.getRealmId()));
        fields.put(F_TIMESTAMP, utf8(String.valueOf(entity.getTimestamp())));
        for (Map.Entry<String, RedisAuthenticationSessionEntity> e : entity.getAuthenticationSessions().entrySet()) {
            fields.put(TAB_FIELD_PREFIX + e.getKey(), serialize(e.getValue()));
        }
        hash.putAll(cacheKey(entity.getId()), fields, getAuthSessionLifespan(realm));
    }

    /** Hot path: update only the root timestamp. One HSET + EXPIRE round-trip. */
    void persistTimestamp(RealmModel realm, String rootId, int timestamp) {
        hash.putField(cacheKey(rootId), F_TIMESTAMP, utf8(String.valueOf(timestamp)),
                getAuthSessionLifespan(realm));
    }

    /** Hot path: update one tab's serialized state. One HSET + EXPIRE round-trip. */
    void persistTab(RealmModel realm, String rootId, String tabId, RedisAuthenticationSessionEntity tab) {
        hash.putField(cacheKey(rootId), TAB_FIELD_PREFIX + tabId, serialize(tab),
                getAuthSessionLifespan(realm));
    }

    /** Remove one tab's field, refresh parent's TTL. One HDEL + EXPIRE round-trip. */
    void removeTab(RealmModel realm, String rootId, String tabId) {
        hash.deleteFieldRefreshTtl(cacheKey(rootId), TAB_FIELD_PREFIX + tabId,
                getAuthSessionLifespan(realm));
    }

    /**
     * Remove one tab, then delete the whole root when no tabs remain (upstream drops
     * an empty root session); otherwise bump the root timestamp. The remaining-tabs
     * check is done server-side because with concurrent removals the caller's local
     * entity can be stale — whoever HDELs the last tab must also drop the root.
     */
    void removeTabCollapsingEmptyRoot(RealmModel realm, String rootId, String tabId, int timestamp) {
        removeTab(realm, rootId, tabId);
        boolean tabsLeft = hash.fieldNames(cacheKey(rootId)).stream()
                .anyMatch(f -> f.startsWith(TAB_FIELD_PREFIX));
        if (tabsLeft) {
            persistTimestamp(realm, rootId, timestamp);
        } else {
            hash.remove(cacheKey(rootId));
        }
    }

    @Override
    public void close() {}

    // -- entity ↔ field-map plumbing ------------------------------------------

    private RedisRootAuthenticationSessionEntity reconstruct(Map<String, byte[]> fields) {
        byte[] idBytes = fields.get(F_ID);
        if (idBytes == null) return null; // malformed
        RedisRootAuthenticationSessionEntity root = new RedisRootAuthenticationSessionEntity();
        root.setId(asString(idBytes));
        root.setRealmId(asString(fields.get(F_REALM_ID)));
        byte[] tsBytes = fields.get(F_TIMESTAMP);
        if (tsBytes != null) {
            try { root.setTimestamp(Integer.parseInt(asString(tsBytes))); } catch (NumberFormatException ignored) {}
        }
        for (Map.Entry<String, byte[]> e : fields.entrySet()) {
            if (e.getKey().startsWith(TAB_FIELD_PREFIX)) {
                String tabId = e.getKey().substring(TAB_FIELD_PREFIX.length());
                RedisAuthenticationSessionEntity child = deserialize(e.getValue());
                if (child != null) {
                    root.getAuthenticationSessions().put(tabId, child);
                }
            }
        }
        return root;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String asString(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize auth session entity: " + obj.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (T) ois.readObject();
        } catch (Exception e) {
            logger.warnf(e, "Failed to deserialize auth session bytes (size=%d)", bytes.length);
            return null;
        }
    }
}
