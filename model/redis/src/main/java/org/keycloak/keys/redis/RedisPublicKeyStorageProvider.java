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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.keycloak.common.util.Time;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;

import com.github.benmanes.caffeine.cache.Cache;
import org.jboss.logging.Logger;

/**
 * Redis-mode public key storage. Functionally identical to the Infinispan provider
 * (the same fetch/dedup/TTL logic and {@link PublicKeysEntry}), but backed by a per-node
 * Caffeine cache instead of an Infinispan cache.
 *
 * <p>Public keys are fetched from an external JWKS/metadata endpoint and cached with a TTL;
 * a per-node cache is the correct shape and what the Infinispan local cache provides in
 * practice. Cross-node invalidation (Infinispan's ClusterProvider notify on key change) is
 * intentionally dropped here: freshness comes from the per-entry expiration checked on read
 * plus {@code minTimeBetweenRequests}/{@code maxCacheTime}. Cross-node eviction is a possible
 * future optimization, not a correctness requirement. See docs/adr/0004.
 */
public class RedisPublicKeyStorageProvider implements PublicKeyStorageProvider {

    private static final Logger log = Logger.getLogger(RedisPublicKeyStorageProvider.class);

    private final Cache<String, PublicKeysEntry> keys;

    private final Map<String, FutureTask<PublicKeysEntry>> tasksInProgress;

    private final int minTimeBetweenRequests;
    private final int maxCacheTime;

    public RedisPublicKeyStorageProvider(Cache<String, PublicKeysEntry> keys,
                                         Map<String, FutureTask<PublicKeysEntry>> tasksInProgress,
                                         int minTimeBetweenRequests, int maxCacheTime) {
        this.keys = keys;
        this.tasksInProgress = tasksInProgress;
        this.minTimeBetweenRequests = minTimeBetweenRequests;
        this.maxCacheTime = maxCacheTime;
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, String algorithm, PublicKeyLoader loader) {
        return getPublicKey(modelKey, null, algorithm, loader);
    }

    @Override
    public KeyWrapper getPublicKey(String modelKey, String kid, String algorithm, PublicKeyLoader loader) {
        PublicKeysEntry entry = keys.getIfPresent(modelKey);
        int lastRequestTime = entry == null ? 0 : entry.getLastRequestTime();
        int currentTime = Time.currentTime();
        boolean isSendingRequestAllowed = currentTime > lastRequestTime + minTimeBetweenRequests;

        // Check if key is in cache, but only if KID is provided or if the key cache has been loaded recently,
        // in order to get a key based on partial match with alg param.
        if (!isExpired(entry, currentTime) && (kid != null || !isSendingRequestAllowed)) {
            KeyWrapper publicKey = entry.getCurrentKeys().getKeyByKidAndAlg(kid, algorithm);
            if (publicKey != null) {
                return publicKey.cloneKey();
            }
        }

        PublicKeysEntry updatedEntry = reloadKeys(modelKey, entry, currentTime, loader);
        entry = updatedEntry == null ? entry : updatedEntry;
        KeyWrapper publicKey = entry == null ? null : entry.getCurrentKeys().getKeyByKidAndAlg(kid, algorithm);
        if (publicKey != null) {
            return publicKey.cloneKey();
        }

        List<String> availableKids = entry == null ? Collections.emptyList() : entry.getCurrentKeys().getKids();
        log.warnf("PublicKey wasn't found in the storage. Requested kid: '%s' . Available kids: '%s'", kid, availableKids);

        return null;
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, Predicate<KeyWrapper> predicate, PublicKeyLoader loader) {
        PublicKeysEntry entry = keys.getIfPresent(modelKey);
        int currentTime = Time.currentTime();
        if (!isExpired(entry, currentTime)) {
            KeyWrapper key = entry.getCurrentKeys().getKeyByPredicate(predicate);
            if (key != null) {
                return key.cloneKey();
            }
        }
        entry = reloadKeys(modelKey, entry, currentTime, loader);
        if (entry != null) {
            KeyWrapper key = entry.getCurrentKeys().getKeyByPredicate(predicate);
            if (key != null) {
                return key.cloneKey();
            }
        }
        return null;
    }

