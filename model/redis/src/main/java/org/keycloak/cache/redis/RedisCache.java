/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.cache.redis;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Redis cache abstraction that provides the same semantics as Infinispan Cache
 * but backed by Redis. This allows Keycloak's caching logic to remain unchanged
 * while swapping out the underlying cache implementation.
 *
 * @param <K> key type
 * @param <V> value type
 * @author Keycloak Redis Team
 */
public interface RedisCache<K, V> {

    /**
     * Get the name of this cache.
     *
     * @return cache name
     */
    String getName();

    /**
     * Get a value from the cache.
     *
     * @param key the key
     * @return the value, or null if not found
     */
    V get(K key);

    /**
     * Put a value into the cache without TTL.
     *
     * @param key the key
     * @param value the value
     * @return the previous value, or null
     */
    V put(K key, V value);

    /**
     * Put a value into the cache with a TTL.
     *
     * @param key the key
     * @param value the value
     * @param ttl time to live
     * @param unit time unit
     * @return the previous value, or null
     */
    V put(K key, V value, long ttl, TimeUnit unit);

    /**
     * Put a value into the cache only if the key doesn't already exist.
     * This is an atomic operation (similar to Infinispan's putIfAbsent).
     *
     * @param key the key
     * @param value the value
     * @param ttl time to live
     * @param unit time unit
     * @return the existing value if key was already present, null if key was absent (value was stored)
     */
    V putIfAbsent(K key, V value, long ttl, TimeUnit unit);

    /**
     * Put a value into the cache only if the key doesn't already exist (no TTL).
     *
     * @param key the key
     * @param value the value
     * @return the existing value if key was already present, null if key was absent (value was stored)
     */
    V putIfAbsent(K key, V value);

    /**
     * Remove a value from the cache.
     *
     * @param key the key
     * @return the removed value, or null if key didn't exist
     */
    V remove(K key);

    /**
     * Remove all entries from the cache.
     */
    void clear();

    /**
     * Check if the cache contains a key.
     *
     * @param key the key
     * @return true if the key exists, false otherwise
     */
    boolean containsKey(K key);

    /**
     * Get the number of entries in the cache.
     * Note: This may be an approximation for large caches.
     *
     * @return approximate size
     */
    long size();

    /**
     * Get multiple values from the cache in a single operation (batch get).
     * This is more efficient than multiple get() calls.
     *
     * @param keys set of keys to retrieve
     * @return map of key-value pairs found in cache
     */
    Map<K, V> getAll(Set<K> keys);

    /**
     * Put multiple values into the cache in a single operation (batch put).
     * This is more efficient than multiple put() calls.
     *
     * @param entries map of key-value pairs to store
     */
    void putAll(Map<K, V> entries);

    /**
     * Put multiple values into the cache with a TTL in a single operation.
     *
     * @param entries map of key-value pairs to store
     * @param ttl time to live
     * @param unit time unit
     */
    void putAll(Map<K, V> entries, long ttl, TimeUnit unit);

    /**
     * Get a stream of all entries in the cache.
     * This is used for predicate-based invalidation (e.g., invalidate all sessions for a realm).
     *
     * WARNING: This can be expensive for large caches. Use with caution.
     *
     * @return stream of cache entries
     */
    Stream<Map.Entry<K, V>> entrySet();

    /**
     * Get a stream of all keys in the cache.
     *
     * WARNING: This can be expensive for large caches. Use with caution.
     *
     * @return stream of keys
     */
    Stream<K> keySet();
}
