/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.cache.redis;

import org.junit.Test;
import org.keycloak.Config;
import org.keycloak.models.cache.redis.RedisCacheRealmProviderFactory;
import org.keycloak.models.cache.redis.RedisUserCacheProviderFactory;
import org.keycloak.models.cache.redis.authorization.RedisCacheStoreFactoryProviderFactory;
import org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory;
import org.keycloak.models.sessions.redis.RedisSingleUseObjectProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserLoginFailureProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserSessionProviderFactory;
import org.keycloak.connections.redis.DefaultRedisConnectionProviderFactory;
import org.keycloak.cluster.redis.RedisClusterProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies every Redis provider factory's {@code isSupported} returns true
 * when {@code config.root().get("cache") == "redis"} and false otherwise.
 *
 * <p>Combined with {@link RedisProviderFactoryIdsTest}, this proves the
 * end-to-end activation contract: distinct ids let both Infinispan and Redis
 * factories survive {@code ProviderManager} dedup, then {@code isSupported}
 * filters down to exactly one based on {@code KC_CACHE}.
 */
public class IsSupportedGuardsTest {

    /** Build a Config.Scope whose root().get("cache") returns the given value. */
    private static Config.Scope scopeWithCache(String value) {
        Map<String, String> root = value == null ? Map.of() : Map.of("cache", value);
        Config.Scope rootScope = new MapConfigScope(root);
        Config.Scope spiScope = new ChainedRootScope(rootScope, Map.of()); // empty SPI-level config
        return spiScope;
    }

    @Test
    public void redisCacheRealmProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisCacheRealmProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
        assertThat(f.isSupported(scopeWithCache(null)), equalTo(false));
    }

    @Test
    public void redisUserCacheProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisUserCacheProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisCacheStoreFactoryProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisCacheStoreFactoryProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisAuthenticationSessionProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisAuthenticationSessionProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisSingleUseObjectProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisSingleUseObjectProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisUserSessionProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisUserSessionProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisUserLoginFailureProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisUserLoginFailureProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisConnectionProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new DefaultRedisConnectionProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    @Test
    public void redisClusterProvider_supportedOnlyWhenCacheRedis() {
        EnvironmentDependentProviderFactory f = new RedisClusterProviderFactory();
        assertThat(f.isSupported(scopeWithCache("redis")), equalTo(true));
        assertThat(f.isSupported(scopeWithCache("ispn")), equalTo(false));
    }

    // ---- Test scaffolding for Config.Scope ------------------------------------

    /**
     * Test stub Config.Scope. The Config.Scope interface from server-spi-private has
     * a {@code root()} method we must implement. The {@code root} field is the scope
     * returned for {@code root()} — for the SPI-level scope it's the global root
     * (from which factories read {@code config.root().get("cache")}); for the global
     * root scope itself, root() returns {@code this}.
     */
    private static class MapConfigScope implements Config.Scope {
        private final Map<String, String> values;
        private final Config.Scope root;
        MapConfigScope(Map<String, String> values) {
            this.values = values;
            this.root = this; // a root scope is its own root
        }
        @Override public String get(String key) { return values.get(key); }
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public String[] getArray(String key) { return null; }
        @Override public Integer getInt(String key) { return null; }
        @Override public Integer getInt(String key, Integer defaultValue) { return defaultValue; }
        @Override public Long getLong(String key) { return null; }
        @Override public Long getLong(String key, Long defaultValue) { return defaultValue; }
        @Override public Boolean getBoolean(String key) { return null; }
        @Override public Boolean getBoolean(String key, Boolean defaultValue) { return defaultValue; }
        @Override public Config.Scope scope(String... scope) { return this; }
        @Override public java.util.Set<String> getPropertyNames() { return values.keySet(); }
        @Override public Config.Scope root() { return root; }
    }

    /** A Config.Scope whose root() returns a different scope than its own get(). */
    private static class ChainedRootScope implements Config.Scope {
        private final Config.Scope root;
        private final Map<String, String> ownValues;
        ChainedRootScope(Config.Scope root, Map<String, String> ownValues) {
            this.root = root;
            this.ownValues = ownValues;
        }
        @Override public String get(String key) { return ownValues.get(key); }
        @Override public String get(String key, String defaultValue) { return ownValues.getOrDefault(key, defaultValue); }
        @Override public String[] getArray(String key) { return null; }
        @Override public Integer getInt(String key) { return null; }
        @Override public Integer getInt(String key, Integer defaultValue) { return defaultValue; }
        @Override public Long getLong(String key) { return null; }
        @Override public Long getLong(String key, Long defaultValue) { return defaultValue; }
        @Override public Boolean getBoolean(String key) { return null; }
        @Override public Boolean getBoolean(String key, Boolean defaultValue) { return defaultValue; }
        @Override public Config.Scope scope(String... scope) { return this; }
        @Override public java.util.Set<String> getPropertyNames() { return ownValues.keySet(); }
        @Override public Config.Scope root() { return root; }
    }
}
