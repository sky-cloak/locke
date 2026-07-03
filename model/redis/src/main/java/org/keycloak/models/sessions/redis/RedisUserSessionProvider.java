package org.keycloak.models.sessions.redis;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelIllegalStateException;
import org.keycloak.models.OfflineUserSessionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.session.PersistentUserSessionAdapter;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Redis user session provider that delegates to JPA persistent sessions.
 *
 * In KC26+, sessions are persisted in the database (source of truth) with
 * optional in-memory caching. This provider delegates all session operations
 * to the UserSessionPersisterProvider (JPA), which is the authoritative store.
 *
 * Redis is used for other SPI data (auth sessions, login failures, action tokens)
 * but user sessions are fully managed by JPA for consistency and durability.
 */
public class RedisUserSessionProvider implements UserSessionProvider {

    private static final Logger logger = Logger.getLogger(RedisUserSessionProvider.class);

    private final KeycloakSession session;
    private final int startupTime;
    // Transient sessions live only as long as this provider (= one request), like upstream.
    private final Map<String, UserSessionModel> transientUserSessions = new HashMap<>();

    public RedisUserSessionProvider(KeycloakSession session) {
        this.session = session;
        this.startupTime = Time.currentTime();
    }

    // Creation retries: mirrors upstream's retry-then-fail contract for conflicting creates.
    private static final int CREATE_RETRIES = 3;
    // Retries for the short separate write transactions (conflicts, transient pool pressure).
    private static final int WRITE_RETRIES = 10;

    private UserSessionPersisterProvider persister() {
        return session.getProvider(UserSessionPersisterProvider.class);
    }

    @Override
    public KeycloakSession getKeycloakSession() {
        return session;
    }

    /**
     * Wraps a persister-loaded user session; returns null when the session is expired
     * (idle or max-lifespan) — the load-time equivalent of upstream's cache expiration.
     */
    private UserSessionModel wrap(RealmModel realm, UserSessionModel loaded, boolean offline) {
        if (loaded == null) {
            return null;
        }
        if (RedisUserSessionAdapter.isUserSessionExpired(realm, loaded, offline)) {
            return null;
        }
        if (loaded instanceof RedisUserSessionAdapter) {
            return loaded;
        }
        return new RedisUserSessionAdapter((PersistentUserSessionAdapter) loaded, session.getKeycloakSessionFactory(), realm, offline);
    }

