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
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ServerInfoAwareProviderFactory;
import org.redisson.api.RedissonClient;

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
        implements RedisConnectionProviderFactory, ServerInfoAwareProviderFactory {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);
    private static final ReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();

    private Config.Scope config;
    private volatile RedisConnectionProvider connectionProvider;
    private volatile RedisClientManager clientManager;
    private volatile RedissonClient redissonClient;

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
            String connectionUri = config.get("connectionUri", "redis://localhost:6379");
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

            // Create provider
            this.connectionProvider = new DefaultRedisConnectionProvider(clientManager, redissonClient, topologyInfo);

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
