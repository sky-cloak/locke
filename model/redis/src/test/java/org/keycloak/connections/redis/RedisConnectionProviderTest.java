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
import static org.hamcrest.Matchers.sameInstance;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.Config;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executor;

/**
 * Integration tests for RedisConnectionProvider and DefaultRedisConnectionProviderFactory.
 * Tests provider lifecycle, lazy initialization, and resource management.
 *
 * @author Keycloak Redis Team
 */
public class RedisConnectionProviderTest {

    private DefaultRedisConnectionProviderFactory factory;

    @BeforeClass
    public static void setUpContainer() {
        // Start Redis container once for all tests
        RedisTestContainer.start();
    }

    @Before
    public void setUp() {
        // Create new factory for each test
        factory = new DefaultRedisConnectionProviderFactory();
        
        // Initialize with Redis connection config
        Config.Scope scope = createTestConfig();
        factory.init(scope);
    }

    @After
    public void tearDown() {
        // Clean up factory after each test
        if (factory != null) {
            factory.close();
        }
    }

    private Config.Scope createTestConfig() {
        return new Config.Scope() {
            @Override
            public String get(String key) {
                if ("connectionUri".equals(key)) {
                    return RedisTestContainer.getConnectionUri();
                }
                return null;
            }

            @Override
            public String get(String key, String defaultValue) {
                String value = get(key);
                return value != null ? value : defaultValue;
            }

            @Override
            public Integer getInt(String key) {
                return null;
            }

            @Override
            public Integer getInt(String key, Integer defaultValue) {
                return defaultValue;
            }

            @Override
            public Long getLong(String key) {
                return null;
            }

            @Override
            public Long getLong(String key, Long defaultValue) {
                return defaultValue;
            }

            @Override
            public Boolean getBoolean(String key) {
                return null;
            }

            @Override
            public Boolean getBoolean(String key, Boolean defaultValue) {
                return defaultValue;
            }

            @Override
            public String[] getArray(String key) {
                return null;
            }

            @Override
            public Config.Scope scope(String... scope) {
                return this;
            }

            @Override
            public Config.Scope root() {
                return this;
            }

            @Override
            public java.util.Set<String> getPropertyNames() {
                return java.util.Set.of("connectionUri");
            }
        };
    }

    @Test
    public void testLazyInitialization_FirstCall() {
        // Given - factory initialized in setUp()

        // When
        RedisConnectionProvider provider = factory.create(null);

        // Then
        assertThat(provider, notNullValue());
    }

    @Test
    public void testLazyInitialization_SubsequentCalls() {
        // Given
        RedisConnectionProvider provider1 = factory.create(null);

        // When
        RedisConnectionProvider provider2 = factory.create(null);

        // Then - should return same instance (singleton)
        assertThat(provider2, sameInstance(provider1));
    }

    @Test
    public void testGetCache_ReturnsCacheAdapter() {
        // Cache implementation landed in Milestone 1.3 (LettuceCacheAdapter); this test was
        // originally written when getCache() was a stub. It now returns a real cache adapter.
        RedisConnectionProvider provider = factory.create(null);
        Object cache = provider.getCache("sessions", true);
        assertThat(cache, notNullValue());
    }

    @Test
    public void testGetCache_WithCreateFalse_ReturnsNull() {
        // Given
        RedisConnectionProvider provider = factory.create(null);

        // When
        Object cache = provider.getCache("nonexistent", false);

        // Then
        assertThat(cache, equalTo(null));
    }

    @Test
    public void testGetTopologyInfo_ReturnsInfo() {
        // Given
        RedisConnectionProvider provider = factory.create(null);

        // When
        TopologyInfo topologyInfo = provider.getTopologyInfo();

        // Then
        assertThat(topologyInfo, notNullValue());
        assertThat(topologyInfo.getMyNodeName(), notNullValue());
    }

    @Test
    public void testGetExecutor_ReturnsExecutor() {
        // Given
        RedisConnectionProvider provider = factory.create(null);

        // When
        Executor executor = provider.getExecutor("test");

        // Then
        assertThat(executor, notNullValue());
    }

    @Test
    public void testGetScheduledExecutor_ReturnsScheduledExecutor() {
        // Given
        RedisConnectionProvider provider = factory.create(null);

        // When
        ScheduledExecutorService scheduledExecutor = provider.getScheduledExecutorService();

        // Then
        assertThat(scheduledExecutor, notNullValue());
    }

    @Test
    public void testClose_ShutdownsResources() {
        // Given
        RedisConnectionProvider provider = factory.create(null);
        assertThat(provider.isHealthy(), equalTo(true));

        // When
        factory.close();

        // Then
        // Provider should be closed, health check should fail
        assertThat(provider.isHealthy(), equalTo(false));
    }
}
