package org.keycloak.models.sessions.redis;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
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

        boolean offline = userSession.isOffline();
        persister().createClientSession(clientSession, offline);
        AuthenticatedClientSessionModel loaded = persister().loadClientSession(realm, client, userSession, offline);
        return loaded != null ? new AutoPersistingClientSessionAdapter(loaded, persister(), offline) : null;
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(UserSessionModel userSession, ClientModel client, boolean offline) {
        return userSession.getAuthenticatedClientSessionByClient(client.getId());
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
            return new TransientUserSessionModel(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
        }

        // Create a persistent user session via the persister
        UserSessionModel userSessionModel = new PersistableUserSession(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
        persister().createUserSession(userSessionModel, false);
        return persister().loadUserSession(realm, id, false);
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
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
                : persister().loadUserSession(realm, id, false);
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
        return persister().loadClientSession(offlineUserSession.getRealm(),
                clientSession.getClient(), offlineUserSession, true);
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
     * Transient user session that is never persisted. Used for service accounts and similar.
     */
    private static class TransientUserSessionModel extends PersistableUserSession {
        TransientUserSessionModel(String id, RealmModel realm, UserModel user, String loginUsername,
                                    String ipAddress, String authMethod, boolean rememberMe,
                                    String brokerSessionId, String brokerUserId) {
            super(id, realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
        }
    }
}
