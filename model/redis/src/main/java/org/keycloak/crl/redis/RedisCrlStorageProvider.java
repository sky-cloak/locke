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

import java.security.GeneralSecurityException;
import java.security.cert.X509CRL;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.keycloak.common.util.Time;
import org.keycloak.crl.CrlStorageProvider;

import com.github.benmanes.caffeine.cache.Cache;
import org.jboss.logging.Logger;

/**
 * Redis-mode CRL storage. Functionally identical to the Infinispan provider (same
 * load/dedup/next-update logic), but backed by a per-node Caffeine cache. CRLs are fetched
 * from a distribution point and refreshed by their next-update time; a per-node cache is the
 * correct shape. See docs/adr/0004.
 */
public class RedisCrlStorageProvider implements CrlStorageProvider {

    private static final Logger log = Logger.getLogger(RedisCrlStorageProvider.class);

    private final Cache<String, CrlEntry> cache;
    private final Map<String, FutureTask<X509CRL>> tasksInProgress;
    private final long cacheTime;
    private final long minTimeBetweenRequests;

    public RedisCrlStorageProvider(Cache<String, CrlEntry> cache, Map<String, FutureTask<X509CRL>> tasksInProgress,
                                   long cacheTime, long minTimeBetweenRequests) {
        this.cache = cache;
        this.tasksInProgress = tasksInProgress;
        this.cacheTime = cacheTime;
        this.minTimeBetweenRequests = minTimeBetweenRequests;
    }

    @Override
    public X509CRL get(String key, Callable<X509CRL> loader) throws GeneralSecurityException {
        final CrlEntry crlEntry = cache.getIfPresent(key);
        final long currentTime = Time.currentTimeMillis();
        if (crlEntry != null && (crlEntry.crl().getNextUpdate() == null || crlEntry.crl().getNextUpdate().compareTo(new Date(currentTime)) > 0)) {
            log.debugf("returning CRL '%s' from cache because it's cached OK", key);
            return crlEntry.crl();
        }
        return reloadCrl(key, loader, currentTime, crlEntry);
    }

    @Override
    public boolean refreshCache(String key, Callable<X509CRL> loader) throws GeneralSecurityException {
        final CrlEntry entry = cache.getIfPresent(key);
        final X509CRL crl = reloadCrl(key, loader, Time.currentTimeMillis(), entry);
        return crl != null && (entry == null || entry.crl() != crl);
    }

    @Override
    public void close() {
        // no-op
    }

    private X509CRL reloadCrl(String key, Callable<X509CRL> loader, long currentTime, CrlEntry crlEntry) {
        if (crlEntry != null && currentTime < crlEntry.lastRequestTime() + minTimeBetweenRequests) {
            log.debugf("Avoiding loading crl with key '%s' again, last refreshed time %d", key, crlEntry.lastRequestTime());
            return crlEntry.crl();
        }

        FutureTask<X509CRL> task = new FutureTask<>(() -> loadCrl(key, loader, currentTime));

        final FutureTask<X509CRL> existing = tasksInProgress.putIfAbsent(key, task);
        if (existing == null) {
            log.debugf("Reloading crl for model key '%s'.", key);
            task.run();
        } else {
            task = existing;
        }

        try {
            return task.get();
        } catch (ExecutionException ee) {
            throw new RuntimeException("Error when loading crl " + key + " : " + ee.getMessage(), ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error. Interrupted when loading crl " + key, ie);
        } finally {
            if (existing == null) {
                tasksInProgress.remove(key);
            }
        }
    }

    private X509CRL loadCrl(String key, Callable<X509CRL> loader, long currentTime) throws Exception {
        final X509CRL crl = loader.call();
        if (crl == null) {
            log.warnf("Loading crl with key '%s' returned null.", key);
            return null;
        }
        cache.put(key, new CrlEntry(crl, currentTime));
        log.debugf("The crl with key '%s' was retrieved successfully and cached.", key);
        return crl;
    }
}
