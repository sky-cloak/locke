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
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.admin.ApiUtil;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Integration test for client cache invalidation with Redis.
 *
 * Tests ClientAdapter caching behavior for OIDC and SAML clients.
 *
 * @author guilliano
 */
public class RedisClientInvalidationTest extends AbstractRedisTest {

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm("test");
        realm.setEnabled(true);
        testRealms.add(realm);
    }

    @Test
    public void testClientCRUD() {
        // CREATE
        ClientRepresentation client = createTestClientRepresentation();
        Response response = adminClient.realm("test").clients().create(client);
        String clientId = ApiUtil.getCreatedId(response);
        response.close();

        // READ - verify cached
        ClientRepresentation cached = adminClient.realm("test").clients().get(clientId).toRepresentation();
        assertThat(cached, is(notNullValue()));
        assertEquals(client.getClientId(), cached.getClientId());
        assertTrue(cached.isEnabled());

        // UPDATE - should invalidate cache
        cached.setEnabled(false);
        cached.setDescription("Updated Description");
        adminClient.realm("test").clients().get(clientId).update(cached);

        // Verify cache invalidated
        ClientRepresentation updated = adminClient.realm("test").clients().get(clientId).toRepresentation();
        assertFalse("Client should be disabled", updated.isEnabled());
        assertEquals("Updated Description", updated.getDescription());

        // DELETE - should invalidate cache
        adminClient.realm("test").clients().get(clientId).remove();

        // Verify cache cleared
        try {
            adminClient.realm("test").clients().get(clientId).toRepresentation();
            fail("Should throw NotFoundException");
        } catch (NotFoundException expected) {}
    }

    @Test
    public void testClientSecretChange() {
        // Create client with secret
        ClientRepresentation client = createTestClientRepresentation();
        client.setPublicClient(false);
        client.setSecret("initialSecret");

        Response response = adminClient.realm("test").clients().create(client);
        String clientId = ApiUtil.getCreatedId(response);
        response.close();

        // Verify initial secret cached
        ClientRepresentation cached = adminClient.realm("test").clients().get(clientId).toRepresentation();
        assertEquals("initialSecret", cached.getSecret());

        // Regenerate secret
        ClientResource clientResource = adminClient.realm("test").clients().get(clientId);
        String newSecret = clientResource.generateNewSecret().getValue();

        // Verify cache invalidated with new secret
        ClientRepresentation updated = clientResource.toRepresentation();
        assertEquals(newSecret, updated.getSecret());
        assertNotEquals("initialSecret", updated.getSecret());

        // Cleanup
        clientResource.remove();
    }

    @Test
    public void testClientProtocolMappers() {
        // Create client
        ClientRepresentation client = createTestClientRepresentation();
        Response response = adminClient.realm("test").clients().create(client);
        String clientId = ApiUtil.getCreatedId(response);
        response.close();

        ClientResource clientResource = adminClient.realm("test").clients().get(clientId);

        // Add protocol mapper
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("testMapper");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", "email");
        config.put("claim.name", "email");
        config.put("jsonType.label", "String");
        mapper.setConfig(config);

        response = clientResource.getProtocolMappers().createMapper(mapper);
        String mapperId = ApiUtil.getCreatedId(response);
        response.close();

        // Verify mapper cached
        ClientRepresentation updated = clientResource.toRepresentation();
        assertThat(updated.getProtocolMappers(), hasSize(1));
        assertEquals("testMapper", updated.getProtocolMappers().get(0).getName());

        // Update mapper - should invalidate client cache
        mapper.setId(mapperId);
        mapper.setName("updatedMapper");
        clientResource.getProtocolMappers().update(mapperId, mapper);

        // Verify cache invalidated
        updated = clientResource.toRepresentation();
        assertEquals("updatedMapper", updated.getProtocolMappers().get(0).getName());

        // Delete mapper - should invalidate cache
        clientResource.getProtocolMappers().delete(mapperId);

        updated = clientResource.toRepresentation();
        assertTrue("Protocol mappers should be empty", updated.getProtocolMappers() == null || updated.getProtocolMappers().isEmpty());

        // Cleanup
        clientResource.remove();
    }

    // Helper methods

    private ClientRepresentation createTestClientRepresentation() {
        String randomSuffix = RandomStringUtils.randomAlphabetic(5);
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("test-client-" + randomSuffix);
        client.setName("Test Client " + randomSuffix);
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setStandardFlowEnabled(true);
        client.setRedirectUris(Arrays.asList("http://localhost:8080/*"));
        client.setWebOrigins(Arrays.asList("http://localhost:8080"));
        return client;
    }
}
