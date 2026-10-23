package org.keycloak.models.sessions.redis;

import java.sql.SQLException;
import java.util.Map;

import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Wrapper around an AuthenticatedClientSessionModel that auto-persists changes
 * back to the JPA store when mutable methods are called.
 *
 * The PersistentAuthenticatedClientSessionAdapter modifies notes in-memory but
 * doesn't flush changes to the JPA entity's data field. This wrapper detects
 * mutations and re-persists the client session so changes are visible on reload.
 *
 * <p>Writes run in their own short transaction against a freshly loaded row and are
 * retried on optimistic-lock conflicts. The wrapper never dirties an entity managed
 * by the caller's transaction — the client-session table is versioned, so a stale
 * entity flushed at commit turns concurrent requests into 500s.
 */
class AutoPersistingClientSessionAdapter implements AuthenticatedClientSessionModel {

    private static final int PERSIST_RETRIES = 10;

    private final AuthenticatedClientSessionModel delegate;
    private final KeycloakSessionFactory factory;
    private final boolean offline;
    // Shadows the timestamp: delegate.setTimestamp writes through to the outer-managed entity.
    private Integer timestampOverride;

    AutoPersistingClientSessionAdapter(AuthenticatedClientSessionModel delegate,
                                       KeycloakSessionFactory factory,
                                       boolean offline) {
        this.delegate = delegate;
        this.factory = factory;
        this.offline = offline;
    }

    private void persist() {
        for (int attempt = 0; ; attempt++) {
            try {
                KeycloakModelUtils.runJobInTransaction(factory,
                        s -> s.getProvider(UserSessionPersisterProvider.class).createClientSession(this, offline));
                return;
            } catch (RuntimeException e) {
                if (attempt >= PERSIST_RETRIES || !isRetryableConflict(e)) {
                    throw e;
                }
                backoff(attempt, e);
            }
        }
    }

    /**
     * Optimistic-lock and duplicate-key conflicts from concurrent writers, plus transient
     * connection-pool exhaustion (the short write transaction needs a second connection
     * while the caller's transaction holds one) — all safe to retry.
     */
    static boolean isRetryableConflict(Throwable t) {
        for (int depth = 0; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
            if (t instanceof org.hibernate.HibernateException
                    && t.getMessage() != null && t.getMessage().contains("connection pool")) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
        }
        return false;
    }

    static void backoff(int attempt, RuntimeException cause) {
        try {
            Thread.sleep(10L * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    @Override
    public String getId() {
        // Persisted client sessions have no standalone id (the store keys them by
        // user session + client); mirror PersistentAuthenticatedClientSessionAdapter.
        return null;
    }

    @Override
    public int getTimestamp() {
        return timestampOverride != null ? timestampOverride : delegate.getTimestamp();
    }

    @Override
    public void setTimestamp(int timestamp) {
        timestampOverride = timestamp;
        persist();
    }

    /**
     * Upstream's persister adapter deletes the stored row only for offline sessions; for online
     * ones it just clears an in-memory field, because upstream keeps online sessions in Infinispan
     * rather than the database. Locke persists them, so the row has to go too — otherwise the next
     * request loads the client session straight back and RFC 7009 revocation silently no-ops
     * ({@code TokenRevocationEndpoint.revokeClientSession()} revokes by detaching, never by
     * writing to the revoked-token store).
     */
    @Override
    public void detachFromUserSession() {
        UserSessionModel userSession = delegate.getUserSession();
        String userSessionId = userSession == null ? null : userSession.getId();
        ClientModel client = delegate.getClient();
        String clientUUID = client == null ? null : client.getId();

        delegate.detachFromUserSession();

        if (!offline && userSessionId != null && clientUUID != null) {
            removeFromStore(userSessionId, clientUUID);
        }
    }

    private void removeFromStore(String userSessionId, String clientUUID) {
        for (int attempt = 0; ; attempt++) {
            try {
                KeycloakModelUtils.runJobInTransaction(factory,
                        s -> s.getProvider(UserSessionPersisterProvider.class)
                                .removeClientSession(userSessionId, clientUUID, false));
                return;
            } catch (RuntimeException e) {
                if (attempt >= PERSIST_RETRIES || !isRetryableConflict(e)) {
                    throw e;
                }
                backoff(attempt, e);
            }
        }
    }

    @Override
    public UserSessionModel getUserSession() { return delegate.getUserSession(); }

    @Override
    public String getRedirectUri() { return delegate.getRedirectUri(); }

    @Override
    public void setRedirectUri(String uri) {
        delegate.setRedirectUri(uri);
        persist();
    }

    @Override
    public RealmModel getRealm() { return delegate.getRealm(); }

    @Override
    public ClientModel getClient() { return delegate.getClient(); }

    @Override
    public String getAction() { return delegate.getAction(); }

    @Override
    public void setAction(String action) {
        delegate.setAction(action);
        persist();
    }

    @Override
    public String getProtocol() { return delegate.getProtocol(); }

    @Override
    public void setProtocol(String method) {
        delegate.setProtocol(method);
        persist();
    }

    @Override
    public String getNote(String name) { return delegate.getNote(name); }

    @Override
    public void setNote(String name, String value) {
        delegate.setNote(name, value);
        persist();
    }

    @Override
    public void removeNote(String name) {
        delegate.removeNote(name);
        persist();
    }

    @Override
    public Map<String, String> getNotes() { return delegate.getNotes(); }
}