    @Override
    public List<KeyWrapper> getKeys(String modelKey, PublicKeyLoader loader) {
        PublicKeysEntry entry = keys.getIfPresent(modelKey);
        int currentTime = Time.currentTime();

        if (isExpired(entry, currentTime) || (hasNoExpiration(entry) && currentTime > entry.getLastRequestTime() + maxCacheTime)) {
            PublicKeysEntry updatedEntry = reloadKeys(modelKey, entry, currentTime, loader);
            if (updatedEntry != null) {
                entry = updatedEntry;
            }
        }

        return entry == null
                ? Collections.emptyList()
                : entry.getCurrentKeys().getKeys().stream().map(KeyWrapper::cloneKey).collect(Collectors.toList());
    }

    @Override
    public boolean reloadKeys(String modelKey, PublicKeyLoader loader) {
        PublicKeysEntry entry = keys.getIfPresent(modelKey);
        int currentTime = Time.currentTime();
        return reloadKeys(modelKey, entry, currentTime, loader) != null;
    }

    private boolean hasNoExpiration(PublicKeysEntry entry) {
        return entry == null || entry.getCurrentKeys().getExpirationTime() == null;
    }

    private boolean isExpired(PublicKeysEntry entry, int currentTime) {
        if (entry == null) {
            return true;
        }

        if (entry.getCurrentKeys().getExpirationTime() != null) {
            return currentTime > TimeUnit.MILLISECONDS.toSeconds(entry.getCurrentKeys().getExpirationTime());
        }

        return false;
    }

    private PublicKeysEntry reloadKeys(String modelKey, PublicKeysEntry entry, int currentTime, PublicKeyLoader loader) {
        if (entry == null || currentTime > entry.getLastRequestTime() + minTimeBetweenRequests) {
            WrapperCallable wrapperCallable = new WrapperCallable(modelKey, loader);
            FutureTask<PublicKeysEntry> task = new FutureTask<>(wrapperCallable);
            FutureTask<PublicKeysEntry> existing = tasksInProgress.putIfAbsent(modelKey, task);

            if (existing == null) {
                log.debugf("Reloading keys for model key '%s'.", modelKey);
                task.run();
            } else {
                task = existing;
            }

            try {
                return task.get();
            } catch (ExecutionException ee) {
                throw new RuntimeException("Error when loading public keys: " + ee.getMessage(), ee);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Error. Interrupted when loading public keys", ie);
            } finally {
                if (existing == null) {
                    tasksInProgress.remove(modelKey);
                }
            }
        } else {
            log.warnf("Won't load the keys for model '%s'. Last request time was %d", modelKey, entry.getLastRequestTime());
        }
        return null;
    }

    @Override
    public void close() {
    }

    private class WrapperCallable implements Callable<PublicKeysEntry> {

        private final String modelKey;
        private final PublicKeyLoader delegate;

        public WrapperCallable(String modelKey, PublicKeyLoader delegate) {
            this.modelKey = modelKey;
            this.delegate = delegate;
        }

        @Override
        public PublicKeysEntry call() throws Exception {
            PublicKeysEntry entry = keys.getIfPresent(modelKey);

            int lastRequestTime = entry == null ? 0 : entry.getLastRequestTime();
            int currentTime = Time.currentTime();

            if (currentTime > lastRequestTime + minTimeBetweenRequests) {
                PublicKeysWrapper publicKeys = delegate.loadKeys();

                if (log.isDebugEnabled()) {
                    log.debugf("Public keys retrieved successfully for model %s. New kids: %s", modelKey, publicKeys.getKids());
                }

                entry = new PublicKeysEntry(currentTime, publicKeys);
                keys.put(modelKey, entry);
            }
            return entry;
        }
    }
}
