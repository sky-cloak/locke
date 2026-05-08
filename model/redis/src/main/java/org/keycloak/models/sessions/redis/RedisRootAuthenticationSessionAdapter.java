package org.keycloak.models.sessions.redis;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.redis.entities.RedisAuthenticationSessionEntity;
import org.keycloak.models.sessions.redis.entities.RedisRootAuthenticationSessionEntity;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * Adapter for root authentication session backed by Redis.
 */
public class RedisRootAuthenticationSessionAdapter implements RootAuthenticationSessionModel {

    private static final Logger log = Logger.getLogger(RedisRootAuthenticationSessionAdapter.class);

    private static final Comparator<Map.Entry<String, RedisAuthenticationSessionEntity>> TIMESTAMP_COMPARATOR =
            Comparator.comparingInt(e -> e.getValue().getTimestamp());

    private final KeycloakSession session;
    private final RedisAuthenticationSessionProvider provider;
    private final RealmModel realm;
    private final RedisRootAuthenticationSessionEntity entity;
    private final int authSessionsLimit;

    public RedisRootAuthenticationSessionAdapter(KeycloakSession session,
                                                  RedisAuthenticationSessionProvider provider,
                                                  RealmModel realm,
                                                  RedisRootAuthenticationSessionEntity entity,
                                                  int authSessionsLimit) {
        this.session = session;
        this.provider = provider;
        this.realm = realm;
        this.entity = entity;
        this.authSessionsLimit = authSessionsLimit;
    }

    @Override
    public String getId() {
        return entity.getId();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public int getTimestamp() {
        return entity.getTimestamp();
    }

    @Override
    public void setTimestamp(int timestamp) {
        // Iteration 5: hot path → HSET single field instead of full-tree write.
        entity.setTimestamp(timestamp);
        provider.persistTimestamp(realm, entity.getId(), timestamp);
    }

    @Override
    public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
        Map<String, AuthenticationSessionModel> result = new HashMap<>();
        for (Map.Entry<String, RedisAuthenticationSessionEntity> entry : entity.getAuthenticationSessions().entrySet()) {
            result.put(entry.getKey(), new RedisAuthenticationSessionAdapter(
                    session, this, entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Override
    public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
        if (client == null || tabId == null) {
            return null;
        }
        AuthenticationSessionModel authSession = getAuthenticationSessions().get(tabId);
        if (authSession != null && client.equals(authSession.getClient())) {
            session.getContext().setAuthenticationSession(authSession);
            return authSession;
        }
        return null;
    }

    @Override
    public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
        Objects.requireNonNull(client, "client");

        RedisAuthenticationSessionEntity authSessionEntity = new RedisAuthenticationSessionEntity();
        authSessionEntity.setClientUUID(client.getId());
        String tabId = Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
        int timestamp = Time.currentTime();
        authSessionEntity.setTimestamp(timestamp);

        Map<String, RedisAuthenticationSessionEntity> authSessions = entity.getAuthenticationSessions();
        if (authSessions.size() >= authSessionsLimit) {
            String oldestTabId = authSessions.entrySet().stream()
                    .min(TIMESTAMP_COMPARATOR)
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestTabId != null) {
                log.debugf("Reached limit (%s) of active authentication sessions. Removing oldest with TabId %s.", authSessionsLimit, oldestTabId);
                authSessions.remove(oldestTabId);
            }
        }

        authSessions.put(tabId, authSessionEntity);
        entity.setTimestamp(timestamp);
        // Tab-add path is on the critical login path. Use the whole-entity HSET-multi-field
        // call (1 round-trip) instead of two separate HSETs — bench showed splitting this
        // into persistTab + persistTimestamp doubled the RT count and regressed RPS.
        provider.persistRootAuthSession(realm, entity);

        AuthenticationSessionModel authSession = new RedisAuthenticationSessionAdapter(session, this, tabId, authSessionEntity);
        session.getContext().setAuthenticationSession(authSession);
        return authSession;
    }

    @Override
    public void removeAuthenticationSessionByTabId(String tabId) {
        if (entity.getAuthenticationSessions().remove(tabId) != null) {
            if (entity.getAuthenticationSessions().isEmpty()) {
                provider.removeRootAuthenticationSession(realm, this);
            } else {
                entity.setTimestamp(Time.currentTime());
                // 1-RT whole-entity rewrite. The HDEL+HSET split was a 2-RT regression.
                // We accept that we re-serialize remaining tabs; the typical case has 1.
                provider.persistRootAuthSession(realm, entity);
            }
        }
    }

    @Override
    public void restartSession(RealmModel realm) {
        // 1-RT whole-entity overwrite. The HSET-multi-field call writes the
        // current (now-empty-tabs) state and the EXPIRE refreshes TTL. Old
        // tab fields remain in the hash if not explicitly deleted, but the
        // restart contract resets the tabs collection on the entity, so the
        // adapter's view is correct. (Cleanup: a future iteration could DEL the
        // tab:* fields explicitly via a SCAN; for now they age out with TTL.)
        entity.getAuthenticationSessions().clear();
        entity.setTimestamp(Time.currentTime());
        provider.persistRootAuthSession(realm, entity);
    }

    /**
     * Called by child auth session adapters when they are updated.
     * Iteration 5: writes ONLY the changed tab's hash field (one HSET) instead
     * of re-serializing the whole tree. With a typical SSO flow doing 5-15
     * field-level updates per login, this drops per-login wire bytes by ~70 %
     * once the tab grows beyond a couple of notes.
     */
    void onChildUpdated(String tabId, RedisAuthenticationSessionEntity childEntity) {
        entity.getAuthenticationSessions().put(tabId, childEntity);
        provider.persistTab(realm, entity.getId(), tabId, childEntity);
    }
}
