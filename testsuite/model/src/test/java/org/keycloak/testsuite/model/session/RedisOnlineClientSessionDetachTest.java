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
package org.keycloak.testsuite.model.session;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.testsuite.model.KeycloakModelTest;
import org.keycloak.testsuite.model.RequireProvider;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Detaching an <em>online</em> client session must actually remove it.
 *
 * <p>This is the invariant RFC 7009 revocation stands on:
 * {@code TokenRevocationEndpoint.revokeClientSession()} revokes a refresh token by detaching
 * the client session and then dropping the user session once it holds no more clients. It
 * never writes to the revoked-token store, so if the detach does not stick the revoked
 * refresh token keeps working until its natural expiry.
 *
 * <p>Upstream's {@code PersistentAuthenticatedClientSessionAdapter.detachFromUserSession()}
 * deletes the persisted row only for offline sessions; for online ones it just clears an
 * in-memory field, because upstream keeps online sessions in Infinispan rather than the
 * database. Locke delegates online sessions to that same JPA persister, so the row outlives
 * the detach and the next request loads the client session straight back.
 *
 * <p>Redis-only: it guards Locke's delegation, and the assertion would not hold for the
 * Infinispan provider, whose own adapter removes the entry from the cache.
 */
@RequireProvider(RedisConnectionProvider.class)
@RequireProvider(UserSessionProvider.class)
@RequireProvider(UserProvider.class)
@RequireProvider(RealmProvider.class)
public class RedisOnlineClientSessionDetachTest extends KeycloakModelTest {

    private String realmId;

    @Override
    public void createEnvironment(KeycloakSession s) {
        RealmModel realm = createRealm(s, "test-detach");
        s.getContext().setRealm(realm);
        realm.setSsoSessionMaxLifespan(Constants.DEFAULT_SESSION_MAX_LIFESPAN);
        realm.setSsoSessionIdleTimeout(Constants.DEFAULT_SESSION_IDLE_TIMEOUT);
        realm.setDefaultRole(s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
        this.realmId = realm.getId();

        s.users().addUser(realm, "user1").setEmail("user1@localhost");

        ClientModel client = s.clients().addClient(realm, "test-app");
        client.setEnabled(true);
        client.setSecret("password");
    }

    @Override
    public void cleanEnvironment(KeycloakSession s) {
        RealmModel realm = s.realms().getRealm(realmId);
        s.getContext().setRealm(realm);
        s.sessions().removeUserSessions(realm);

        UserModel user1 = s.users().getUserByUsername(realm, "user1");
        if (user1 != null) {
            new UserManager(s).removeUser(realm, user1);
        }
        s.realms().removeRealm(realmId);
    }

    @Test
    public void detachedOnlineClientSessionIsNotVisibleToLaterTransactions() {
        String userSessionId = inComittedTransaction(session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            UserModel user = session.users().getUserByUsername(realm, "user1");
            ClientModel client = realm.getClientByClientId("test-app");

            UserSessionModel userSession = session.sessions().createUserSession(null, realm, user, "user1",
                    "127.0.0.1", "form", false, null, null,
                    UserSessionModel.SessionPersistenceState.PERSISTENT);
            session.sessions().createClientSession(realm, client, userSession);
            return userSession.getId();
        });

        inComittedTransaction(session -> {
            assertThat("the client session should exist before the detach",
                    findClientSession(session, userSessionId), Matchers.notNullValue());
        });

        inComittedTransaction(session -> {
            AuthenticatedClientSessionModel clientSession = findClientSession(session, userSessionId);
            clientSession.detachFromUserSession();
        });

        inComittedTransaction(session -> {
            assertThat("a detached online client session must not come back",
                    findClientSession(session, userSessionId), Matchers.nullValue());
        });
    }

    /**
     * The second half of RFC 7009 revocation: once the detached client session was the last one,
     * {@code TokenRevocationEndpoint} drops the whole user session, which is what kills SSO. It
     * decides that with {@code userSession.getAuthenticatedClientSessions().isEmpty()} on the same
     * in-memory user session it just detached from — so that view has to reflect the detach.
     *
     * <p>The persister's client-session map is latched on first read
     * ({@code ClientSessionLoader}), so without filtering it still reports the detached session
     * and the user session survives. The observable effect is that the browser's SSO cookie keeps
     * minting fresh tokens for the revoked client without re-authentication.
     */
    @Test
    public void detachingTheLastClientSessionEmptiesTheUserSessionView() {
        String userSessionId = inComittedTransaction(session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            UserModel user = session.users().getUserByUsername(realm, "user1");
            ClientModel client = realm.getClientByClientId("test-app");

            UserSessionModel userSession = session.sessions().createUserSession(null, realm, user, "user1",
                    "127.0.0.1", "form", false, null, null,
                    UserSessionModel.SessionPersistenceState.PERSISTENT);
            session.sessions().createClientSession(realm, client, userSession);
            return userSession.getId();
        });

        // One transaction, mirroring the endpoint: resolve, detach, then re-check the same object.
        inComittedTransaction(session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            ClientModel client = realm.getClientByClientId("test-app");
            UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);

            AuthenticatedClientSessionModel clientSession =
                    userSession.getAuthenticatedClientSessionByClient(client.getId());
            clientSession.detachFromUserSession();

            assertThat("the detached client session must not still count towards the user session",
                    userSession.getAuthenticatedClientSessions().keySet(), Matchers.empty());
        });
    }

    private AuthenticatedClientSessionModel findClientSession(KeycloakSession session, String userSessionId) {
        RealmModel realm = session.realms().getRealm(realmId);
        session.getContext().setRealm(realm);
        ClientModel client = realm.getClientByClientId("test-app");
        UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
        return userSession == null ? null : userSession.getAuthenticatedClientSessionByClient(client.getId());
    }
}
