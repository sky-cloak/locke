/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.sessions.redis;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OfflineUserSessionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.session.PersistentUserSessionAdapter;
import org.keycloak.models.utils.SessionExpirationUtils;

/**
 * Wraps a persister-loaded user session to give it the semantics upstream gets from the
 * Infinispan cache layer:
 * <ul>
 *   <li>expired client sessions are filtered out of the authenticated-client-sessions map
 *       on access (idle + max-lifespan, remember-me aware);</li>
 *   <li>client sessions are wrapped in {@link AutoPersistingClientSessionAdapter} so note
 *       and timestamp mutations reach the database;</li>
 *   <li>user-session data mutations (notes, state) are flushed back to the managed JPA
 *       entity, which {@link PersistentUserSessionAdapter} alone does not do.</li>
 * </ul>
 */
class RedisUserSessionAdapter implements OfflineUserSessionModel {

    private final PersistentUserSessionAdapter delegate;
    private final KeycloakSessionFactory factory;
    private final RealmModel realm;
    private final boolean offline;

    RedisUserSessionAdapter(PersistentUserSessionAdapter delegate, KeycloakSessionFactory factory,
                            RealmModel realm, boolean offline) {
        this.delegate = delegate;
        this.factory = factory;
        this.realm = realm;
        this.offline = offline;
    }

    /** Serializes the in-memory data (notes, state) into the managed JPA entity. */
    private void flushData() {
        delegate.getUpdatedModel();
    }

    static boolean isUserSessionExpired(RealmModel realm, UserSessionModel userSession, boolean offline) {
        long now = Time.currentTimeMillis();
        long idle = SessionExpirationUtils.calculateUserSessionIdleTimestamp(
                offline, userSession.isRememberMe(), TimeUnit.SECONDS.toMillis(userSession.getLastSessionRefresh()), realm);
        if (idle < now) {
            return true;
        }
        long lifespan = SessionExpirationUtils.calculateUserSessionMaxLifespanTimestamp(
                offline, userSession.isRememberMe(), TimeUnit.SECONDS.toMillis(userSession.getStarted()), realm);
        return lifespan > 0 && lifespan < now;
    }

    // The persister loads client sessions unfiltered, so expiration is checked here
    // (upstream does this via cache expiration in the Infinispan layer).
    static boolean isClientSessionExpired(RealmModel realm, ClientModel client, UserSessionModel userSession,
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
    public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() {
        Map<String, AuthenticatedClientSessionModel> map = delegate.getAuthenticatedClientSessions();
        map.entrySet().removeIf(entry -> {
            ClientModel client = entry.getValue().getClient();
            return client == null || isClientSessionExpired(realm, client, this, entry.getValue(), offline);
        });
        for (Map.Entry<String, AuthenticatedClientSessionModel> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof AutoPersistingClientSessionAdapter)) {
                entry.setValue(new AutoPersistingClientSessionAdapter(entry.getValue(), factory, offline));
            }
        }
        return map;
    }

    @Override
    public void removeAuthenticatedClientSessions(Collection<String> removedClientUUIDs) {
        delegate.removeAuthenticatedClientSessions(removedClientUUIDs);
    }

    @Override
    public String getUserId() {
        return delegate.getUserId();
    }

    @Override
    public void setLoginUsername(String loginUsername) {
        delegate.setLoginUsername(loginUsername);
        flushData();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public String getBrokerSessionId() {
        return delegate.getBrokerSessionId();
    }

    @Override
    public String getBrokerUserId() {
        return delegate.getBrokerUserId();
    }

    @Override
    public UserModel getUser() {
        return delegate.getUser();
    }

    @Override
    public String getLoginUsername() {
        return delegate.getLoginUsername();
    }

    @Override
    public String getIpAddress() {
        return delegate.getIpAddress();
    }

    @Override
    public String getAuthMethod() {
        return delegate.getAuthMethod();
    }

    @Override
    public boolean isRememberMe() {
        return delegate.isRememberMe();
    }

    @Override
    public int getStarted() {
        return delegate.getStarted();
    }

    @Override
    public int getLastSessionRefresh() {
        return delegate.getLastSessionRefresh();
    }

    @Override
    public void setLastSessionRefresh(int seconds) {
        // Writes through to the managed entity; no data flush needed.
        delegate.setLastSessionRefresh(seconds);
    }

    @Override
    public boolean isOffline() {
        return delegate.isOffline();
    }

    @Override
    public String getNote(String name) {
        return delegate.getNote(name);
    }

    @Override
    public void setNote(String name, String value) {
        delegate.setNote(name, value);
        flushData();
    }

    @Override
    public void removeNote(String name) {
        delegate.removeNote(name);
        flushData();
    }

    @Override
    public Map<String, String> getNotes() {
        return delegate.getNotes();
    }

    @Override
    public State getState() {
        return delegate.getState();
    }

    @Override
    public void setState(State state) {
        delegate.setState(state);
        flushData();
    }

    @Override
    public SessionPersistenceState getPersistenceState() {
        return delegate.getPersistenceState();
    }

    @Override
    public void restartSession(RealmModel realm, UserModel user, String loginUsername, String ipAddress,
                               String authMethod, boolean rememberMe, String brokerSessionId, String brokerUserId) {
        delegate.restartSession(realm, user, loginUsername, ipAddress, authMethod, rememberMe, brokerSessionId, brokerUserId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof UserSessionModel that && that.getId().equals(getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public String toString() {
        return getId();
    }
}
