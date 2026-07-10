/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.testsuite.model.parameters;

import java.util.Set;

import org.keycloak.cluster.redis.RedisClusterProviderFactory;
import org.keycloak.connections.redis.DefaultRedisConnectionProviderFactory;
import org.keycloak.connections.redis.RedisConnectionSpi;
import org.keycloak.crl.CrlStorageSpi;
import org.keycloak.crl.redis.RedisCrlStorageProviderFactory;
import org.keycloak.keys.PublicKeyStorageSpi;
import org.keycloak.keys.redis.RedisPublicKeyStorageProviderFactory;
import org.keycloak.models.RevokedTokenSpi;
import org.keycloak.models.SingleUseObjectSpi;
import org.keycloak.models.cache.CacheRealmProviderSpi;
import org.keycloak.models.cache.CacheUserProviderSpi;
import org.keycloak.models.cache.authorization.CachedStoreFactorySpi;
import org.keycloak.models.cache.redis.RedisCacheRealmProviderFactory;
import org.keycloak.models.cache.redis.RedisUserCacheProviderFactory;
import org.keycloak.models.cache.redis.authorization.RedisCacheStoreFactoryProviderFactory;
import org.keycloak.models.session.UserSessionPersisterSpi;
import org.keycloak.models.sessions.infinispan.InfinispanRevokedTokenProviderFactory;
import org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory;
import org.keycloak.models.sessions.redis.RedisSingleUseObjectProviderFactory;
import org.keycloak.models.sessions.redis.RedisStickySessionEncoderProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserLoginFailureProviderFactory;
import org.keycloak.models.sessions.redis.RedisUserSessionProviderFactory;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;
import org.keycloak.sessions.AuthenticationSessionSpi;
import org.keycloak.sessions.StickySessionEncoderSpi;
import org.keycloak.testsuite.model.Config;
import org.keycloak.testsuite.model.KeycloakModelParameters;
import org.keycloak.timer.TimerProviderFactory;

import com.google.common.collect.ImmutableSet;

/**
 * Runs the model testsuite against Locke's Redis cache backend (KC_CACHE=redis) — the
 * behavioral-parity gate from docs/adr/0004: the same conformance tests upstream runs
 * against Infinispan, executed against the Redis providers. Combine with Jpa:
 * {@code -Dkeycloak.model.parameters=Redis,Jpa}.
 *
 * <p>Locke's {@code isSupported()} guards read {@code config.root().get("cache")}, which in
 * this harness resolves to the {@code keycloak.cache} system property — set in
 * {@link #beforeSuite} so the Redis factories activate and the Infinispan ones filter out.
 *
 * <p>Needs a reachable Redis; override with
 * {@code -Dkeycloak.connectionsRedis.url=redis://host:port} (default localhost:6379).
 */
public class Redis extends KeycloakModelParameters {

    static final Set<Class<? extends Spi>> ALLOWED_SPIS = ImmutableSet.<Class<? extends Spi>>builder()
            .add(AuthenticationSessionSpi.class)
            .add(CacheRealmProviderSpi.class)
            .add(CachedStoreFactorySpi.class)
            .add(CacheUserProviderSpi.class)
            .add(RedisConnectionSpi.class)
            .add(StickySessionEncoderSpi.class)
            .add(UserSessionPersisterSpi.class)
            .add(SingleUseObjectSpi.class)
            .add(PublicKeyStorageSpi.class)
            .add(CrlStorageSpi.class)
            .add(RevokedTokenSpi.class)
            .build();

    static final Set<Class<? extends ProviderFactory>> ALLOWED_FACTORIES = ImmutableSet.<Class<? extends ProviderFactory>>builder()
            .add(DefaultRedisConnectionProviderFactory.class)
            .add(RedisClusterProviderFactory.class)
            .add(RedisCacheRealmProviderFactory.class)
            .add(RedisUserCacheProviderFactory.class)
            .add(RedisCacheStoreFactoryProviderFactory.class)
            .add(RedisUserSessionProviderFactory.class)
            .add(RedisUserLoginFailureProviderFactory.class)
            .add(RedisSingleUseObjectProviderFactory.class)
            .add(RedisAuthenticationSessionProviderFactory.class)
            .add(RedisStickySessionEncoderProviderFactory.class)
            .add(RedisPublicKeyStorageProviderFactory.class)
            .add(RedisCrlStorageProviderFactory.class)
            // Despite the name, this factory holds no Infinispan state: it wraps whichever
            // SingleUseObjectProvider is registered, which under redis is ours. Upstream
            // parked it in model/infinispan; it stays enabled under redis, so mirror that here.
            .add(InfinispanRevokedTokenProviderFactory.class)
            .add(TimerProviderFactory.class)
            .build();

    public Redis() {
        super(ALLOWED_SPIS, ALLOWED_FACTORIES);
    }

    @Override
    public void beforeSuite(Config cf) {
        // Activates every Locke isSupported() guard: config.root() here is the
        // "keycloak."-prefixed system-property scope.
        System.setProperty("keycloak.cache", "redis");
    }

    @Override
    public void afterSuite() {
        System.clearProperty("keycloak.cache");
    }

    @Override
    public void updateConfig(Config cf) {
        cf.spi(RedisConnectionSpi.SPI_NAME)
                .provider("default")
                .config("url", System.getProperty("keycloak.connectionsRedis.url", "redis://localhost:6379"));
    }
}
