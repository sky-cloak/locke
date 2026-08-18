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
package org.keycloak.keys.redis;

import org.keycloak.Config;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.CachePublicKeyProvider;
import org.keycloak.models.cache.CachePublicKeyProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderFactory;

/**
 * Redis counterpart of {@code InfinispanCachePublicKeyProviderFactory}. Without it the only
 * factory for this SPI is the Infinispan one, which is disabled under redis, so
 * {@code session.getProvider(CachePublicKeyProvider.class)} returns null and the admin
 * "clear keys cache" endpoint quietly does nothing (see docs/adr/0004).
 */
public class RedisCachePublicKeyProviderFactory implements CachePublicKeyProviderFactory, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "redis";

    private volatile boolean listenerRegistered;

    @Override
    public CachePublicKeyProvider create(KeycloakSession session) {
        RedisPublicKeyStorageProviderFactory storage = storageFactory(session);
        registerClearListener(session, storage);
        return new RedisCachePublicKeyProvider(session, storage);
    }

    private static RedisPublicKeyStorageProviderFactory storageFactory(KeycloakSession session) {
        ProviderFactory<PublicKeyStorageProvider> factory = session.getKeycloakSessionFactory()
                .getProviderFactory(PublicKeyStorageProvider.class);
        if (!(factory instanceof RedisPublicKeyStorageProviderFactory redisFactory)) {
            throw new IllegalStateException("Expected the Redis PublicKeyStorageProvider under KC_CACHE=redis, found "
                    + (factory == null ? "none" : factory.getClass().getName()));
        }
        return redisFactory;
    }

    /** Applies a clear that another node initiated. */
    private void registerClearListener(KeycloakSession session, RedisPublicKeyStorageProviderFactory storage) {
        if (listenerRegistered) {
            return;
        }
        synchronized (this) {
            if (listenerRegistered) {
                return;
            }
            session.getProvider(ClusterProvider.class).registerListener(
                    RedisPublicKeyStorageProviderFactory.KEYS_CLEAR_CACHE_EVENTS,
                    event -> storage.clearCache());
            listenerRegistered = true;
        }
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }
}
