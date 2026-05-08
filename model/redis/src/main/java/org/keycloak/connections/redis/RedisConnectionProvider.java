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

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import org.keycloak.cache.redis.HashCacheAdapter;
import org.keycloak.cache.redis.LuaScripts;
import org.keycloak.cache.redis.PipelinedRedisCache;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.provider.Provider;

/**
 * Redis Connection Provider SPI - provides access to Redis caches and cluster coordination primitives.
 * This is the parallel implementation to InfinispanConnectionProvider for Redis-based caching.
 *
 * @author Keycloak Redis Team
 */
public interface RedisConnectionProvider extends Provider {

    // Cache names - must match InfinispanConnectionProvider for compatibility
    String REALM_CACHE_NAME = "realms";
    String REALM_REVISIONS_CACHE_NAME = "realmRevisions";
    int REALM_REVISIONS_CACHE_DEFAULT_MAX = 20000;

    String USER_CACHE_NAME = "users";
    String USER_REVISIONS_CACHE_NAME = "userRevisions";
    int USER_REVISIONS_CACHE_DEFAULT_MAX = 100000;

    String USER_SESSION_CACHE_NAME = "sessions";
    String CLIENT_SESSION_CACHE_NAME = "clientSessions";
    String OFFLINE_USER_SESSION_CACHE_NAME = "offlineSessions";
    String OFFLINE_CLIENT_SESSION_CACHE_NAME = "offlineClientSessions";
    String LOGIN_FAILURE_CACHE_NAME = "loginFailures";
    String AUTHENTICATION_SESSIONS_CACHE_NAME = "authenticationSessions";
    String WORK_CACHE_NAME = "work";
    String AUTHORIZATION_CACHE_NAME = "authorization";
    String AUTHORIZATION_REVISIONS_CACHE_NAME = "authorizationRevisions";
    int AUTHORIZATION_REVISIONS_CACHE_DEFAULT_MAX = 20000;
    int SESSIONS_CACHE_DEFAULT_MAX = 10000;

    String ACTION_TOKEN_CACHE = "actionTokens";
    int ACTION_TOKEN_CACHE_DEFAULT_MAX = -1;
    int ACTION_TOKEN_MAX_IDLE_SECONDS = -1;
    long ACTION_TOKEN_WAKE_UP_INTERVAL_SECONDS = 5 * 60 * 1000L;

    String KEYS_CACHE_NAME = "keys";
    int KEYS_CACHE_DEFAULT_MAX = 1000;
    int KEYS_CACHE_MAX_IDLE_SECONDS = 3600;

    String CRL_CACHE_NAME = "crl";
    int CRL_CACHE_DEFAULT_MAX = 1000;

    // Node identification
    String NODE_PREFIX = "node_";

    // Cache categories for configuration
    String[] LOCAL_CACHE_NAMES = {
            REALM_CACHE_NAME,
            REALM_REVISIONS_CACHE_NAME,
            USER_CACHE_NAME,
            USER_REVISIONS_CACHE_NAME,
            AUTHORIZATION_CACHE_NAME,
            AUTHORIZATION_REVISIONS_CACHE_NAME,
            KEYS_CACHE_NAME,
            CRL_CACHE_NAME,
    };

    String[] CLUSTERED_CACHE_NAMES = {
            USER_SESSION_CACHE_NAME,
            CLIENT_SESSION_CACHE_NAME,
            OFFLINE_USER_SESSION_CACHE_NAME,
            OFFLINE_CLIENT_SESSION_CACHE_NAME,
            LOGIN_FAILURE_CACHE_NAME,
            AUTHENTICATION_SESSIONS_CACHE_NAME,
            ACTION_TOKEN_CACHE,
            WORK_CACHE_NAME
    };

    String[] ALL_CACHES_NAME = Stream.concat(
            Stream.of(LOCAL_CACHE_NAMES),
            Stream.of(CLUSTERED_CACHE_NAMES)
    ).toArray(String[]::new);

    /**
     * Get a cache instance by name. Creates the cache if it doesn't exist.
     *
     * @param name cache name
     * @param <K> key type
     * @param <V> value type
     * @return cache instance
     */
    default <K, V> RedisCache<K, V> getCache(String name) {
        return getCache(name, true);
    }

    /**
     * Get a cache instance by name.
     *
     * @param name cache name
     * @param createIfAbsent if true, creates the cache if it doesn't exist
     * @param <K> key type
     * @param <V> value type
     * @return cache instance, or null if createIfAbsent is false and cache doesn't exist
     */
    <K, V> RedisCache<K, V> getCache(String name, boolean createIfAbsent);

    /**
     * Get a hash-shaped cache adapter ({@code HSET / HGETALL} storage) by name.
     *
     * <p>Use this instead of {@link #getCache} when entities map naturally to a
     * field-keyed structure (e.g. login-failure counters, session attributes).
     * Field-level updates avoid the read-modify-write of opaque-value caches.
     *
     * @param name cache name (matches the Redis key prefix)
     * @return hash cache adapter
     */
    <K> HashCacheAdapter<K> getHashCache(String name);

    /**
     * Get the Lua script holder for atomic compare-and-set operations.
     * Scripts are loaded once per server; subsequent calls use {@code EVALSHA}.
     *
     * @return Lua script holder, or null if not available
     */
    LuaScripts getLuaScripts();

    /**
     * Begin a pipelined batch of Redis writes. Use within a try-with-resources
     * block. The batch returns immediately on each call and awaits all responses
     * on close — collapsing N round-trips into one network window.
     *
     * @return a fresh pipeline batch
     */
    PipelinedRedisCache.Batch beginPipelineBatch();

    /**
     * Get topology information for cluster awareness.
     *
     * @return topology information
     */
    TopologyInfo getTopologyInfo();

    /**
     * Get an executor for running blocking operations.
     * This should be used for I/O operations that should not block the event loop.
     *
     * @param name name for tracing/logging
     * @return executor for blocking operations
     */
    Executor getExecutor(String name);

    /**
     * Get a scheduled executor service for scheduling tasks.
     *
     * @return scheduled executor service
     */
    ScheduledExecutorService getScheduledExecutorService();

    /**
     * Check if the provider is healthy and connected to Redis.
     *
     * @return true if healthy, false otherwise
     */
    boolean isHealthy();

    /**
     * Get the Redisson client for distributed primitives (locks, pub/sub, etc.).
     * This is used by the cluster provider for distributed coordination.
     *
     * @return Redisson client instance, or null if not available
     */
    org.redisson.api.RedissonClient getRedissonClient();
}
