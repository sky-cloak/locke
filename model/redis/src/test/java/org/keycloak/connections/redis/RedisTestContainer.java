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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers wrapper for Redis integration tests.
 * Provides a reusable Redis container for fast test execution.
 *
 * @author Keycloak Redis Team
 */
public class RedisTestContainer {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redisContainer;

    /**
     * Start the Redis container if not already running.
     * Container is reused across tests for performance.
     */
    public static synchronized void start() {
        if (redisContainer == null) {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withReuse(true);
            redisContainer.start();
        }
    }

    /**
     * Stop the Redis container.
     */
    public static synchronized void stop() {
        if (redisContainer != null) {
            redisContainer.stop();
            redisContainer = null;
        }
    }

    /**
     * Get the Redis connection URI.
     * Format: redis://host:port
     *
     * @return connection URI
     */
    public static String getConnectionUri() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running. Call start() first.");
        }
        return String.format("redis://%s:%d",
                redisContainer.getHost(),
                redisContainer.getMappedPort(REDIS_PORT));
    }

    /**
     * Get the Redis host.
     *
     * @return host address
     */
    public static String getHost() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running. Call start() first.");
        }
        return redisContainer.getHost();
    }

    /**
     * Get the mapped Redis port.
     *
     * @return port number
     */
    public static int getPort() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running. Call start() first.");
        }
        return redisContainer.getMappedPort(REDIS_PORT);
    }

    /**
     * Check if the container is running.
     *
     * @return true if running, false otherwise
     */
    public static boolean isRunning() {
        return redisContainer != null && redisContainer.isRunning();
    }
}
