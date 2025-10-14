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

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.util.ContainerAssume;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for Redis provider integration tests.
 *
 * This class provides Redis container management using Testcontainers
 * and configures the Keycloak test environment to use Redis as the cache provider.
 *
 * @author guilliano
 */
public abstract class AbstractRedisTest extends AbstractKeycloakTest {

    protected static final String REDIS_IMAGE = "redis:7.2-alpine";
    protected static final int REDIS_PORT = 6379;

    protected static GenericContainer<?> redisContainer;

    @BeforeClass
    public static void startRedisContainer() {
        ContainerAssume.assumeNotClustered();

        if (redisContainer == null) {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withReuse(true);

            redisContainer.start();

            log.infof("Started Redis container at %s:%d",
                    redisContainer.getHost(),
                    redisContainer.getMappedPort(REDIS_PORT));

            // Set system properties for Keycloak to use Redis
            System.setProperty("kc.cache", "redis");
            System.setProperty("kc.cache-redis-url", getRedisUrl());
        }
    }

    @AfterClass
    public static void stopRedisContainer() {
        if (redisContainer != null && redisContainer.isRunning()) {
            log.info("Stopping Redis container");
            redisContainer.stop();
        }

        // Clear system properties
        System.clearProperty("kc.cache");
        System.clearProperty("kc.cache-redis-url");
    }

    @Before
    public void verifyRedisConnection() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running");
        }
    }

    /**
     * Get the Redis connection URL for the test container
     */
    protected static String getRedisUrl() {
        if (redisContainer == null) {
            return null;
        }
        return String.format("redis://%s:%d",
                redisContainer.getHost(),
                redisContainer.getMappedPort(REDIS_PORT));
    }

    /**
     * Get the mapped Redis port on the host
     */
    protected static Integer getRedisPort() {
        return redisContainer != null ? redisContainer.getMappedPort(REDIS_PORT) : null;
    }

    /**
     * Get the Redis container host
     */
    protected static String getRedisHost() {
        return redisContainer != null ? redisContainer.getHost() : null;
    }

    /**
     * Clear all Redis data (useful for test isolation)
     */
    protected void clearRedisData() {
        // This will be implemented when needed for specific tests
        // For now, tests rely on cache namespacing and expiration
        log.info("Redis data cleared (if implemented)");
    }
}
