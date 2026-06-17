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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jboss.logging.Logger;

import java.io.File;
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
    // Cluster connections are thread-safe and multiplex over per-node channels, so one shared
    // connection serves all callers (no pool). Standalone/sentinel use the pool above instead.
    private volatile StatefulRedisClusterConnection<byte[], byte[]> clusterConnection;

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

        applyTls(uriBuilder);
        applyAuth(uriBuilder);

        RedisURI redisURI = uriBuilder.build();
        this.standaloneClient = RedisClient.create(redisURI);
        this.standaloneClient.setOptions(buildClientOptions());

        this.connectionPool = ConnectionPoolSupport.createGenericObjectPool(
                () -> standaloneClient.connect(new ByteArrayCodec()),
                buildPoolConfig()
        );
        prewarmPool();
    }

    private GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> buildPoolConfig() {
        // Lettuce connections auto-reconnect; PING-on-borrow/return adds 2 round-trips per cache op
        // for no real safety. Use idle-time eviction as the cheap defense instead.
        GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getPoolMaxSize());
        poolConfig.setMinIdle(config.getPoolMinSize());
        poolConfig.setMaxIdle(config.getPoolMaxSize());
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(30));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(15));
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(Duration.ofMillis(2000));
        poolConfig.setJmxEnabled(false);
        return poolConfig;
    }

    private void initSentinelClient() {
        RedisURI redisURI = buildSentinelUri();
        this.standaloneClient = RedisClient.create(redisURI);
        this.standaloneClient.setOptions(buildClientOptions());

        this.connectionPool = ConnectionPoolSupport.createGenericObjectPool(
                () -> standaloneClient.connect(new ByteArrayCodec()),
                buildPoolConfig()
        );
        prewarmPool();
    }

    // Build straight from the configured host/port pairs. Don't round-trip through a sentinel
    // RedisURI: it keeps its hosts in a sentinels list, so getHost() is empty. package-private
    // for testing.
    RedisURI buildSentinelUri() {
        List<RedisConnectionConfig.HostPort> sentinels = config.getHosts();
        if (sentinels.isEmpty()) {
            throw new IllegalStateException("No sentinel hosts configured");
        }
        RedisURI.Builder uriBuilder = RedisURI.Builder
                .sentinel(sentinels.get(0).getHost(), sentinels.get(0).getPort(), config.getSentinelMasterId())
                .withTimeout(config.getTimeout());
        for (int i = 1; i < sentinels.size(); i++) {
            uriBuilder.withSentinel(sentinels.get(i).getHost(), sentinels.get(i).getPort());
        }
        applyTls(uriBuilder);
        applyAuth(uriBuilder);
        return uriBuilder.build();
    }

    private void initClusterClient() {
        List<RedisURI> clusterUris = config.getHosts().stream()
                .map(hostPort -> {
                    RedisURI.Builder builder = RedisURI.Builder.redis(hostPort.getHost(), hostPort.getPort())
                            .withTimeout(config.getTimeout());
                    applyTls(builder);
                    applyAuth(builder);
                    return builder.build();
                })
                .collect(Collectors.toList());

        this.clusterClient = RedisClusterClient.create(clusterUris);
        ClusterClientOptions.Builder clusterOptions = ClusterClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(config.getTimeout()))
                .topologyRefreshOptions(buildClusterTopologyRefreshOptions());
        if (config.isSslEnabled()) {
            clusterOptions.sslOptions(buildSslOptions());
        }
        this.clusterClient.setOptions(clusterOptions.build());
        this.clusterConnection = clusterClient.connect(new ByteArrayCodec());
    }

    // Keep the slot->node map current so a shard failover or reshard doesn't strand the
    // client on a dead node. package-private for testing.
    static ClusterTopologyRefreshOptions buildClusterTopologyRefreshOptions() {
        return ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(30))
                .enableAllAdaptiveRefreshTriggers()
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
                .dynamicRefreshSources(true)
                .build();
    }

    // See initStandaloneClient: fail fast on a Redis outage instead of hanging request threads.
    private ClientOptions buildClientOptions() {
        ClientOptions.Builder builder = ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(config.getTimeout()));
        if (config.isSslEnabled()) {
            builder.sslOptions(buildSslOptions());
        }
        return builder.build();
    }

    private void applyTls(RedisURI.Builder uriBuilder) {
        if (!config.isSslEnabled()) {
            return;
        }
        uriBuilder.withSsl(true);
        // FULL: chain + CN/SAN match (default).
        // CA: chain only; skip CN/SAN match. Used when the operator opts out of hostname
        // verification (e.g. internal CA whose cert CN does not match the K8s service DNS).
        // NONE is intentionally never used: a "TLS-enabled" connection that doesn't validate
        // the cert chain at all is worse than plaintext because it pretends to be secure.
        uriBuilder.withVerifyPeer(config.isTlsVerifyHostname() ? SslVerifyMode.FULL : SslVerifyMode.CA);
    }

    private void applyAuth(RedisURI.Builder uriBuilder) {
        String password = config.getPassword();
        if (password == null) {
            return;
        }
        String username = config.getUsername();
        if (username != null && !username.isEmpty()) {
            uriBuilder.withAuthentication(username, password.toCharArray());
        } else {
            uriBuilder.withPassword(password.toCharArray());
        }
    }

    private SslOptions buildSslOptions() {
        SslOptions.Builder ssl = SslOptions.builder();
        String tlsCaFile = config.getTlsCaFile();
        if (tlsCaFile != null && !tlsCaFile.isEmpty()) {
            File ca = new File(tlsCaFile);
            if (!ca.canRead()) {
                throw new RuntimeException("KC_CACHE_REDIS_TLS_CA_FILE points to a missing or unreadable file: " + tlsCaFile);
            }
            ssl.trustManager(ca);
        }
        return ssl.build();
    }

    /**
     * Get a connection from the pool.
     * For standalone/sentinel, returns a pooled connection (callers MUST returnConnection it).
     * Lettuce's .sync() API serializes commands per connection, so a pool of N connections
     * is what gives us N-way parallelism. (A single shared connection causes head-of-line
     * blocking under concurrent load — verified by benchmark: 5.96 vs 8.1 iter/s at 50 VUs.)
     * For cluster mode, returns the shared cluster connection (thread-safe; not pooled).
     *
     * @return Redis connection (StatefulRedisConnection or StatefulRedisClusterConnection)
     */
    public Object getConnection() {
        try {
            if (config.getMode() == RedisConnectionConfig.Mode.CLUSTER) {
                return clusterConnection;
            } else {
                return connectionPool.borrowObject();
            }
        } catch (Exception e) {
            logger.errorf(e, "Failed to get Redis connection");
            throw new RuntimeException("Failed to get Redis connection", e);
        }
    }

    /**
     * Return a connection. No-op in cluster mode (the shared connection lives for the manager's
     * lifetime and is closed in {@link #close()}); pooled connections are returned to the pool.
     */
    public void returnConnection(Object connection) {
        if (connection == null || config.getMode() == RedisConnectionConfig.Mode.CLUSTER) {
            return;
        }
        if (connection instanceof StatefulRedisConnection) {
            connectionPool.returnObject((StatefulRedisConnection<byte[], byte[]>) connection);
        }
    }

    /**
     * Sync commands for a borrowed connection, working across all modes. The standalone/sentinel
     * connection yields {@code RedisCommands} and the cluster connection yields
     * {@code RedisAdvancedClusterCommands}; both extend {@link RedisClusterCommands}.
     */
    @SuppressWarnings("unchecked")
    public RedisClusterCommands<byte[], byte[]> sync(Object connection) {
        if (connection instanceof StatefulRedisClusterConnection) {
            return ((StatefulRedisClusterConnection<byte[], byte[]>) connection).sync();
        }
        return ((StatefulRedisConnection<byte[], byte[]>) connection).sync();
    }

    /** Async commands for a borrowed connection. See {@link #sync(Object)}. */
    @SuppressWarnings("unchecked")
    public RedisClusterAsyncCommands<byte[], byte[]> async(Object connection) {
        if (connection instanceof StatefulRedisClusterConnection) {
            return ((StatefulRedisClusterConnection<byte[], byte[]>) connection).async();
        }
        return ((StatefulRedisConnection<byte[], byte[]>) connection).async();
    }

    /**
     * Pre-warm the pool to minIdle so that the first N cache ops don't pay the
     * HELLO + 2× CLIENT SETINFO handshake cost (~3 RTs each). Without warmup, a single
     * login can open ~16 fresh TCP connections — half of all Redis traffic for that
     * login is connection-setup. With warmup, that cost is paid once at startup.
     */
    private void prewarmPool() {
        int target = config.getPoolMinSize();
        StatefulRedisConnection<byte[], byte[]>[] borrowed = new StatefulRedisConnection[target];
        try {
            for (int i = 0; i < target; i++) {
                borrowed[i] = connectionPool.borrowObject();
            }
        } catch (Exception e) {
            logger.warnf(e, "Pool pre-warm failed at connection %d/%d (continuing)", borrowed.length, target);
        } finally {
            for (StatefulRedisConnection<byte[], byte[]> c : borrowed) {
                if (c != null) connectionPool.returnObject(c);
            }
        }
        logger.infof("Pre-warmed Redis connection pool: %d idle connections ready", target);
    }

    /** Lettuce client for standalone/sentinel modes; null in cluster mode. Used by the L1 bus for pub/sub. */
    public RedisClient getStandaloneClient() {
        return standaloneClient;
    }

    /** Lettuce cluster client for cluster mode; null otherwise. Used by the L1 bus for cluster pub/sub. */
    public RedisClusterClient getClusterClient() {
        return clusterClient;
    }

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

        if (clusterConnection != null) {
            clusterConnection.close();
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
