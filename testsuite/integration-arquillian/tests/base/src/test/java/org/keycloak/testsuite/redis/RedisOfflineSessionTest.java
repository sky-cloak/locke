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
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.RefreshToken;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.util.OAuthClient;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for offline sessions with Redis.
 *
 * Tests offline token generation and persistence in Redis cache.
 *
 * @author guilliano
 */
public class RedisOfflineSessionTest extends AbstractRedisTest {

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        realm.setOfflineSessionIdleTimeout(2592000); // 30 days
        realm.setOfflineSessionMaxLifespan(5184000); // 60 days
        testRealms.add(realm);
    }

    @Test
    public void testOfflineTokenFlow() throws Exception {
        // Create user
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");
        oauth.scope(OAuth2Constants.OFFLINE_ACCESS);

        // Login and get offline token
        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        String code = resp.getCode();
        assertNotNull("Authorization code should be present", code);

        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(code, "password");
        assertEquals(200, tokenResp.getStatusCode());

        String offlineRefreshToken = tokenResp.getRefreshToken();
        assertNotNull("Refresh token should be present", offlineRefreshToken);

        // Verify it's an offline token
        RefreshToken refreshToken = oauth.parseRefreshToken(offlineRefreshToken);
        assertEquals(OAuth2Constants.OFFLINE_ACCESS, refreshToken.getType());

        // Verify offline session persists beyond normal idle timeout
        // Normal sessions expire at 1800 seconds, offline should survive much longer
        setTimeOffset(3600); // 1 hour later

        // Refresh with offline token - should still work
        OAuthClient.AccessTokenResponse refreshResponse = oauth.doRefreshTokenRequest(offlineRefreshToken, "password");
        assertEquals(200, refreshResponse.getStatusCode());

        AccessToken newAccessToken = oauth.verifyToken(refreshResponse.getAccessToken());
        assertNotNull("New access token should be valid", newAccessToken);

        resetTimeOffset();
    }

    @Test
    public void testOfflineSessionRevocation() throws Exception {
        // Create user
        String userId = createUser("test", "test-user", "password");

        oauth.realm("test");
        oauth.clientId("test-app");
        oauth.scope(OAuth2Constants.OFFLINE_ACCESS);

        // Get offline token
        OAuthClient.AuthorizationEndpointResponse resp = oauth.doLogin("test-user", "password");
        OAuthClient.AccessTokenResponse tokenResp = oauth.doAccessTokenRequest(resp.getCode(), "password");

        String offlineRefreshToken = tokenResp.getRefreshToken();

        // Verify offline token works
        OAuthClient.AccessTokenResponse refreshResp = oauth.doRefreshTokenRequest(offlineRefreshToken, "password");
        assertEquals(200, refreshResp.getStatusCode());

        // Revoke offline session
        oauth.doLogout(offlineRefreshToken, "password");

        // Try to use revoked offline token - should fail
        OAuthClient.AccessTokenResponse revokedRefreshResp = oauth.doRefreshTokenRequest(offlineRefreshToken, "password");
        assertNotNull("Should have error", revokedRefreshResp.getError());
    }
}
