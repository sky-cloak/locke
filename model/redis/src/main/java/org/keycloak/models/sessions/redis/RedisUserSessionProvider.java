package org.keycloak.models.sessions.redis;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.SessionExpirationUtils;

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

    private UserSessionPersisterProvider persister() {
        return session.getProvider(UserSessionPersisterProvider.class);
    }

    @Override
    public KeycloakSession getKeycloakSession() {
        return session;
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
        persister().createClientSession(clientSession, offline);
        AuthenticatedClientSessionModel loaded = persister().loadClientSession(realm, client, userSession, offline);
        if (loaded == null) {
            return null;
        }
        AuthenticatedClientSessionModel adapter = new AutoPersistingClientSessionAdapter(loaded, persister(), offline);
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
        if (clientSession == null || isClientSessionExpired(client.getRealm(), client, userSession, clientSession, offline)) {
            return null;
        }
        return clientSession;
    }

    // The persister loads client sessions unfiltered, so expiration is checked here
    // (upstream does this via cache expiration in the Infinispan layer).
    private static boolean isClientSessionExpired(RealmModel realm, ClientModel client, UserSessionModel userSession,
                                                  AuthenticatedClientSessionModel clientSession, boolean offline) {
        long now = Time.currentTimeMillis();
        boolean rememberMe = userSession.isRememberMe();
        long idleTimestamp = SessionExpirationUtils.calculateClientSessionIdleTimestamp(
                offline, rememberMe, TimeUnit.SECONDS.toMillis(clientSession.getTimestamp()), realm, client);
        if (idleTimestamp < now) {
            return true;
        }
        long started = clientSession.getTimestamp();
        String startedNote = clientSession.getNote(AuthenticatedClientSessionModel.STARTED_AT_NOTE);
        if (startedNote != null) {
            try {
                started = Long.parseLong(startedNote);
            } catch (NumberFormatException ignored) {
            }
        }
        long lifespanTimestamp = SessionExpirationUtils.calculateClientSessionMaxLifespanTimestamp(
                offline, rememberMe, TimeUnit.SECONDS.toMillis(started), TimeUnit.SECONDS.toMillis(userSession.getStarted()), realm, client);
        return lifespanTimestamp > 0 && lifespanTimestamp < now;
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

        // Create a persistent user session via the persister
        UserSessionModel userSessionModel = new PersistableUserSession(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
        persister().createUserSession(userSessionModel, false);
        return persister().loadUserSession(realm, id, false);
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        UserSessionModel transientSession = transientUserSessions.get(id);
        if (transientSession != null && transientSession.getRealm().getId().equals(realm.getId())) {
            return transientSession;
        }
        return persister().loadUserSession(realm, id, false);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        return persister().loadUserSessionsStream(realm, user, false, null, null);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        // Load all user sessions and filter by broker user ID
        return persister().loadUserSessionsStream(null, null, false, null)
                .filter(s -> brokerUserId.equals(s.getBrokerUserId()) && realm.getId().equals(s.getRealm().getId()));
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        return persister().loadUserSessionsStreamByBrokerSessionId(realm, brokerSessionId, false);
    }

    @Override
    public UserSessionModel getUserSessionWithPredicate(RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
        UserSessionModel userSession = offline
                ? persister().loadUserSession(realm, id, true)
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
        persister().createUserSession(userSession, true);
        return persister().loadUserSession(userSession.getRealm(), userSession.getId(), true);
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        return persister().loadUserSession(realm, userSessionId, true);
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        persister().removeUserSession(userSession.getId(), true);
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(AuthenticatedClientSessionModel clientSession,
                                                                       UserSessionModel offlineUserSession) {
        persister().createClientSession(clientSession, true);
        AuthenticatedClientSessionModel loaded = persister().loadClientSession(offlineUserSession.getRealm(),
                clientSession.getClient(), offlineUserSession, true);
        if (loaded != null) {
            // Same latched-map refresh as in createClientSession.
            offlineUserSession.getAuthenticatedClientSessions().put(clientSession.getClient().getId(), loaded);
        }
        return loaded;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return persister().loadUserSessionsStream(realm, user, true, null, null);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return persister().loadUserSessionsStream(null, null, true, null)
                .filter(s -> brokerUserId.equals(s.getBrokerUserId()) && realm.getId().equals(s.getRealm().getId()));
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        return persister().getUserSessionsCount(realm, client, true);
    }

    @Override
    public Stream<UserSessionModel> readOnlyStreamUserSessions(RealmModel realm, ClientModel client, int skip, int maxResults) {
        return persister().readOnlyUserSessionStream(realm, client, false, skip, maxResults);
    }

    @Override
    public Stream<UserSessionModel> readOnlyStreamOfflineUserSessions(RealmModel realm, ClientModel client, int skip, int maxResults) {
        return persister().readOnlyUserSessionStream(realm, client, true, skip, maxResults);
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
