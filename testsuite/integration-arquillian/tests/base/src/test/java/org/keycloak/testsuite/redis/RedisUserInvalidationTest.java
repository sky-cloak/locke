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
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.admin.ApiUtil;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test for user cache invalidation with Redis.
 *
 * Tests that user CRUD operations properly invalidate the Redis cache,
 * ensuring that cached user data stays consistent with the database.
 *
 * @author guilliano
 */
public class RedisUserInvalidationTest extends AbstractRedisTest {

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Test
    public void testUserCRUD() {
        // CREATE
        UserRepresentation user = createTestUserRepresentation();
        Response response = adminClient.realm("test").users().create(user);
        String userId = ApiUtil.getCreatedId(response);
        response.close();
        user.setId(userId);

        // Verify user is cached
        UserRepresentation cached = adminClient.realm("test").users().get(userId).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(user.getUsername(), cached.getUsername());
        assertEquals(user.getEmail(), cached.getEmail());
        assertTrue(cached.isEnabled());

        // Second read should come from cache
        UserRepresentation cached2 = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals(cached.getUsername(), cached2.getUsername());

        // UPDATE - should invalidate cache
        user.setFirstName(user.getFirstName() + "_updated");
        user.setLastName(user.getLastName() + "_updated");
        user.setEnabled(false);
        adminClient.realm("test").users().get(userId).update(user);

        // Verify cache was invalidated
        UserRepresentation updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals(user.getFirstName(), updated.getFirstName());
        assertEquals(user.getLastName(), updated.getLastName());
        assertFalse("User should be disabled after update", updated.isEnabled());

        // DELETE - should invalidate cache
        adminClient.realm("test").users().get(userId).remove();

        // Verify cache was cleared
        try {
            adminClient.realm("test").users().get(userId).toRepresentation();
            fail("Should throw NotFoundException after user deletion");
        } catch (NotFoundException expected) {
            // Expected - user no longer exists
        }
    }

    @Test
    public void testUserAttributeUpdates() {
        // Create test user
        UserRepresentation user = createTestUserRepresentation();
        String userId = createUser("test", user.getUsername(), "password",
                user.getFirstName(), user.getLastName(), user.getEmail());

        // Test 1: Update email
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        String newEmail = "updated_" + user.getEmail();
        user.setEmail(newEmail);
        adminClient.realm("test").users().get(userId).update(user);

        UserRepresentation updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals(newEmail, updated.getEmail());

        // Test 2: Update username
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        String newUsername = user.getUsername() + "_updated";
        user.setUsername(newUsername);
        adminClient.realm("test").users().get(userId).update(user);

        updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals(newUsername, updated.getUsername());

        // Test 3: Update email verification status
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        user.setEmailVerified(true);
        adminClient.realm("test").users().get(userId).update(user);

        updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertTrue(updated.isEmailVerified());

        // Test 4: Update custom attributes
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("customAttribute1", Arrays.asList("value1", "value2"));
        attributes.put("customAttribute2", Arrays.asList("value3"));
        user.setAttributes(attributes);
        adminClient.realm("test").users().get(userId).update(user);

        updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertThat(updated.getAttributes(), hasEntry("customAttribute1", Arrays.asList("value1", "value2")));
        assertThat(updated.getAttributes(), hasEntry("customAttribute2", Arrays.asList("value3")));
    }

    @Test
    public void testUserPasswordChange() {
        // Create test user
        UserRepresentation user = createTestUserRepresentation();
        String userId = createUser("test", user.getUsername(), "password",
                user.getFirstName(), user.getLastName(), user.getEmail());

        // Update password
        CredentialRepresentation newPassword = new CredentialRepresentation();
        newPassword.setType(CredentialRepresentation.PASSWORD);
        newPassword.setValue("newPassword123");
        newPassword.setTemporary(false);

        UserResource userResource = adminClient.realm("test").users().get(userId);
        userResource.resetPassword(newPassword);

        // Verify cache was invalidated by reading user again
        UserRepresentation updatedUser = userResource.toRepresentation();
        assertThat(updatedUser, is(notNullValue()));
        assertEquals(userId, updatedUser.getId());

        // Verify old password doesn't work and new password works
        // This would require OAuth2 testing which is covered in RedisUserSessionTest
    }

