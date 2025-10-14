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

import org.apache.commons.lang.RandomStringUtils;
import org.junit.After;
import org.junit.Test;
import org.keycloak.representations.idm.RealmRepresentation;

import jakarta.ws.rs.NotFoundException;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test for realm cache invalidation with Redis.
 *
 * Tests that realm CRUD operations properly invalidate the Redis cache,
 * ensuring that cached realm data stays consistent with the database.
 *
 * @author guilliano
 */
public class RedisRealmInvalidationTest extends AbstractRedisTest {

    private String testRealmName;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        // No realms added by default - tests create their own
    }

    @After
    public void cleanupTestRealm() {
        if (testRealmName != null) {
            try {
                adminClient.realm(testRealmName).remove();
            } catch (NotFoundException e) {
                // Already deleted
            }
            testRealmName = null;
        }
    }

    @Test
    public void testRealmCRUD() {
        // CREATE
        RealmRepresentation realm = createTestRealmRepresentation();
        testRealmName = realm.getRealm();
        adminClient.realms().create(realm);

        // Verify realm is cached (first read hits DB, second should hit cache)
        RealmRepresentation cached1 = adminClient.realm(testRealmName).toRepresentation();
        assertThat(cached1, is(notNullValue()));
        assertThat(cached1.getRealm(), is(equalTo(testRealmName)));
        assertTrue(cached1.isEnabled());

        // Second read should come from cache
        RealmRepresentation cached2 = adminClient.realm(testRealmName).toRepresentation();
        assertEquals(cached1.getRealm(), cached2.getRealm());

        // UPDATE - should invalidate cache
        realm.setEnabled(false);
        realm.setDisplayName("Updated Display Name");
        adminClient.realm(testRealmName).update(realm);

        // Verify cache was invalidated
        RealmRepresentation updated = adminClient.realm(testRealmName).toRepresentation();
        assertFalse("Realm should be disabled after update", updated.isEnabled());
        assertEquals("Updated Display Name", updated.getDisplayName());

        // DELETE - should invalidate cache
        adminClient.realm(testRealmName).remove();

        // Verify cache was cleared
        try {
            adminClient.realm(testRealmName).toRepresentation();
            fail("Should throw NotFoundException after realm deletion");
        } catch (NotFoundException expected) {
            // Expected - realm no longer exists
        }

        testRealmName = null;
    }

    @Test
    public void testRealmAttributeUpdates() {
        // Create test realm
        RealmRepresentation realm = createTestRealmRepresentation();
        testRealmName = realm.getRealm();
        adminClient.realms().create(realm);

        // Test 1: SSL requirement update
        realm = adminClient.realm(testRealmName).toRepresentation();
        realm.setSslRequired("all");
        adminClient.realm(testRealmName).update(realm);

        RealmRepresentation updated = adminClient.realm(testRealmName).toRepresentation();
        assertEquals("all", updated.getSslRequired());

        // Test 2: Brute force protection toggle
        realm = adminClient.realm(testRealmName).toRepresentation();
        boolean originalBruteForce = realm.isBruteForceProtected();
        realm.setBruteForceProtected(!originalBruteForce);
        adminClient.realm(testRealmName).update(realm);

        updated = adminClient.realm(testRealmName).toRepresentation();
        assertEquals(!originalBruteForce, updated.isBruteForceProtected());

        // Test 3: Failure factor update
        realm = adminClient.realm(testRealmName).toRepresentation();
        realm.setBruteForceProtected(true);
        int originalFailureFactor = realm.getFailureFactor();
        realm.setFailureFactor(originalFailureFactor + 5);
        adminClient.realm(testRealmName).update(realm);

        updated = adminClient.realm(testRealmName).toRepresentation();
        assertEquals(originalFailureFactor + 5, updated.getFailureFactor().intValue());

        // Test 4: Token lifespan update
        realm = adminClient.realm(testRealmName).toRepresentation();
        realm.setAccessTokenLifespan(1200);
        adminClient.realm(testRealmName).update(realm);

        updated = adminClient.realm(testRealmName).toRepresentation();
        assertEquals(Integer.valueOf(1200), updated.getAccessTokenLifespan());
    }

    @Test
    public void testRealmRename() {
        // Create test realm
        RealmRepresentation realm = createTestRealmRepresentation();
        testRealmName = realm.getRealm();
        String originalName = testRealmName;
        adminClient.realms().create(realm);

        // Verify original realm exists
        RealmRepresentation original = adminClient.realm(originalName).toRepresentation();
        assertThat(original, is(notNullValue()));

        // Rename realm
        String newName = originalName + "_renamed";
        realm.setRealm(newName);
        adminClient.realm(originalName).update(realm);
        testRealmName = newName;

        // Verify old name is invalidated
        try {
            adminClient.realm(originalName).toRepresentation();
            fail("Old realm name should not exist after rename");
        } catch (NotFoundException expected) {
            // Expected
        }

        // Verify new name is accessible
        RealmRepresentation renamed = adminClient.realm(newName).toRepresentation();
        assertThat(renamed, is(notNullValue()));
        assertEquals(newName, renamed.getRealm());
    }

    @Test
    public void testRealmPublicKeyGeneration() {
        // Create test realm
        RealmRepresentation realm = createTestRealmRepresentation();
        testRealmName = realm.getRealm();
        adminClient.realms().create(realm);

        // Get initial public key
        realm = adminClient.realm(testRealmName).toRepresentation();
        String originalPublicKey = realm.getPublicKey();
        assertThat(originalPublicKey, is(notNullValue()));

        // Trigger key regeneration
        realm.setPublicKey("GENERATE");
        adminClient.realm(testRealmName).update(realm);

        // Verify new key was generated and cached
        RealmRepresentation updated = adminClient.realm(testRealmName).toRepresentation();
        assertNotEquals("GENERATE", updated.getPublicKey());
        assertNotEquals(originalPublicKey, updated.getPublicKey());
        assertThat(updated.getPublicKey(), is(notNullValue()));
    }

    @Test
    public void testMultipleRealmCache() {
        // Create multiple realms
        String realm1Name = "test_realm_1_" + RandomStringUtils.randomAlphabetic(5);
        String realm2Name = "test_realm_2_" + RandomStringUtils.randomAlphabetic(5);
        String realm3Name = "test_realm_3_" + RandomStringUtils.randomAlphabetic(5);

        try {
            RealmRepresentation realm1 = createTestRealmRepresentation(realm1Name);
            RealmRepresentation realm2 = createTestRealmRepresentation(realm2Name);
            RealmRepresentation realm3 = createTestRealmRepresentation(realm3Name);

            adminClient.realms().create(realm1);
            adminClient.realms().create(realm2);
            adminClient.realms().create(realm3);

            // Verify all realms are cached
            RealmRepresentation cached1 = adminClient.realm(realm1Name).toRepresentation();
            RealmRepresentation cached2 = adminClient.realm(realm2Name).toRepresentation();
            RealmRepresentation cached3 = adminClient.realm(realm3Name).toRepresentation();

            assertEquals(realm1Name, cached1.getRealm());
            assertEquals(realm2Name, cached2.getRealm());
            assertEquals(realm3Name, cached3.getRealm());

            // Update realm2 - should only invalidate realm2's cache
            realm2.setDisplayName("Realm 2 Updated");
            adminClient.realm(realm2Name).update(realm2);

            // Verify realm2 cache updated
            RealmRepresentation updated2 = adminClient.realm(realm2Name).toRepresentation();
            assertEquals("Realm 2 Updated", updated2.getDisplayName());

            // Verify realm1 and realm3 are still accessible (not affected)
            cached1 = adminClient.realm(realm1Name).toRepresentation();
            cached3 = adminClient.realm(realm3Name).toRepresentation();
            assertEquals(realm1Name, cached1.getRealm());
            assertEquals(realm3Name, cached3.getRealm());

        } finally {
            // Cleanup
            try { adminClient.realm(realm1Name).remove(); } catch (Exception e) {}
            try { adminClient.realm(realm2Name).remove(); } catch (Exception e) {}
            try { adminClient.realm(realm3Name).remove(); } catch (Exception e) {}
        }
    }

    // Helper methods

    private RealmRepresentation createTestRealmRepresentation() {
        return createTestRealmRepresentation("test_" + RandomStringUtils.randomAlphabetic(5));
    }

    private RealmRepresentation createTestRealmRepresentation(String realmName) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(realmName);
        realm.setEnabled(true);
        realm.setDisplayName("Test Realm " + realmName);
        realm.setAccessTokenLifespan(600);
        realm.setSsoSessionIdleTimeout(1800);
        realm.setSsoSessionMaxLifespan(36000);
        realm.setBruteForceProtected(false);
        realm.setFailureFactor(3);
        realm.setSslRequired("external");
        return realm;
    }
}
