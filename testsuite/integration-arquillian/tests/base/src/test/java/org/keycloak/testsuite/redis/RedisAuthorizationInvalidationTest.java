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
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.AuthorizationResource;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.authorization.*;
import org.keycloak.testsuite.admin.ApiUtil;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for authorization cache invalidation with Redis.
 *
 * Tests ResourceAdapter, PolicyAdapter, PermissionTicketAdapter, etc.
 *
 * @author guilliano
 */
public class RedisAuthorizationInvalidationTest extends AbstractRedisTest {

    private String resourceClientId;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Before
    public void setupResourceServer() {
        // Create resource server client (required for authorization)
        ClientRepresentation client = new ClientRepresentation();
        String clientId = "resource-server-" + RandomStringUtils.randomAlphabetic(5);
        client.setClientId(clientId);
        client.setName("Test Resource Server");
        client.setEnabled(true);
        client.setServiceAccountsEnabled(true);
        client.setAuthorizationServicesEnabled(true);

        Response response = adminClient.realm("test").clients().create(client);
        resourceClientId = ApiUtil.getCreatedId(response);
        response.close();
    }

    @Test
    public void testResourceCRUD() {
        ClientResource clientResource = adminClient.realm("test").clients().get(resourceClientId);
        AuthorizationResource authz = clientResource.authorization();

        // CREATE
        ResourceRepresentation resource = new ResourceRepresentation();
        resource.setName("test-resource-" + RandomStringUtils.randomAlphabetic(5));
        resource.setDisplayName("Test Resource");
        resource.setType("urn:test-resource-type");
        resource.setUris(new HashSet<>(Arrays.asList("/api/resource/*")));

        Response response = authz.resources().create(resource);
        String resourceId = ApiUtil.getCreatedId(response);
        response.close();

        // READ - verify cached
        ResourceRepresentation cached = authz.resources().resource(resourceId).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(resource.getName(), cached.getName());

        // UPDATE - should invalidate cache
        cached.setDisplayName("Updated Resource");
        authz.resources().resource(resourceId).update(cached);

        // Verify cache invalidated
        ResourceRepresentation updated = authz.resources().resource(resourceId).toRepresentation();
        assertEquals("Updated Resource", updated.getDisplayName());

        // DELETE - should invalidate cache
        authz.resources().resource(resourceId).remove();

        // Verify cache cleared
        try {
            authz.resources().resource(resourceId).toRepresentation();
            fail("Should throw NotFoundException");
        } catch (NotFoundException expected) {}
    }

    @Test
    public void testPolicyCRUD() {
        ClientResource clientResource = adminClient.realm("test").clients().get(resourceClientId);
        AuthorizationResource authz = clientResource.authorization();

        // CREATE policy
        RolePolicyRepresentation policy = new RolePolicyRepresentation();
        policy.setName("test-policy-" + RandomStringUtils.randomAlphabetic(5));
        policy.setDescription("Test Role Policy");
        policy.setType("role");
        policy.setDecisionStrategy(DecisionStrategy.UNANIMOUS);

        Response response = authz.policies().role().create(policy);
        String policyId = ApiUtil.getCreatedId(response);
        response.close();

        // READ - verify cached
        PolicyRepresentation cached = authz.policies().policy(policyId).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(policy.getName(), cached.getName());

        // UPDATE - should invalidate cache
        cached.setDescription("Updated Policy");
        authz.policies().policy(policyId).update(cached);

        // Verify cache invalidated
        PolicyRepresentation updated = authz.policies().policy(policyId).toRepresentation();
        assertEquals("Updated Policy", updated.getDescription());

        // DELETE - should invalidate cache
        authz.policies().policy(policyId).remove();

        // Verify cache cleared
        try {
            authz.policies().policy(policyId).toRepresentation();
            fail("Should throw NotFoundException");
        } catch (NotFoundException expected) {}
    }

    @Test
    public void testPermissionCRUD() {
        ClientResource clientResource = adminClient.realm("test").clients().get(resourceClientId);
        AuthorizationResource authz = clientResource.authorization();

        // Create resource first
        ResourceRepresentation resource = new ResourceRepresentation();
        resource.setName("permission-test-resource-" + RandomStringUtils.randomAlphabetic(5));
        Response response = authz.resources().create(resource);
        String resourceId = ApiUtil.getCreatedId(response);
        response.close();

        // CREATE permission
        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
        permission.setName("test-permission-" + RandomStringUtils.randomAlphabetic(5));
        permission.setDescription("Test Permission");
        permission.setResources(new HashSet<>(Arrays.asList(resourceId)));
        permission.setDecisionStrategy(DecisionStrategy.UNANIMOUS);

        response = authz.permissions().resource().create(permission);
        String permissionId = ApiUtil.getCreatedId(response);
        response.close();

        // READ - verify cached
        ResourcePermissionRepresentation cached = authz.permissions().resource()
                .findById(permissionId).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(permission.getName(), cached.getName());

        // UPDATE - should invalidate cache
        cached.setDescription("Updated Permission");
        authz.permissions().resource().findById(permissionId).update(cached);

        // Verify cache invalidated
        ResourcePermissionRepresentation updated = authz.permissions().resource()
                .findById(permissionId).toRepresentation();
        assertEquals("Updated Permission", updated.getDescription());

        // DELETE - should invalidate cache
        authz.permissions().resource().findById(permissionId).remove();

        // Verify cache cleared
        try {
            authz.permissions().resource().findById(permissionId).toRepresentation();
            fail("Should throw NotFoundException");
        } catch (NotFoundException expected) {}

        // Cleanup
        authz.resources().resource(resourceId).remove();
    }
}
