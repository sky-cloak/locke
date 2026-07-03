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

package org.keycloak.models.sessions.redis;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.infinispan.expiration.BaseExpirationTask;

/**
 * Periodic database purge of expired sessions ({@code persister().removeExpired(realm)}),
 * reusing upstream's {@link BaseExpirationTask} scheduling. Cluster distribution uses a
 * per-realm Redis lease (SET NX + TTL): the holder purges the realm, everyone else skips it.
 * The JPA deletes are idempotent, so a double purge (e.g. lease lost mid-run) is harmless.
 */
class RedisExpirationTask extends BaseExpirationTask {

    private static final Logger logger = Logger.getLogger(RedisExpirationTask.class);
    private static final String LEASE_KEY_PREFIX = "sessionExpirationLease:";

    private final RedisCache<String, String> leaseCache;
    private final String nodeId;
    private final long leaseSeconds;

    RedisExpirationTask(KeycloakSessionFactory factory, ScheduledExecutorService scheduler, int periodSeconds,
                        Consumer<Duration> onTaskExecuted, RedisCache<String, String> leaseCache, String nodeId) {
        super(factory, scheduler, periodSeconds, onTaskExecuted);
        this.leaseCache = leaseCache;
        this.nodeId = nodeId;
        this.leaseSeconds = Math.max(2L * periodSeconds, 60L);
    }

    @Override
    protected Predicate<RealmModel> realmFilter() {
        return this::holdsLease;
    }

    private boolean holdsLease(RealmModel realm) {
        String key = LEASE_KEY_PREFIX + realm.getId();
        try {
            String holder = leaseCache.putIfAbsent(key, nodeId, leaseSeconds, TimeUnit.SECONDS);
            if (holder == null) {
                return true; // acquired
            }
            if (nodeId.equals(holder)) {
                leaseCache.put(key, nodeId, leaseSeconds, TimeUnit.SECONDS); // renew
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            // Fail open: with Redis unreachable every node may purge, but the deletes are idempotent.
            logger.debugf(e, "Could not check expiration lease for realm %s, purging anyway", realm.getId());
            return true;
        }
    }
}
