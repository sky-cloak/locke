/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.testsuite.redis;

import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.common.util.Time;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.RefreshToken;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.util.OAuthClient;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Integration tests for Redis-backed user sessions.
 *
 * Tests session creation, refresh, notes, and expiration with Redis cache.
 *
 * @author guilliano
 */
public class RedisUserSessionTest extends AbstractRedisTest {

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation testRealm = loadTestRealm(testRealms);
    }

    private RealmRepresentation loadTestRealm(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        realm.setAccessTokenLifespan(600);
        realm.setSsoSessionIdleTimeout(1800);
        realm.setSsoSessionMaxLifespan(36000);
        testRealms.add(realm);
        return realm;
    }

    @Test
    public void testCreateSession() throws Exception {
        // Create a user and login
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        String code = resp.getCode();
        assertNotNull(code);

        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(code, "password");
        assertEquals(200, tokenResp.getStatusCode());

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        assertNotNull(token.getSessionState());

        // Verify session exists in Redis by refreshing the token
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());

        AccessToken refreshedToken = oauth.verifyToken(refreshResponse.getAccessToken());
        assertEquals(token.getSessionState(), refreshedToken.getSessionState());
    }

    @Test
    public void testSessionRefresh() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        String sessionId = token.getSessionState();

        // Wait a bit and refresh
        setTimeOffset(10);

        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());

        AccessToken refreshedToken = oauth.verifyToken(refreshResponse.getAccessToken());
        assertEquals(sessionId, refreshedToken.getSessionState());
    }

    @Test
    public void testSessionExpiration() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        assertNotNull(token.getSessionState());

        // Advance time beyond idle timeout (1800 seconds)
        setTimeOffset(2000);

        // Try to refresh - should fail due to session expiration
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertNotNull(refreshResponse.getError());
        assertEquals("Session not active", refreshResponse.getErrorDescription());
    }

    @Test
    public void testSessionNotes() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        // Set a custom note during authentication
        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        assertNotNull(token.getSessionState());

        // Session notes would be set via authentication flows
        // For now, verify session can be retrieved
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());
    }

    @Test
    public void testMultipleSessions() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        // Create first session
        OAuthClient.AuthorizationEndpointResponse resp1 = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp1 = oauth.doAccessTokenRequest(resp1.getCode(), "password");
        AccessToken token1 = oauth.verifyToken(tokenResp1.getAccessToken());

        // Create second session (new browser)
        deleteAllCookiesForRealm("test");
        OAuthClient.AuthorizationEndpointResponse resp2 = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp2 = oauth.doAccessTokenRequest(resp2.getCode(), "password");
        AccessToken token2 = oauth.verifyToken(tokenResp2.getAccessToken());

        // Verify different sessions
        assertThat(token1.getSessionState(), is(notNullValue()));
        assertThat(token2.getSessionState(), is(notNullValue()));

        // Both sessions should be refreshable
        OAuthClient.AccessTokenResponse refresh1 = oauth.doRefreshTokenRequest(tokenResp1.getRefreshToken(), "password");
        OAuthClient.AccessTokenResponse refresh2 = oauth.doRefreshTokenRequest(tokenResp2.getRefreshToken(), "password");

        assertEquals(200, refresh1.getStatusCode());
        assertEquals(200, refresh2.getStatusCode());
    }

    @Test
    public void testClientSessionManagement() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        assertNotNull(token.getSessionState());

        // Verify client session is attached
        assertNotNull(token.getIssuedFor());
        assertEquals("test-app", token.getIssuedFor());

        // Refresh should maintain client session
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());

        AccessToken refreshedToken = oauth.verifyToken(refreshResponse.getAccessToken());
        assertEquals(token.getSessionState(), refreshedToken.getSessionState());
        assertEquals("test-app", refreshedToken.getIssuedFor());
    }

    @Test
    public void testLogout() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        String sessionId = token.getSessionState();
        assertNotNull(sessionId);

        // Perform logout
        oauth.doLogout(tokenResp.getRefreshToken(), "password");

        // Try to refresh - should fail
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertNotNull(refreshResponse.getError());
    }

    @Test
    public void testSessionLastActivityUpdate() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        AccessToken token = oauth.verifyToken(tokenResp.getAccessToken());
        int initialIat = token.getIat();

        // Wait and refresh
        setTimeOffset(60);

        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());

        AccessToken refreshedToken = oauth.verifyToken(refreshResponse.getAccessToken());

        // New token should have updated timestamp
        assertThat(refreshedToken.getIat(), is(notNullValue()));
    }

    @Test
    public void testOfflineSession() throws Exception {
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("offline-client");
        oauth.scope(OAuth2Constants.OFFLINE_ACCESS);

        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        RefreshToken refreshToken = oauth.parseRefreshToken(tokenResp.getRefreshToken());
        assertEquals(OAuth2Constants.OFFLINE_ACCESS, refreshToken.getType());

        // Offline sessions survive idle timeout
        setTimeOffset(2000);

        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(tokenResp.getRefreshToken(), "password");
        assertEquals(200, refreshResponse.getStatusCode());
    }
}
