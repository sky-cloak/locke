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
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import jakarta.ws.rs.NotFoundException;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for role cache invalidation with Redis.
 *
 * Tests RoleAdapter caching for realm and client roles.
 *
 * @author guilliano
 */
public class RedisRoleInvalidationTest extends AbstractRedisTest {

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Test
    public void testRoleCRUD() {
        // CREATE
        RoleRepresentation role = createTestRoleRepresentation();
        adminClient.realm("test").roles().create(role);

        // READ - verify cached
        RoleRepresentation cached = adminClient.realm("test").roles().get(role.getName()).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(role.getName(), cached.getName());
        assertEquals(role.getDescription(), cached.getDescription());

        // UPDATE - should invalidate cache
        cached.setDescription("Updated Description");
        adminClient.realm("test").roles().get(role.getName()).update(cached);

        // Verify cache invalidated
        RoleRepresentation updated = adminClient.realm("test").roles().get(role.getName()).toRepresentation();
        assertEquals("Updated Description", updated.getDescription());

        // DELETE - should invalidate cache
        adminClient.realm("test").roles().deleteRole(role.getName());

        // Verify cache cleared
        try {
            adminClient.realm("test").roles().get(role.getName()).toRepresentation();
            fail("Should throw NotFoundException");
        } catch (NotFoundException expected) {}
    }

    @Test
    public void testRoleComposites() {
        // Create parent role
        RoleRepresentation parentRole = createTestRoleRepresentation();
        adminClient.realm("test").roles().create(parentRole);

        // Create child roles
        RoleRepresentation childRole1 = createTestRoleRepresentation();
        RoleRepresentation childRole2 = createTestRoleRepresentation();
        adminClient.realm("test").roles().create(childRole1);
        adminClient.realm("test").roles().create(childRole2);

        RoleResource parentResource = adminClient.realm("test").roles().get(parentRole.getName());

        // Add composites - should invalidate parent role cache
        RoleRepresentation child1 = adminClient.realm("test").roles().get(childRole1.getName()).toRepresentation();
        RoleRepresentation child2 = adminClient.realm("test").roles().get(childRole2.getName()).toRepresentation();
        parentResource.addComposites(Arrays.asList(child1, child2));

        // Verify composites cached
        RoleRepresentation updated = parentResource.toRepresentation();
        assertTrue(updated.isComposite());

        List<RoleRepresentation> composites = parentResource.getRoleComposites();
        assertThat(composites, hasSize(2));

        List<String> compositeNames = Arrays.asList(
            composites.get(0).getName(),
            composites.get(1).getName()
        );
        assertTrue(compositeNames.contains(childRole1.getName()));
        assertTrue(compositeNames.contains(childRole2.getName()));

        // Remove one composite - should invalidate cache
        parentResource.deleteComposites(Arrays.asList(child1));

        composites = parentResource.getRoleComposites();
        assertThat(composites, hasSize(1));
        assertEquals(childRole2.getName(), composites.get(0).getName());

        // Cleanup
        adminClient.realm("test").roles().deleteRole(parentRole.getName());
        adminClient.realm("test").roles().deleteRole(childRole1.getName());
        adminClient.realm("test").roles().deleteRole(childRole2.getName());
    }

    // Helper methods

    private RoleRepresentation createTestRoleRepresentation() {
        String randomSuffix = RandomStringUtils.randomAlphabetic(5);
        RoleRepresentation role = new RoleRepresentation();
        role.setName("test-role-" + randomSuffix);
        role.setDescription("Test Role " + randomSuffix);
        return role;
    }
}
