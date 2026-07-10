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
package org.keycloak.crl.redis;

import org.keycloak.cluster.ClusterProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.CacheCrlProvider;
import org.keycloak.models.cache.redis.ClearCacheEvent;

/**
 * Backs the admin "clear CRL cache" action under {@code KC_CACHE=redis}.
 *
 * <p>Cached CRLs live in a per-node Caffeine cache, so clearing has to reach every node: drop
 * ours, then publish over the Redis-backed cluster bus so the others drop theirs. An admin
 * clearing this cache is usually reacting to a freshly revoked certificate, so a node that
 * kept its stale CRL would keep accepting that certificate until the entry expired.
 */
public class RedisCacheCrlProvider implements CacheCrlProvider {

    private final KeycloakSession session;
    private final RedisCrlStorageProviderFactory storage;

    public RedisCacheCrlProvider(KeycloakSession session, RedisCrlStorageProviderFactory storage) {
        this.session = session;
        this.storage = storage;
    }

    @Override
    public void clearCache() {
        storage.clearCache();
        session.getProvider(ClusterProvider.class).notify(
                RedisCrlStorageProviderFactory.CRL_CLEAR_CACHE_EVENTS,
                ClearCacheEvent.getInstance(),
                true);
    }

    @Override
    public void close() {
    }
}
