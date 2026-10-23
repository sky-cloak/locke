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

package org.keycloak.connections.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration tests for RedisClientManager.
 * Uses Testcontainers for real Redis integration testing.
 *
 * @author Keycloak Redis Team
 */
public class RedisClientManagerTest {

    private RedisClientManager clientManager;

    @BeforeClass
    public static void setUpContainer() {
        // Start Redis container once for all tests (reusable)
        RedisTestContainer.start();
    }

    @Before
    public void setUp() {
        // Create new client manager for each test
        String connectionUri = RedisTestContainer.getConnectionUri();
        RedisConnectionConfig config = RedisConnectionConfig.parse(connectionUri);
        this.clientManager = new RedisClientManager(config);
    }

    @After
    public void tearDown() {
        // Clean up client manager after each test
        if (clientManager != null) {
            clientManager.close();
        }
    }

    @Test
    public void testCreateStandaloneClient_Success() {
        // Given - setup done in setUp()

        // When
        clientManager.init();

        // Then
        assertThat(clientManager.isHealthy(), equalTo(true));
    }

    @Test
    public void testHealthCheck_WhenRedisUp_ReturnsTrue() {
        // Given
        clientManager.init();

        // When
        boolean healthy = clientManager.isHealthy();

        // Then
        assertThat(healthy, equalTo(true));
    }

    @Test
    public void testGetConnection_ReturnsConnection() {
        // Given
        clientManager.init();

        // When
        Object connection = clientManager.getConnection();

        // Then
        assertThat(connection, notNullValue());
        
        // Cleanup
        clientManager.returnConnection(connection);
    }

    @Test
    public void testConnectionPooling_BorrowAndReturn() {
        // Given
        clientManager.init();

        // When - borrow 3 connections
        Object conn1 = clientManager.getConnection();
        Object conn2 = clientManager.getConnection();
        Object conn3 = clientManager.getConnection();

        // Then - all connections should be valid
        assertThat(conn1, notNullValue());
        assertThat(conn2, notNullValue());
        assertThat(conn3, notNullValue());

        // Cleanup - return all connections
        clientManager.returnConnection(conn1);
        clientManager.returnConnection(conn2);
        clientManager.returnConnection(conn3);
    }

    @Test
    public void testClose_ShutdownsClient() {
        // Given
        clientManager.init();
        assertThat(clientManager.isHealthy(), equalTo(true));

        // When
        clientManager.close();

        // Then
        // Health check should fail after close (client shutdown)
        // Note: This test validates graceful shutdown
        assertThat(clientManager.isHealthy(), equalTo(false));
    }
}
