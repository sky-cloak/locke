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

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages Redis client lifecycle and connection pooling.
 * Creates appropriate Lettuce clients based on Redis deployment mode.
 *
 * @author Keycloak Redis Team
 */
public class RedisClientManager {

    private static final Logger logger = Logger.getLogger(RedisClientManager.class);

    private final RedisConnectionConfig config;
    private RedisClient standaloneClient;
    private RedisClusterClient clusterClient;
    private GenericObjectPool<StatefulRedisConnection<byte[], byte[]>> connectionPool;

    public RedisClientManager(RedisConnectionConfig config) {
        this.config = config;
    }

    /**
     * Initialize the Redis client based on the configured mode.
     */
    public void init() {
        logger.infof("Initializing Redis client in %s mode", config.getMode());

        switch (config.getMode()) {
            case STANDALONE:
                initStandaloneClient();
                break;
            case SENTINEL:
                initSentinelClient();
                break;
            case CLUSTER:
                initClusterClient();
                break;
            default:
                throw new IllegalStateException("Unsupported Redis mode: " + config.getMode());
        }

        // Perform health check
        if (!isHealthy()) {
            throw new RuntimeException("Failed to connect to Redis");
        }

        logger.info("Redis client initialized successfully");
    }

    private void initStandaloneClient() {
        RedisConnectionConfig.HostPort hostPort = config.getHosts().get(0);

        RedisURI.Builder uriBuilder = RedisURI.Builder.redis(hostPort.getHost(), hostPort.getPort())
                .withDatabase(config.getDatabase())
                .withTimeout(config.getTimeout());

        if (config.getPassword() != null) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        RedisURI redisURI = uriBuilder.build();
        this.standaloneClient = RedisClient.create(redisURI);

        // Create connection pool
        GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getPoolMaxSize());
        poolConfig.setMinIdle(config.getPoolMinSize());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);

        this.connectionPool = ConnectionPoolSupport.createGenericObjectPool(
                () -> standaloneClient.connect(new ByteArrayCodec()),
                poolConfig
        );
    }

    private void initSentinelClient() {
        List<RedisURI> sentinelUris = config.getHosts().stream()
                .map(hostPort -> RedisURI.Builder.sentinel(hostPort.getHost(), hostPort.getPort(), config.getSentinelMasterId())
                        .withTimeout(config.getTimeout())
                        .build())
                .collect(Collectors.toList());

        if (sentinelUris.isEmpty()) {
            throw new IllegalStateException("No sentinel hosts configured");
        }

        // Use first URI and add others as sentinel nodes
        RedisURI.Builder uriBuilder = RedisURI.Builder.sentinel(
                sentinelUris.get(0).getHost(),
                sentinelUris.get(0).getPort(),
                config.getSentinelMasterId()
        ).withTimeout(config.getTimeout());

        for (int i = 1; i < sentinelUris.size(); i++) {
            RedisURI uri = sentinelUris.get(i);
            uriBuilder.withSentinel(uri.getHost(), uri.getPort());
        }

        if (config.getPassword() != null) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        RedisURI redisURI = uriBuilder.build();
        this.standaloneClient = RedisClient.create(redisURI);

        // Create connection pool
        GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getPoolMaxSize());
        poolConfig.setMinIdle(config.getPoolMinSize());

        this.connectionPool = ConnectionPoolSupport.createGenericObjectPool(
                () -> standaloneClient.connect(new ByteArrayCodec()),
                poolConfig
        );
    }

    private void initClusterClient() {
        List<RedisURI> clusterUris = config.getHosts().stream()
                .map(hostPort -> {
                    RedisURI.Builder builder = RedisURI.Builder.redis(hostPort.getHost(), hostPort.getPort())
                            .withTimeout(config.getTimeout());
                    if (config.getPassword() != null) {
                        builder.withPassword(config.getPassword().toCharArray());
                    }
                    return builder.build();
                })
                .collect(Collectors.toList());

        this.clusterClient = RedisClusterClient.create(clusterUris);
    }

    /**
     * Get a connection from the pool.
     * For standalone/sentinel, returns a pooled connection.
     * For cluster, creates a new cluster connection.
     *
     * @return Redis connection
     */
    public Object getConnection() {
        try {
            if (config.getMode() == RedisConnectionConfig.Mode.CLUSTER) {
                return clusterClient.connect(new ByteArrayCodec());
            } else {
                return connectionPool.borrowObject();
            }
        } catch (Exception e) {
            logger.errorf(e, "Failed to get Redis connection");
            throw new RuntimeException("Failed to get Redis connection", e);
        }
    }

    /**
     * Return a connection to the pool.
     *
     * @param connection connection to return
     */
    public void returnConnection(Object connection) {
        if (config.getMode() != RedisConnectionConfig.Mode.CLUSTER && connection instanceof StatefulRedisConnection) {
            connectionPool.returnObject((StatefulRedisConnection<byte[], byte[]>) connection);
        }
    }

    /**
     * Check if Redis is healthy by performing a ping.
     *
     * @return true if healthy, false otherwise
     */
    public boolean isHealthy() {
        try {
            if (config.getMode() == RedisConnectionConfig.Mode.CLUSTER) {
                try (StatefulRedisClusterConnection<byte[], byte[]> connection =
                             clusterClient.connect(new ByteArrayCodec())) {
                    String pong = connection.sync().ping();
                    return "PONG".equals(pong);
                }
            } else {
                try (StatefulRedisConnection<byte[], byte[]> connection =
                             standaloneClient.connect(new ByteArrayCodec())) {
                    String pong = connection.sync().ping();
                    return "PONG".equals(pong);
                }
            }
        } catch (Exception e) {
            logger.warnf(e, "Redis health check failed");
            return false;
        }
    }

    /**
     * Close all Redis connections and clients.
     */
    public void close() {
        logger.info("Closing Redis client");

        if (connectionPool != null) {
            connectionPool.close();
        }

        if (standaloneClient != null) {
            standaloneClient.shutdown();
        }

        if (clusterClient != null) {
            clusterClient.shutdown();
        }
    }

    /**
     * Byte array codec for Redis commands.
     * Allows storing raw bytes directly without String conversion.
     */
    private static class ByteArrayCodec implements io.lettuce.core.codec.RedisCodec<byte[], byte[]> {

        @Override
        public byte[] decodeKey(java.nio.ByteBuffer bytes) {
            return getBytes(bytes);
        }

        @Override
        public byte[] decodeValue(java.nio.ByteBuffer bytes) {
            return getBytes(bytes);
        }

        @Override
        public java.nio.ByteBuffer encodeKey(byte[] key) {
            return java.nio.ByteBuffer.wrap(key);
        }

        @Override
        public java.nio.ByteBuffer encodeValue(byte[] value) {
            return java.nio.ByteBuffer.wrap(value);
        }

        private byte[] getBytes(java.nio.ByteBuffer buffer) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
    }
}
