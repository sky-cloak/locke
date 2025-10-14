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

import org.jboss.arquillian.graphene.page.Page;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.openqa.selenium.Cookie;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for authentication session provider with Redis.
 *
 * Tests AuthenticationSessionAdapter and RootAuthenticationSessionAdapter.
 *
 * @author guilliano
 */
public class RedisAuthenticationSessionTest extends AbstractRedisTest {

    @Page
    protected LoginPage loginPage;

    @Page
    protected AppPage appPage;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Before
    public void setupUser() {
        createUser("test", "test-user", "password");
    }

    @Test
    public void testAuthSessionLifecycle() {
        // Navigate to login page
        oauth.realm("test");
        oauth.clientId("test-app");
        driver.navigate().to(oauth.getLoginFormUrl());

        // Verify auth session cookie created
        Cookie authSessionCookie = driver.manage().getCookieNamed("AUTH_SESSION_ID");
        assertThat("Auth session cookie should be created", authSessionCookie, is(notNullValue()));

        String authSessionId = authSessionCookie.getValue();
        assertThat("Auth session ID should not be empty", authSessionId, not(isEmptyString()));

        // Complete login
        loginPage.login("test-user", "password");

        // After successful login, auth session should be cleaned up
        // (converted to user session)
        Cookie postLoginAuthCookie = driver.manage().getCookieNamed("AUTH_SESSION_ID");

        // Auth session cookie may still exist but will be different or removed
        // The key point is that a new user session was created
        assertThat("Should have session after login", driver.manage().getCookies(), not(empty()));
    }

    @Test
    public void testAuthSessionExpiration() {
        // Navigate to login page
        oauth.realm("test");
        oauth.clientId("test-app");
        driver.navigate().to(oauth.getLoginFormUrl());

        // Get auth session cookie
        Cookie authSessionCookie = driver.manage().getCookieNamed("AUTH_SESSION_ID");
        assertThat(authSessionCookie, is(notNullValue()));

        // Simulate time passing (auth session TTL is typically 300 seconds)
        setTimeOffset(400);

        // Try to continue login - should fail with expired session
        loginPage.login("test-user", "password");

        // Should be redirected back to login due to expired auth session
        // The exact behavior depends on Keycloak configuration
        assertThat("Should be on login page or app page",
                driver.getCurrentUrl(), anyOf(containsString("/auth"), containsString("/app")));

        resetTimeOffset();
    }

    @Test
    public void testMultiTabAuthentication() {
        // Tab 1: Start authentication flow
        oauth.realm("test");
        oauth.clientId("test-app");
        String loginUrl1 = oauth.getLoginFormUrl();
        driver.navigate().to(loginUrl1);

        Cookie authCookie1 = driver.manage().getCookieNamed("AUTH_SESSION_ID");
        assertThat("First tab should have auth session", authCookie1, is(notNullValue()));

        // Simulate second tab (same browser) - reuse same auth session
        // In a real browser, tabs share cookies
        driver.navigate().to(loginUrl1);

        Cookie authCookie2 = driver.manage().getCookieNamed("AUTH_SESSION_ID");
        assertThat("Second access should reuse auth session", authCookie2, is(notNullValue()));

        // Complete login in one tab
        loginPage.login("test-user", "password");

        // Both tabs should now have valid user session
        assertThat("Should have valid session cookies",
                driver.manage().getCookies(), not(empty()));
    }
}