    @Test
    public void testUserRequiredActions() {
        // Create test user
        UserRepresentation user = createTestUserRepresentation();
        String userId = createUser("test", user.getUsername(), "password",
                user.getFirstName(), user.getLastName(), user.getEmail());

        // Add required actions
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        user.setRequiredActions(Arrays.asList("UPDATE_PASSWORD", "VERIFY_EMAIL"));
        adminClient.realm("test").users().get(userId).update(user);

        // Verify cache was invalidated
        UserRepresentation updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals(2, updated.getRequiredActions().size());
        assertTrue(updated.getRequiredActions().contains("UPDATE_PASSWORD"));
        assertTrue(updated.getRequiredActions().contains("VERIFY_EMAIL"));

        // Remove required actions
        user = adminClient.realm("test").users().get(userId).toRepresentation();
        user.setRequiredActions(Arrays.asList());
        adminClient.realm("test").users().get(userId).update(user);

        updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertTrue(updated.getRequiredActions().isEmpty());
    }

    @Test
    public void testMultipleUserCache() {
        // Create multiple users
        UserRepresentation user1 = createTestUserRepresentation();
        UserRepresentation user2 = createTestUserRepresentation();
        UserRepresentation user3 = createTestUserRepresentation();

        String userId1 = createUser("test", user1.getUsername(), "password",
                user1.getFirstName(), user1.getLastName(), user1.getEmail());
        String userId2 = createUser("test", user2.getUsername(), "password",
                user2.getFirstName(), user2.getLastName(), user2.getEmail());
        String userId3 = createUser("test", user3.getUsername(), "password",
                user3.getFirstName(), user3.getLastName(), user3.getEmail());

        // Verify all users are cached
        UserRepresentation cached1 = adminClient.realm("test").users().get(userId1).toRepresentation();
        UserRepresentation cached2 = adminClient.realm("test").users().get(userId2).toRepresentation();
        UserRepresentation cached3 = adminClient.realm("test").users().get(userId3).toRepresentation();

        assertEquals(user1.getUsername(), cached1.getUsername());
        assertEquals(user2.getUsername(), cached2.getUsername());
        assertEquals(user3.getUsername(), cached3.getUsername());

        // Update user2 - should only invalidate user2's cache
        user2 = cached2;
        user2.setFirstName("Updated First Name");
        adminClient.realm("test").users().get(userId2).update(user2);

        // Verify user2 cache updated
        UserRepresentation updated2 = adminClient.realm("test").users().get(userId2).toRepresentation();
        assertEquals("Updated First Name", updated2.getFirstName());

        // Verify user1 and user3 are still accessible (not affected)
        cached1 = adminClient.realm("test").users().get(userId1).toRepresentation();
        cached3 = adminClient.realm("test").users().get(userId3).toRepresentation();
        assertEquals(user1.getUsername(), cached1.getUsername());
        assertEquals(user3.getUsername(), cached3.getUsername());
    }

    @Test
    public void testUserSearch() {
        // Create users with searchable attributes
        String uniquePrefix = "searchtest_" + RandomStringUtils.randomAlphabetic(5);

        String userId1 = createUser("test", uniquePrefix + "_user1", "password",
                "John", "Doe", uniquePrefix + "_user1@example.com");
        String userId2 = createUser("test", uniquePrefix + "_user2", "password",
                "Jane", "Doe", uniquePrefix + "_user2@example.com");
        String userId3 = createUser("test", uniquePrefix + "_user3", "password",
                "Bob", "Smith", uniquePrefix + "_user3@example.com");

        // Search by username prefix
        List<UserRepresentation> searchResults = adminClient.realm("test").users()
                .search(uniquePrefix, 0, 10);

        assertThat(searchResults.size(), is(equalTo(3)));

        // Search by first name
        searchResults = adminClient.realm("test").users()
                .search("John", 0, 10);

        assertTrue(searchResults.stream()
                .anyMatch(u -> u.getId().equals(userId1)));

        // Update a user - verify search cache is invalidated
        UserRepresentation user1 = adminClient.realm("test").users().get(userId1).toRepresentation();
        user1.setFirstName("Johnny");
        adminClient.realm("test").users().get(userId1).update(user1);

        // Search for "Johnny" should now find the user
        searchResults = adminClient.realm("test").users()
                .search("Johnny", 0, 10);

        assertTrue(searchResults.stream()
                .anyMatch(u -> u.getId().equals(userId1)));
    }

    // Helper methods

    private UserRepresentation createTestUserRepresentation() {
        String randomSuffix = RandomStringUtils.randomAlphabetic(5);
        UserRepresentation user = new UserRepresentation();
        user.setUsername("testuser_" + randomSuffix);
        user.setEmail("testuser_" + randomSuffix + "@test.com");
        user.setFirstName("Test");
        user.setLastName("User" + randomSuffix);
        user.setEnabled(true);
        user.setEmailVerified(false);
        return user;
    }
}
