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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Periodic database purge of expired sessions ({@code UserSessionPersisterProvider.removeExpired(realm)}).
 * Cluster distribution uses a per-realm Redis lease (SET NX + TTL): the holder purges the realm,
 * everyone else skips it. The JPA deletes are idempotent, so a double purge (e.g. lease lost
 * mid-run, or Redis unreachable — we fail open) is harmless.
 * Self-contained: owns a single daemon scheduler thread, no Infinispan dependency.
 */
class RedisExpirationTask {

    private static final Logger logger = Logger.getLogger(RedisExpirationTask.class);
    private static final String LEASE_KEY_PREFIX = "sessionExpirationLease:";

    private final KeycloakSessionFactory factory;
    private final RedisCache<String, String> leaseCache;
    private final String nodeId;
    private final int periodSeconds;
    private final long leaseSeconds;
    private volatile ScheduledExecutorService scheduler;

    RedisExpirationTask(KeycloakSessionFactory factory, int periodSeconds,
                        RedisCache<String, String> leaseCache, String nodeId) {
        this.factory = factory;
        this.leaseCache = leaseCache;
        this.nodeId = nodeId;
        this.periodSeconds = periodSeconds;
        this.leaseSeconds = Math.max(2L * periodSeconds, 60L);
    }

    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-session-expiration");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::purgeExpired, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void purgeExpired() {
        try {
            KeycloakModelUtils.runJobInTransaction(factory, session -> {
                UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
                if (persister == null) {
                    return;
                }
                session.realms().getRealmsStream()
                        .filter(this::holdsLease)
                        .forEach(persister::removeExpired);
            });
        } catch (Throwable t) {
            logger.error("Unexpected error while removing expired sessions from database", t);
        }
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