    /**
     * Runs a persister create in its own committed transaction so concurrent-create
     * conflicts can be handled without poisoning the caller's transaction (a failed
     * flush marks it rollback-only). Optimistic-lock conflicts (the create can take
     * the update path for an existing row) are retried.
     *
     * @return true when this call wrote the row, false when a concurrent insert won
     *         the race (duplicate key)
     */
    private boolean tryCreateInSeparateTx(Consumer<UserSessionPersisterProvider> job) {
        for (int attempt = 0; ; attempt++) {
            try {
                KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(),
                        s -> job.accept(s.getProvider(UserSessionPersisterProvider.class)));
                return true;
            } catch (RuntimeException e) {
                if (isConstraintViolation(e)) {
                    return false;
                }
                if (attempt >= WRITE_RETRIES || !AutoPersistingClientSessionAdapter.isRetryableConflict(e)) {
                    throw e;
                }
                AutoPersistingClientSessionAdapter.backoff(attempt, e);
            }
        }
    }

    /** True when the exception chain contains an integrity-constraint SQL error (SQLState 23xxx). */
    private static boolean isConstraintViolation(Throwable t) {
        for (int depth = 0; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String userIdOf(UserSessionModel userSession) {
        return userSession instanceof OfflineUserSessionModel offlineModel
                ? offlineModel.getUserId()
                : userSession.getUser().getId();
    }

    @Override
    public AuthenticatedClientSessionModel createClientSession(RealmModel realm, ClientModel client, UserSessionModel userSession) {
        AuthenticatedClientSessionModel existing = userSession.getAuthenticatedClientSessionByClient(client.getId());
        if (existing != null) {
            return existing;
        }
        // Create a transient client session model for persistence
        TransientClientSessionModel clientSession = new TransientClientSessionModel(
                UUID.randomUUID().toString(), client, userSession, realm, Time.currentTime());
        clientSession.setNote(AuthenticatedClientSessionModel.STARTED_AT_NOTE, String.valueOf(clientSession.getTimestamp()));
        clientSession.setNote(AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE, String.valueOf(userSession.getStarted()));
        if (userSession.isRememberMe()) {
            clientSession.setNote(AuthenticatedClientSessionModel.USER_SESSION_REMEMBER_ME_NOTE, "true");
        }

        if (userSession.getPersistenceState() == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            // Transient sessions must never touch the persister; keep the client session
            // in memory on the user session for the lifetime of this request.
            userSession.getAuthenticatedClientSessions().put(client.getId(), clientSession);
            return clientSession;
        }

        boolean offline = userSession.isOffline();
        // If a concurrent request won the insert race the row already carries equivalent
        // data; either way the freshly-built model below reflects the intended state.
        // Reloading through this session's persistence context could return a stale
        // managed entity (e.g. when re-creating a client session that expired earlier
        // in this transaction), so the in-memory model is the safer source.
        tryCreateInSeparateTx(p -> p.createClientSession(clientSession, offline));
        AuthenticatedClientSessionModel adapter =
                new AutoPersistingClientSessionAdapter(clientSession, session.getKeycloakSessionFactory(), offline);
        // The persister adapter latches its client-session map on first read (the
        // existing-check above), so register the new session there too or later reads
        // through this user session model won't see it.
        userSession.getAuthenticatedClientSessions().put(client.getId(), adapter);
        return adapter;
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(UserSessionModel userSession, ClientModel client, boolean offline) {
        if (userSession.getPersistenceState() == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            // Look up via this provider's registry, not the (possibly long-lived) model
            // object — transient sessions must not be visible outside their request.
            UserSessionModel local = transientUserSessions.get(userSession.getId());
            return local == null ? null : local.getAuthenticatedClientSessionByClient(client.getId());
        }
        AuthenticatedClientSessionModel clientSession = userSession.getAuthenticatedClientSessionByClient(client.getId());
        if (clientSession == null
                || RedisUserSessionAdapter.isClientSessionExpired(client.getRealm(), client, userSession, clientSession, offline)) {
            return null;
        }
        if (clientSession instanceof AutoPersistingClientSessionAdapter) {
            return clientSession;
        }
        return new AutoPersistingClientSessionAdapter(clientSession, session.getKeycloakSessionFactory(), offline);
    }

    @Override
    public UserSessionModel createUserSession(String id, RealmModel realm, UserModel user, String loginUsername,
                                                String ipAddress, String authMethod, boolean rememberMe,
                                                String brokerSessionId, String brokerUserId,
                                                UserSessionModel.SessionPersistenceState persistenceState) {
        if (id == null) {
            id = SecretGenerator.getInstance().randomString();
        }

        if (persistenceState == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            UserSessionModel userSession = new TransientUserSessionModel(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
            transientUserSessions.put(id, userSession);
            return userSession;
        }

        UserSessionModel userSessionModel = new PersistableUserSession(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
        for (int attempt = 0; attempt <= CREATE_RETRIES; attempt++) {
            UserSessionModel existing = persister().loadUserSession(realm, id, false);
            if (existing != null) {
                if (!user.getId().equals(userIdOf(existing))) {
                    // Same contract as upstream's conflicting-create retry loop.
                    if (attempt == CREATE_RETRIES) {
                        throw new RuntimeException("Maximum number of retries reached",
                                new ModelIllegalStateException("User ID of the session does not match, the user ID should not change"));
                    }
                    continue;
                }
                // Concurrently created for the same user: merge — mutations on the returned
                // adapter update the existing row.
                return wrap(realm, existing, false);
            }
            if (!tryCreateInSeparateTx(p -> p.createUserSession(userSessionModel, false))) {
                continue; // lost the race — reload and merge
            }
            UserSessionModel loaded = wrap(realm, persister().loadUserSession(realm, id, false), false);
            if (loaded != null) {
                return loaded;
            }
        }
        throw new IllegalStateException("Unable to create user session " + id);
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        UserSessionModel transientSession = transientUserSessions.get(id);
        if (transientSession != null && transientSession.getRealm().getId().equals(realm.getId())) {
            return transientSession;
        }
        return wrap(realm, persister().loadUserSession(realm, id, false), false);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        return persister().loadUserSessionsStream(realm, user, false, null, null)
                .map(s -> wrap(realm, s, false))
                .filter(Objects::nonNull);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        // Load all user sessions and filter by broker user ID
        return persister().loadUserSessionsStream(null, null, false, null)
                .filter(s -> brokerUserId.equals(s.getBrokerUserId()) && realm.getId().equals(s.getRealm().getId()))
                .map(s -> wrap(realm, s, false))
                .filter(Objects::nonNull);
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        return wrap(realm, persister().loadUserSessionsStreamByBrokerSessionId(realm, brokerSessionId, false), false);
    }

    @Override
    public UserSessionModel getUserSessionWithPredicate(RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
        UserSessionModel userSession = offline
                ? getOfflineUserSession(realm, id)
                : getUserSession(realm, id);
        if (userSession != null && predicate.test(userSession)) {
            return userSession;
        }
        return null;
    }

    @Override
    public long getActiveUserSessions(RealmModel realm, ClientModel client) {
        return persister().getUserSessionsCount(realm, client, false);
    }

    @Override
    public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
        return persister().getUserSessionsCountsByClients(realm, offline);
    }

    @Override
    public void removeUserSession(RealmModel realm, UserSessionModel session) {
        if (session.getPersistenceState() == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            transientUserSessions.remove(session.getId());
            return;
        }
        persister().removeUserSession(session.getId(), false);
    }

    @Override
    public void removeUserSessions(RealmModel realm, UserModel user) {
        persister().onUserRemoved(realm, user);
    }

    @Override
    public void removeUserSessions(RealmModel realm) {
        persister().removeUserSessions(realm);
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        persister().onRealmRemoved(realm);
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        persister().onClientRemoved(realm, client);
    }

    @Override
    public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
        RealmModel realm = userSession.getRealm();
        UserSessionModel existing = persister().loadUserSession(realm, userSession.getId(), true);
        if (existing == null) {
            // A lost duplicate-key race means a concurrent request created the offline copy.
            tryCreateInSeparateTx(p -> p.createUserSession(userSession, true));
            existing = persister().loadUserSession(realm, userSession.getId(), true);
        }
        return wrap(realm, existing, true);
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        return wrap(realm, persister().loadUserSession(realm, userSessionId, true), true);
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        persister().removeUserSession(userSession.getId(), true);
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(AuthenticatedClientSessionModel clientSession,
                                                                       UserSessionModel offlineUserSession) {
        RealmModel realm = offlineUserSession.getRealm();
        // Snapshot the online client session into a model bound to the offline user session;
        // as in createClientSession, the in-memory model avoids stale persistence-context reads.
        TransientClientSessionModel copy = new TransientClientSessionModel(clientSession.getId(),
                clientSession.getClient(), offlineUserSession, realm, clientSession.getTimestamp());
        copy.setAction(clientSession.getAction());
        copy.setProtocol(clientSession.getProtocol());
        copy.setRedirectUri(clientSession.getRedirectUri());
        Map<String, String> notes = clientSession.getNotes();
        if (notes != null) {
            copy.getNotes().putAll(notes);
        }

        tryCreateInSeparateTx(p -> p.createClientSession(copy, true));
        AuthenticatedClientSessionModel adapter =
                new AutoPersistingClientSessionAdapter(copy, session.getKeycloakSessionFactory(), true);
        // Same latched-map refresh as in createClientSession.
        offlineUserSession.getAuthenticatedClientSessions().put(clientSession.getClient().getId(), adapter);
        return adapter;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return persister().loadUserSessionsStream(realm, user, true, null, null)
                .map(s -> wrap(realm, s, true))
                .filter(Objects::nonNull);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return persister().loadUserSessionsStream(null, null, true, null)
                .filter(s -> brokerUserId.equals(s.getBrokerUserId()) && realm.getId().equals(s.getRealm().getId()))
                .map(s -> wrap(realm, s, true))
                .filter(Objects::nonNull);
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        return persister().getUserSessionsCount(realm, client, true);
    }

    @Override
    public Stream<UserSessionModel> readOnlyStreamUserSessions(RealmModel realm, ClientModel client, int skip, int maxResults) {
        return persister().readOnlyUserSessionStream(realm, client, false, skip, maxResults)
                .filter(s -> !RedisUserSessionAdapter.isUserSessionExpired(realm, s, false));
    }

    @Override
    public Stream<UserSessionModel> readOnlyStreamOfflineUserSessions(RealmModel realm, ClientModel client, int skip, int maxResults) {
        return persister().readOnlyUserSessionStream(realm, client, true, skip, maxResults)
                .filter(s -> !RedisUserSessionAdapter.isUserSessionExpired(realm, s, true));
    }

    @Override
    public int getStartupTime(RealmModel realm) {
        return startupTime;
    }

    @Override
    public void close() {
    }

    /**
     * A minimal UserSessionModel implementation used to pass data to the persister for creation.
     * After creation, sessions are always loaded from the persister which returns its own model.
     */
    private static class PersistableUserSession implements UserSessionModel {
        private final String id;
        private final RealmModel realm;
        private final UserModel user;
        private final String loginUsername;
        private final String ipAddress;
        private final String authMethod;
        private final boolean rememberMe;
        private final String brokerSessionId;
        private final String brokerUserId;
        private final int started;

        PersistableUserSession(String id, RealmModel realm, UserModel user, String loginUsername,
                                String ipAddress, String authMethod, boolean rememberMe,
                                String brokerSessionId, String brokerUserId) {
            this.id = id;
            this.realm = realm;
            this.user = user;
            this.loginUsername = loginUsername;
            this.ipAddress = ipAddress;
            this.authMethod = authMethod;
            this.rememberMe = rememberMe;
            this.brokerSessionId = brokerSessionId;
            this.brokerUserId = brokerUserId;
            this.started = Time.currentTime();
        }

        @Override public String getId() { return id; }
        @Override public RealmModel getRealm() { return realm; }
        @Override public String getBrokerSessionId() { return brokerSessionId; }
        @Override public String getBrokerUserId() { return brokerUserId; }
        @Override public UserModel getUser() { return user; }
        @Override public String getLoginUsername() { return loginUsername; }
        @Override public String getIpAddress() { return ipAddress; }
        @Override public String getAuthMethod() { return authMethod; }
        @Override public boolean isRememberMe() { return rememberMe; }
        @Override public int getStarted() { return started; }
        @Override public int getLastSessionRefresh() { return started; }
        @Override public void setLastSessionRefresh(int seconds) { }
        @Override public boolean isOffline() { return false; }
        @Override public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() { return Map.of(); }
        @Override public AuthenticatedClientSessionModel getAuthenticatedClientSessionByClient(String clientUUID) { return null; }
        @Override public void removeAuthenticatedClientSessions(Collection<String> removedClientUUIDs) { }
        @Override public String getNote(String name) { return null; }
        @Override public void setNote(String name, String value) { }
        @Override public void removeNote(String name) { }
        @Override public Map<String, String> getNotes() { return Map.of(); }
        @Override public State getState() { return State.LOGGED_IN; }
        @Override public void setState(State state) { }
        @Override public void restartSession(RealmModel realm, UserModel user, String loginUsername, String ipAddress, String authMethod, boolean rememberMe, String brokerSessionId, String brokerUserId) { }
    }

    /**
     * Transient client session model used to pass data to the persister for creation.
     */
    private static class TransientClientSessionModel implements AuthenticatedClientSessionModel {
        private final String id;
        private final ClientModel client;
        private final UserSessionModel userSession;
        private final RealmModel realm;
        private int timestamp;
        private final Map<String, String> notes = new java.util.HashMap<>();
        private String redirectUri;
        private String action;
        private String protocol;

        TransientClientSessionModel(String id, ClientModel client, UserSessionModel userSession, RealmModel realm, int timestamp) {
            this.id = id;
            this.client = client;
            this.userSession = userSession;
            this.realm = realm;
            this.timestamp = timestamp;
        }

        @Override public String getId() { return id; }
        @Override public int getTimestamp() { return timestamp; }
        @Override public void setTimestamp(int timestamp) { this.timestamp = timestamp; }
        @Override public void detachFromUserSession() { }
        @Override public UserSessionModel getUserSession() { return userSession; }
        @Override public String getRedirectUri() { return redirectUri; }
        @Override public void setRedirectUri(String uri) { this.redirectUri = uri; }
        @Override public RealmModel getRealm() { return realm; }
        @Override public ClientModel getClient() { return client; }
        @Override public String getAction() { return action; }
        @Override public void setAction(String action) { this.action = action; }
        @Override public String getProtocol() { return protocol; }
        @Override public void setProtocol(String method) { this.protocol = method; }
        @Override public String getNote(String name) { return notes.get(name); }
        @Override public void setNote(String name, String value) { if (value != null) notes.put(name, value); else notes.remove(name); }
        @Override public void removeNote(String name) { notes.remove(name); }
        @Override public Map<String, String> getNotes() { return notes; }
    }

    /**
     * Transient user session that is never persisted. Used for service accounts
     * (client_credentials) and similar. Notes, state and client sessions are held
     * in memory so protocol mappers and token endpoints can read back what they
     * wrote during the request.
     */
    private static class TransientUserSessionModel extends PersistableUserSession {
        private final Map<String, AuthenticatedClientSessionModel> clientSessions = new HashMap<>();
        private final Map<String, String> notes = new HashMap<>();
        private State state = State.LOGGED_IN;
        private int lastSessionRefresh;

        TransientUserSessionModel(String id, RealmModel realm, UserModel user, String loginUsername,
                                    String ipAddress, String authMethod, boolean rememberMe,
                                    String brokerSessionId, String brokerUserId) {
            super(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
            this.lastSessionRefresh = getStarted();
        }

        @Override public SessionPersistenceState getPersistenceState() { return SessionPersistenceState.TRANSIENT; }
        @Override public int getLastSessionRefresh() { return lastSessionRefresh; }
        @Override public void setLastSessionRefresh(int seconds) { this.lastSessionRefresh = seconds; }
        @Override public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() { return clientSessions; }
        @Override public AuthenticatedClientSessionModel getAuthenticatedClientSessionByClient(String clientUUID) { return clientSessions.get(clientUUID); }
        @Override public void removeAuthenticatedClientSessions(Collection<String> removedClientUUIDs) {
            if (removedClientUUIDs != null) {
                removedClientUUIDs.forEach(clientSessions::remove);
            }
        }
        @Override public String getNote(String name) { return notes.get(name); }
        @Override public void setNote(String name, String value) {
            if (value == null) {
                notes.remove(name);
            } else {
                notes.put(name, value);
            }
        }
        @Override public void removeNote(String name) { notes.remove(name); }
        @Override public Map<String, String> getNotes() { return notes; }
        @Override public State getState() { return state; }
        @Override public void setState(State state) { this.state = state; }
    }
}
