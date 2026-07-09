/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.cache.redis;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A no-op {@link RedisCache} for caches that are intentionally L1-only.
 *
 * <p>Some Keycloak cache entities — notably {@code CachedRealm}, {@code CachedUser},
 * {@code CachedClient}, and the authorization equivalents — hold lambda fields
 * (via {@link org.keycloak.models.cache.redis.DefaultLazyLoader}) that are not
 * Java-serializable. They were originally written for Infinispan, which uses
 * Protostream and an entity wrapper, not {@code ObjectOutputStream}. Trying to
 * persist them through {@link LettuceCacheAdapter} (Java-native serialization)
 * crashes Keycloak at startup.
 *
 * <p>Architecturally these caches don't need Redis storage anyway. Realm/user/
 * client config is read-mostly and the canonical source of truth is PostgreSQL.
 * Each pod can keep its own in-process Caffeine cache and rely on Redis pub/sub
 * for cross-pod invalidation. That's how Infinispan's local cache mode behaves,
 * and we mirror it here without dragging in Infinispan.
 *
 * <p>Wired in by {@link
 * org.keycloak.connections.redis.DefaultRedisConnectionProvider#getCache}: when
 * the cache name matches the L1-only set, the L2 delegate is this no-op and
 * {@link L1RedisCache} effectively becomes a Caffeine wrapper with cross-node
 * invalidation. The Keycloak-level cache manager loads from JPA on every miss,
 * which is the same behavior as Infinispan's local cache.
 */
public final class NoOpRedisCache<K, V> implements RedisCache<K, V> {

    private final String name;

    public NoOpRedisCache(String name) {
        this.name = name;
    }

    @Override public String getName() { return name; }

    // Reads always miss — caller (typically a Keycloak cache session) is
    // expected to re-fetch from JPA and call put() to populate the L1.
    @Override public V get(K key) { return null; }
    @Override public boolean containsKey(K key) { return false; }
    @Override public long size() { return 0L; }
    @Override public Map<K, V> getAll(Set<K> keys) { return Collections.emptyMap(); }

    // Writes are no-ops at L2; the L1 wrapper still keeps the value in Caffeine.
    @Override public V put(K key, V value) { return null; }
    @Override public V put(K key, V value, long ttl, TimeUnit unit) { return null; }
    @Override public V putIfAbsent(K key, V value) { return null; }
    @Override public V putIfAbsent(K key, V value, long ttl, TimeUnit unit) { return null; }
    @Override public V remove(K key) { return null; }
    @Override public void clear() {}
    @Override public void putAll(Map<K, V> entries) {}
    @Override public void putAll(Map<K, V> entries, long ttl, TimeUnit unit) {}

    @Override public Stream<Map.Entry<K, V>> entrySet() { return Stream.empty(); }
    @Override public Stream<K> keySet() { return Stream.empty(); }
}
