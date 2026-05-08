/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.models.cache.redis;

import org.keycloak.cache.redis.RedisCache;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.cache.CacheRealmProviderFactory;
import org.keycloak.models.cache.redis.entities.Revisioned;
import org.keycloak.models.cache.redis.events.InvalidationEvent;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class RedisCacheRealmProviderFactory implements CacheRealmProviderFactory, EnvironmentDependentProviderFactory {

    private static final Logger log = Logger.getLogger(RedisCacheRealmProviderFactory.class);
    public static final String REALM_CLEAR_CACHE_EVENTS = "REALM_CLEAR_CACHE_EVENTS";
    public static final String REALM_INVALIDATION_EVENTS = "REALM_INVALIDATION_EVENTS";

    protected volatile RealmCacheManager realmCache;

    @Override
    public CacheRealmProvider create(KeycloakSession session) {
        lazyInit(session);
        return new RealmCacheSession(realmCache, session);
    }

    private void lazyInit(KeycloakSession session) {
        if (realmCache == null) {
            synchronized (this) {
                if (realmCache == null) {
                    RedisCache<String, Revisioned> cache = session.getProvider(RedisConnectionProvider.class).getCache(RedisConnectionProvider.REALM_CACHE_NAME);
                    RedisCache<String, Long> revisions = session.getProvider(RedisConnectionProvider.class).getCache(RedisConnectionProvider.REALM_REVISIONS_CACHE_NAME);
                    realmCache = new RealmCacheManager(cache, revisions);

                    ClusterProvider cluster = session.getProvider(ClusterProvider.class);
                    cluster.registerListener(REALM_INVALIDATION_EVENTS, (ClusterEvent event) -> {

                        InvalidationEvent invalidationEvent = (InvalidationEvent) event;
                        realmCache.invalidationEventReceived(invalidationEvent);

                    });

                    cluster.registerListener(REALM_CLEAR_CACHE_EVENTS, (ClusterEvent event) -> {

                        realmCache.clear();

                    });

                    log.debug("Registered cluster listeners");
                }
            }
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
        // KNOWN ISSUE — temporarily returning "default" so this factory loses ProviderManager
        // dedup against InfinispanCacheRealmProviderFactory (Infinispan keeps serving realm
        // cache even when KC_CACHE=redis). This was iter-6's correct fix, BUT it activated
        // a deeper bug: CachedRealmRole and other entities reference DefaultLazyLoader which
        // holds non-Serializable Function/Supplier lambda fields, so the LettuceCacheAdapter's
        // Java-native serialization fails at startup with "Failed to serialize object:
        // org.keycloak.models.cache.redis.entities.CachedRealmRole".
        //
        // Real fix (iter-7 scope): register Cached* entities in RedisModelSchema and serialize
        // via Protostream instead of Java native. That's how Infinispan does it. Tracked in
        // docs/redis-iterations/iteration-6-prometheus-metrics.md "Known issues".
        //
        // Auth sessions, single-use objects, login failures, user sessions still go to Redis
        // correctly because their factories use getId()="redis" (different from Infinispan's
        // "infinispan") — they survive ProviderManager dedup naturally. Only the three cache
        // providers (realm, user, authorization) use the "default" id and need the iter-7 work.
        return "default";
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

}
