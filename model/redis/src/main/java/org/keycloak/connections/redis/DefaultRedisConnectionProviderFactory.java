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

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cache.redis.L1InvalidationBus;
import org.keycloak.cache.redis.L1RedisCache;
import org.keycloak.cache.redis.LuaScripts;
import org.keycloak.cache.redis.PipelinedRedisCache;
import org.keycloak.cache.redis.RedisMetrics;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ServerInfoAwareProviderFactory;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Factory for creating DefaultRedisConnectionProvider instances.
 * Handles lazy initialization and lifecycle management.
 *
 * @author Keycloak Redis Team
 */
public class DefaultRedisConnectionProviderFactory
        implements RedisConnectionProviderFactory, ServerInfoAwareProviderFactory, EnvironmentDependentProviderFactory {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);
    private static final ReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();

    private Config.Scope config;
    private volatile RedisConnectionProvider connectionProvider;
    private volatile RedisClientManager clientManager;
    private volatile RedissonClient redissonClient;
    private volatile L1InvalidationBus l1Bus;
    private volatile LuaScripts luaScripts;
    private volatile PipelinedRedisCache pipelinedCache;
    private volatile RedisMetrics metrics;

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        return lazyInit();
    }

    /**
     * Lazy initialization with double-checked locking.
     * Same pattern as Infinispan to prevent race conditions.
     */
    private RedisConnectionProvider lazyInit() {
        if (connectionProvider != null) {
            return connectionProvider;
        }

        synchronized (this) {
            if (connectionProvider != null) {
                return connectionProvider;
            }

            logger.info("Initializing Redis connection provider");

            // Read configuration
            String connectionUri = config.get("url", "redis://localhost:6379");
            RedisConnectionConfig redisConfig = RedisConnectionConfig.parse(connectionUri);

            // Create client manager
            this.clientManager = new RedisClientManager(redisConfig);
            clientManager.init();

            // Create Redisson client for distributed primitives
            this.redissonClient = RedissonClientFactory.createClient(redisConfig);
            logger.info("Redisson client initialized for distributed locks and pub/sub");

            // Create topology info
            String nodeName = config.get("nodeName");
            String siteName = config.get("siteName");
            TopologyInfo topologyInfo = new TopologyInfo(nodeName, siteName);

            logger.infof("Redis topology: %s", topologyInfo);

            // Initialize the L1 cache invalidation bus (skip in cluster mode for now —
            // cluster pub/sub requires per-shard subscriptions). Bus is null-safe in
            // DefaultRedisConnectionProvider so the L1 layer is bypassed if absent.
            L1RedisCache.L1Config l1Config = new L1RedisCache.L1Config(
                    config.getInt("l1MaxEntries", 10_000),
                    Duration.ofSeconds(config.getInt("l1TtlSeconds", 60))
            );
            boolean l1Enabled = config.getBoolean("l1Enabled", true);
            if (l1Enabled && clientManager.getStandaloneClient() != null) {
                this.l1Bus = new L1InvalidationBus(clientManager.getStandaloneClient());
                logger.infof("L1 cache enabled (max=%d entries, ttl=%s)",
                        l1Config.maxEntries, l1Config.ttl);
            } else {
                this.l1Bus = null;
                logger.info("L1 cache disabled");
            }

            // Tier 2 infra: Lua scripts (preloaded into Redis script cache) and pipeline factory.
            this.metrics = new RedisMetrics();
            this.luaScripts = new LuaScripts(clientManager, metrics);
            try {
                luaScripts.loadAll();
            } catch (Exception e) {
                logger.warnf(e, "Lua script preload failed; will fall back to inline EVAL on first use");
            }
            this.pipelinedCache = new PipelinedRedisCache(clientManager, metrics);

            // Create provider
            this.connectionProvider = new DefaultRedisConnectionProvider(
                    clientManager, redissonClient, topologyInfo, l1Bus, l1Config,
                    luaScripts, pipelinedCache, metrics);

            return connectionProvider;
        }
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Register for lifecycle events if needed
    }

    @Override
    public void close() {
        logger.info("Closing Redis connection provider factory");

        runWithWriteLock(() -> {
            if (connectionProvider != null) {
                connectionProvider.close();
                connectionProvider = null;
            }
            if (l1Bus != null) {
                l1Bus.close();
                l1Bus = null;
            }
            if (redissonClient != null) {
                RedissonClientFactory.closeClient(redissonClient);
                redissonClient = null;
            }
            if (clientManager != null) {
                clientManager.close();
                clientManager = null;
            }
        });
    }

    @Override
    public String getId() {
        return "default";
    }

    @Override
    public Map<String, String> getOperationalInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("implementation", "Redis (Lettuce)");

        if (connectionProvider != null) {
            TopologyInfo topology = connectionProvider.getTopologyInfo();
            info.put("nodeName", topology.getMyNodeName());
            if (topology.getMySiteName() != null) {
                info.put("siteName", topology.getMySiteName());
            }
            info.put("healthy", String.valueOf(connectionProvider.isHealthy()));
        }

        return info;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

    /**
     * Run task with read lock to prevent deadlocks during shutdown.
     */
    public static void runWithReadLock(Runnable task) {
        Lock lock = READ_WRITE_LOCK.readLock();
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run task with write lock during shutdown.
     */
    public static void runWithWriteLock(Runnable task) {
        Lock lock = READ_WRITE_LOCK.writeLock();
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
