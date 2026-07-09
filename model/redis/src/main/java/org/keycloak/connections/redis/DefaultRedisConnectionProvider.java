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

import org.keycloak.cache.redis.HashCacheAdapter;
import org.keycloak.cache.redis.L1InvalidationBus;
import org.keycloak.cache.redis.L1RedisCache;
import org.keycloak.cache.redis.LettuceCacheAdapter;
import org.keycloak.cache.redis.LuaScripts;
import org.keycloak.cache.redis.NoOpRedisCache;
import org.keycloak.cache.redis.PipelinedRedisCache;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.cache.redis.RedisMetrics;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.Set;
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
    private final RedissonClient redissonClient;
    private final TopologyInfo topologyInfo;
    private final Map<String, RedisCache<?, ?>> caches;
    private final Map<String, HashCacheAdapter<?>> hashCaches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduledExecutor;
    private final L1InvalidationBus l1Bus;
    private final L1RedisCache.L1Config l1Config;
    private final LuaScripts luaScripts;
    private final PipelinedRedisCache pipelinedCache;
    private final RedisMetrics metrics;

    /**
     * Caches with unique-per-request keys (auth flows, action tokens, etc.) get
     * zero L1 hit benefit — every entry is read once, written once — and pay the
     * pub/sub publish cost per write. Skip the L1 wrapper for them so the L2 path
     * stays as cheap as possible.
     *
     * <p>Read-mostly caches (realms, users, clients, keys, authorization) stay on
     * L1 — that's where the L1 hit rate is high and the win is real.
     */
    private static final Set<String> L1_SKIP_PREFIXES = Set.of(
            "sessions",                  // user sessions (also delegated to JPA in our impl)
            "clientSessions",            // client sessions
            "offlineSessions",
            "offlineClientSessions",
            "authenticationSessions",    // auth sessions: ephemeral, unique per login
            "actionTokens",              // single-use tokens
            "loginFailures",             // brute-force counters: tiny + ephemeral
            "work"                       // cluster work cache: pure messaging
    );

    private static boolean shouldSkipL1(String cacheName) {
        if (cacheName == null) return true;
        for (String prefix : L1_SKIP_PREFIXES) {
            if (cacheName.equals(prefix) || cacheName.startsWith(prefix + ":")) return true;
        }
        return false;
    }

    /**
     * Caches whose values reference {@link org.keycloak.models.cache.redis.DefaultLazyLoader}
     * — which holds non-Serializable lambda fields ({@code Function}/{@code Supplier}). These
     * cannot pass through {@link LettuceCacheAdapter}'s Java-native serialization, and
     * Protostream isn't an option either because the Cached* entities don't carry
     * {@code @ProtoField} annotations.
     *
     * <p>The pragmatic architecture: keep them L1-only (Caffeine in-JVM) with cross-node
     * invalidation via Redis pub/sub. Each pod loads from JPA on miss, exactly the behavior
     * Infinispan provides for its local cache mode. PostgreSQL remains the source of truth.
     *
     * <p>The {@link L1RedisCache} wrapper still applies — it provides the Caffeine layer and
     * the bus subscription. Only the L2 delegate is swapped to {@link NoOpRedisCache}.
     */
    private static final Set<String> L1_ONLY_PREFIXES = Set.of(
            "realms", "realmRevisions",
            "users", "userRevisions",
            "authorization", "authorizationRevisions",
            "keys", "crl"
    );

    private static boolean shouldUseL1Only(String cacheName) {
        if (cacheName == null) return false;
        for (String prefix : L1_ONLY_PREFIXES) {
            if (cacheName.equals(prefix) || cacheName.startsWith(prefix + ":")) return true;
        }
        return false;
    }

    public DefaultRedisConnectionProvider(RedisClientManager clientManager,
                                          RedissonClient redissonClient,
                                          TopologyInfo topologyInfo,
                                          L1InvalidationBus l1Bus,
                                          L1RedisCache.L1Config l1Config,
                                          LuaScripts luaScripts,
                                          PipelinedRedisCache pipelinedCache,
                                          RedisMetrics metrics) {
        this.clientManager = clientManager;
        this.redissonClient = redissonClient;
        this.topologyInfo = topologyInfo;
        this.caches = new ConcurrentHashMap<>();
        this.scheduledExecutor = Executors.newScheduledThreadPool(2);
        this.l1Bus = l1Bus;
        this.l1Config = l1Config;
        this.luaScripts = luaScripts;
        this.pipelinedCache = pipelinedCache;
        this.metrics = metrics;
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
            // L1-only path: read-mostly caches whose entities reference DefaultLazyLoader
            // (lambda fields → not Java-serializable). Caffeine in-JVM + cross-pod
            // invalidation via the L1 bus; PostgreSQL is source of truth, each pod loads
            // from JPA on cache miss. Same model as Infinispan's local cache mode.
            if (shouldUseL1Only(cacheName) && l1Bus != null) {
                NoOpRedisCache<K, V> noOpL2 = new NoOpRedisCache<>(cacheName);
                return new L1RedisCache<>(noOpL2,
                        // Synthesize a stable byte key for the L1 layer / invalidation channel.
                        // Java toString is enough — keys are realm/user/role IDs (UUIDs/strings).
                        k -> (cacheName + ":" + k).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        l1Bus, l1Config, metrics);
            }

            LettuceCacheAdapter<K, V> l2 = new LettuceCacheAdapter<>(cacheName, clientManager, metrics, luaScripts);
            // L1 bypass for: cluster mode (no shared bus), or caches with unique-per-request
            // keys where L1 only adds publish overhead without hit benefit.
            if (l1Bus == null || shouldSkipL1(cacheName)) return l2;
            return new L1RedisCache<>(l2, l2::toRedisKey, l1Bus, l1Config, metrics);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K> HashCacheAdapter<K> getHashCache(String name) {
        return (HashCacheAdapter<K>) hashCaches.computeIfAbsent(name,
                cacheName -> new HashCacheAdapter<K>(cacheName, clientManager, metrics));
    }

    @Override
    public LuaScripts getLuaScripts() {
        return luaScripts;
    }

    @Override
    public PipelinedRedisCache.Batch beginPipelineBatch() {
        return pipelinedCache.beginBatch();
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
    public RedissonClient getRedissonClient() {
        return redissonClient;
    }

    @Override
    public void close() {
        // Provider instances are per-session but the underlying connections are managed
        // by the factory singleton. Do NOT close connections here - the factory handles lifecycle.
    }
}
