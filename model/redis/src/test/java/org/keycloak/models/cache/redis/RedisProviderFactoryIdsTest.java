/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.models.cache.redis;

import org.junit.Test;
import org.keycloak.models.cache.redis.authorization.RedisCacheStoreFactoryProviderFactory;
import org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory;
import org.keycloak.models.sessions.redis.RedisSingleUseObjectProviderFactory;
import org.keycloak.models.sessions.redis.RedisStickySessionEncoderProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserLoginFailureProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserSessionProviderFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Regression test for the iter-6 critical bug where Redis cache provider factories
 * shared {@code getId() == "default"} with their Infinispan counterparts.
 *
 * <p>Background: {@link org.keycloak.provider.ProviderManager#load} dedup keys
 * factories by {@code spi-name + "-" + factory-id}. When two factories share the
 * same dedup key, {@code compareFactories} silently picks one based on order/internal
 * flags and discards the other — <em>before</em> {@code isSupported(Config.Scope)}
 * is consulted at build time. The losing factory's {@code isSupported} guard is
 * therefore a no-op.
 *
 * <p>Concretely, every Redis cache-style factory ({@code RealmCache}, {@code UserCache},
 * {@code AuthorizationCache}) was being silently dropped in favor of the Infinispan
 * factory of the same SPI, even when {@code KC_CACHE=redis} was set.
 *
 * <p>Fix: Redis factories must use a {@code getId()} value distinct from {@code "default"}
 * (Infinispan's value). This test fails if anyone reverts that change.
 */
public class RedisProviderFactoryIdsTest {

    // Iter-7 restored the "redis" ids for realm/user/authorization. The L1-only routing
    // in DefaultRedisConnectionProvider sidesteps the DefaultLazyLoader serialization cascade.

    @Test
    public void redisCacheRealmProviderFactory_idIsRedis_notDefault() {
        assertThat(new RedisCacheRealmProviderFactory().getId(), equalTo("redis"));
        assertThat(new RedisCacheRealmProviderFactory().getId(), not(equalTo("default")));
    }

    @Test
    public void redisUserCacheProviderFactory_idIsRedis_notDefault() {
        assertThat(new RedisUserCacheProviderFactory().getId(), equalTo("redis"));
        assertThat(new RedisUserCacheProviderFactory().getId(), not(equalTo("default")));
    }

    @Test
    public void redisCacheStoreFactoryProviderFactory_idIsRedis_notDefault() {
        assertThat(new RedisCacheStoreFactoryProviderFactory().getId(), equalTo("redis"));
        assertThat(new RedisCacheStoreFactoryProviderFactory().getId(), not(equalTo("default")));
    }

    @Test
    public void redisAuthenticationSessionProviderFactory_idIsRedis() {
        assertThat(new RedisAuthenticationSessionProviderFactory().getId(), equalTo("redis"));
    }

    @Test
    public void redisSingleUseObjectProviderFactory_idIsRedis() {
        assertThat(new RedisSingleUseObjectProviderFactory().getId(), equalTo("redis"));
    }

    @Test
    public void redisUserSessionProviderFactory_idIsRedis() {
        assertThat(new RedisUserSessionProviderFactory().getId(), equalTo("redis"));
    }

    @Test
    public void redisUserLoginFailureProviderFactory_idIsRedis() {
        assertThat(new RedisUserLoginFailureProviderFactory().getId(), equalTo("redis"));
    }

    @Test
    public void redisStickySessionEncoderProviderFactory_idIsRedis() {
        assertThat(new RedisStickySessionEncoderProviderFactory().getId(), equalTo("redis"));
    }
}
