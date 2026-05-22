/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.keycloak.cache.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * In-process L1 cache (Caffeine, W-TinyLFU eviction) layered over a {@link RedisCache}.
 *
 * <p>Reads check the local L1 first; misses fall through to L2 (Redis) and the
 * result is hoisted into L1. Writes go to L2 first (the source of truth) and then
 * update L1 locally; an invalidation is published on the {@link L1InvalidationBus}
 * so peer nodes evict their stale copy.
 *
 * <p>Single-flight: concurrent misses for the same key collapse into one L2
 * lookup via {@link Cache#get(Object, java.util.function.Function)}. This prevents
 * the cache stampede / thundering herd that otherwise hits the L2 when a hot key
 * expires under load.
 *
 * <p>Negative caching: a sentinel object is stored for keys known to be absent
 * from L2 so repeated misses don't hammer Redis. The sentinel uses a short
 * dedicated TTL (configurable, default 5 s) — long enough to absorb a burst,
 * short enough to be benign if the key suddenly appears.
 *
 * <p>Cross-node consistency:
 * <ul>
 *   <li>Local writer publishes invalidation; peer nodes evict.</li>
 *   <li>Pub/sub is fire-and-forget; if a node misses an invalidation message
 *       (network blip), the entry becomes stale until the L1 TTL expires.
 *       For 5-nines this is acceptable for cache data; the SOT in PostgreSQL is
 *       always correct.</li>
 *   <li>{@link #clear()} broadcasts a wildcard message that flushes peer L1s.</li>
 * </ul>
 *
 * <p>The L1 key is the base64 of the L2 redis key bytes — that string is what
 * travels on the invalidation channel and is what Caffeine entries are keyed by.
 * Using the redis-key bytes (already prefixed with cache name) guarantees no
 * collisions across caches sharing one bus.
 */
public final class L1RedisCache<K, V> implements RedisCache<K, V> {

    private static final Logger logger = Logger.getLogger(L1RedisCache.class);

    /** Sentinel placed in L1 when L2 returned null, so we can short-circuit subsequent misses. */
    private static final Object NEGATIVE = new Object();

    private final RedisCache<K, V> delegate;       // L2 (Lettuce/Redis)
    private final Cache<String, Object> l1;        // L1 (Caffeine; Object so we can store NEGATIVE sentinel)
    private final L1InvalidationBus bus;
    private final L1InvalidationBus.Subscription sub;
    private final java.util.function.Function<K, String> keyFn;
    private final RedisMetrics metrics;

    public L1RedisCache(RedisCache<K, V> delegate,
                        java.util.function.Function<K, byte[]> redisKeyFn,
                        L1InvalidationBus bus,
                        L1Config config) {
        this(delegate, redisKeyFn, bus, config, null);
    }

    public L1RedisCache(RedisCache<K, V> delegate,
                        java.util.function.Function<K, byte[]> redisKeyFn,
                        L1InvalidationBus bus,
                        L1Config config,
                        RedisMetrics metrics) {
        this.delegate = delegate;
        this.bus = bus;
        this.keyFn = k -> Base64.getEncoder().encodeToString(redisKeyFn.apply(k));
        this.metrics = metrics;

        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(config.maxEntries)
                .expireAfterWrite(config.ttl);
        if (metrics != null) {
            // CaffeineStatsCounter publishes hit/miss/eviction/load metrics under
            // keycloak_redis_l1.<cacheName>.cache.* automatically. No manual counters needed.
            builder = builder.recordStats(() -> metrics.caffeineStatsFor(delegate.getName()));
        } else {
            builder = builder.recordStats();
        }
        this.l1 = builder.build();

        this.sub = bus.register(delegate.getName(), this::evictLocal);
        logger.debugf("L1 attached to cache '%s' (max=%d, ttl=%s, metrics=%s)",
                delegate.getName(), config.maxEntries, config.ttl, metrics != null);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key) {
        String l1Key = keyFn.apply(key);
        // Single-flight: concurrent misses share one L2 lookup
        Object hit = l1.get(l1Key, k -> {
            V loaded = delegate.get(key);
            return loaded != null ? loaded : NEGATIVE;
        });
        return hit == NEGATIVE ? null : (V) hit;
    }

    @Override
    public V put(K key, V value) {
        V old = delegate.put(key, value);
        l1.put(keyFn.apply(key), value);
        bus.publish(delegate.getName(), keyFn.apply(key));
        return old;
    }

    @Override
    public V put(K key, V value, long ttl, TimeUnit unit) {
        V old = delegate.put(key, value, ttl, unit);
        l1.put(keyFn.apply(key), value);
        bus.publish(delegate.getName(), keyFn.apply(key));
        return old;
    }

    @Override
    public V putIfAbsent(K key, V value, long ttl, TimeUnit unit) {
        V existing = delegate.putIfAbsent(key, value, ttl, unit);
        if (existing == null) {
            l1.put(keyFn.apply(key), value);
            bus.publish(delegate.getName(), keyFn.apply(key));
        }
        return existing;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        V existing = delegate.putIfAbsent(key, value);
        if (existing == null) {
            l1.put(keyFn.apply(key), value);
            bus.publish(delegate.getName(), keyFn.apply(key));
        }
        return existing;
    }

    @Override
    public V remove(K key) {
        V old = delegate.remove(key);
        l1.invalidate(keyFn.apply(key));
        bus.publish(delegate.getName(), keyFn.apply(key));
        return old;
    }

    @Override
    public void clear() {
        delegate.clear();
        l1.invalidateAll();
        bus.publish(delegate.getName(), L1InvalidationBus.FLUSH_KEY);
    }

    @Override
    public boolean containsKey(K key) {
        if (l1.getIfPresent(keyFn.apply(key)) != null) return true;
        return delegate.containsKey(key);
    }

    @Override
    public long size() {
        // L1 size isn't authoritative; defer to L2.
        return delegate.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> getAll(Set<K> keys) {
        Map<K, V> result = new HashMap<>(keys.size() * 2);
        Map<K, K> needFromL2 = new ConcurrentHashMap<>();
        for (K k : keys) {
            Object hit = l1.getIfPresent(keyFn.apply(k));
            if (hit == NEGATIVE) {
                continue;
            } else if (hit != null) {
                result.put(k, (V) hit);
            } else {
                needFromL2.put(k, k);
            }
        }
        if (!needFromL2.isEmpty()) {
            Map<K, V> fromL2 = delegate.getAll(needFromL2.keySet());
            for (Map.Entry<K, V> e : fromL2.entrySet()) {
                l1.put(keyFn.apply(e.getKey()), e.getValue());
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public void putAll(Map<K, V> entries) {
        delegate.putAll(entries);
        for (Map.Entry<K, V> e : entries.entrySet()) {
            String l1Key = keyFn.apply(e.getKey());
            l1.put(l1Key, e.getValue());
            bus.publish(delegate.getName(), l1Key);
        }
    }

    @Override
    public void putAll(Map<K, V> entries, long ttl, TimeUnit unit) {
        delegate.putAll(entries, ttl, unit);
        for (Map.Entry<K, V> e : entries.entrySet()) {
            String l1Key = keyFn.apply(e.getKey());
            l1.put(l1Key, e.getValue());
            bus.publish(delegate.getName(), l1Key);
        }
    }

    @Override
    public Stream<Map.Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }

    @Override
    public Stream<K> keySet() {
        return delegate.keySet();
    }

    /** Invalidate this cache's L1 entry. Called by the bus on remote-write events. */
    private void evictLocal(String l1Key) {
        if (L1InvalidationBus.FLUSH_KEY.equals(l1Key)) {
            l1.invalidateAll();
        } else {
            l1.invalidate(l1Key);
        }
    }

    public void close() {
        if (sub != null) sub.unregister();
        l1.invalidateAll();
        l1.cleanUp();
    }

    /** Configuration for the L1 layer. */
    public static final class L1Config {
        public final long maxEntries;
        public final Duration ttl;

        public L1Config(long maxEntries, Duration ttl) {
            this.maxEntries = maxEntries;
            this.ttl = ttl;
        }

        public static L1Config defaults() {
            // 10k entries / cache, 60s TTL: matches Keycloak 26's default local cache budget,
            // short enough that missed pub/sub invalidations heal within a minute.
            return new L1Config(10_000, Duration.ofSeconds(60));
        }
    }
}
