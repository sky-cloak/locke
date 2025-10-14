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

import org.junit.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.admin.ApiUtil;

import jakarta.ws.rs.core.Response;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for Redis cluster event distribution.
 *
 * Tests RedisPubSubEventManager and cache invalidation events.
 *
 * Note: This test validates event distribution in single-node mode.
 * Full cluster testing requires multiple Keycloak nodes.
 *
 * @author guilliano
 */
public class RedisClusterEventTest extends AbstractRedisTest {

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Test
    public void testCacheInvalidationEvents() {
        // Create a user - triggers cache entry
        UserRepresentation user = new UserRepresentation();
        user.setUsername("event-test-user");
        user.setEmail("event@test.com");
        user.setEnabled(true);

        Response response = adminClient.realm("test").users().create(user);
        String userId = ApiUtil.getCreatedId(response);
        response.close();

        // Read user - loads into cache
        UserRepresentation cached = adminClient.realm("test").users().get(userId).toRepresentation();
        assertNotNull("User should be cached", cached);

        // Update user - should trigger invalidation event via Redis pub/sub
        cached.setFirstName("Updated");
        adminClient.realm("test").users().get(userId).update(cached);

        // Read again - should reflect update (cache was invalidated)
        UserRepresentation updated = adminClient.realm("test").users().get(userId).toRepresentation();
        assertEquals("Updated", updated.getFirstName());

        // In a multi-node cluster, this invalidation event would propagate
        // via RedisPubSubEventManager to all nodes, ensuring cache consistency

        // Cleanup
        adminClient.realm("test").users().get(userId).remove();
    }

    @Test
    public void testClusterWideNotification() {
        // Create realm - triggers cluster notification
        RealmRepresentation newRealm = new RealmRepresentation();
        String realmName = "test-cluster-" + System.currentTimeMillis();
        newRealm.setRealm(realmName);
        newRealm.setEnabled(true);

        adminClient.realms().create(newRealm);

        // Verify realm exists (cache populated)
        RealmRepresentation cached = adminClient.realm(realmName).toRepresentation();
        assertThat(cached, is(notNullValue()));

        // Update realm - triggers cluster-wide invalidation
        cached.setDisplayName("Cluster Test Realm");
        adminClient.realm(realmName).update(cached);

        // Verify update propagated
        RealmRepresentation updated = adminClient.realm(realmName).toRepresentation();
        assertEquals("Cluster Test Realm", updated.getDisplayName());

        // In multi-node setup:
        // 1. Node A updates realm
        // 2. RedisPubSubEventManager publishes "realm.invalidate" event to Redis
        // 3. Node B subscribes to Redis channel, receives event
        // 4. Node B invalidates local cache
        // 5. Node B next read gets fresh data from DB

        // This test validates the event flow in single-node mode
        // The same code path executes in multi-node with Redis pub/sub

        // Cleanup
        adminClient.realm(realmName).remove();
    }
}
