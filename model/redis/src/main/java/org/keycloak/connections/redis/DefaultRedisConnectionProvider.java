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

import org.keycloak.cache.redis.RedisCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Default implementation of RedisConnectionProvider.
 * Manages Redis connections and cache instances.
 *
 * @author Keycloak Redis Team
 */
public class DefaultRedisConnectionProvider implements RedisConnectionProvider {

    private final RedisClientManager clientManager;
    private final TopologyInfo topologyInfo;
    private final Map<String, RedisCache<?, ?>> caches;
    private final ScheduledExecutorService scheduledExecutor;

    public DefaultRedisConnectionProvider(RedisClientManager clientManager, TopologyInfo topologyInfo) {
        this.clientManager = clientManager;
        this.topologyInfo = topologyInfo;
        this.caches = new ConcurrentHashMap<>();
        this.scheduledExecutor = Executors.newScheduledThreadPool(2);
    }

    @Override
    public <K, V> RedisCache<K, V> getCache(String name) {
        return getCache(name, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> RedisCache<K, V> getCache(String name, boolean createIfAbsent) {
        if (!createIfAbsent) {
            return (RedisCache<K, V>) caches.get(name);
        }

        return (RedisCache<K, V>) caches.computeIfAbsent(name, cacheName -> {
            // TODO: Create LettuceCacheAdapter here (Milestone 1.3)
            // For now, return null to allow compilation
            throw new UnsupportedOperationException("Cache creation will be implemented in Milestone 1.3");
        });
    }

    @Override
    public TopologyInfo getTopologyInfo() {
        return topologyInfo;
    }

    @Override
    public Executor getExecutor(String name) {
        // Use a cached thread pool for async operations
        return Executors.newCachedThreadPool();
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutor;
    }

    @Override
    public boolean isHealthy() {
        return clientManager.isHealthy();
    }

    @Override
    public void close() {
        // Close all caches
        caches.values().forEach(cache -> {
            // Cache cleanup if needed
        });

        // Shutdown executors
        scheduledExecutor.shutdown();

        // Close client manager
        clientManager.close();
    }
}
