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

import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.jboss.logging.Logger;
import org.keycloak.connections.redis.RedisClientManager;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hash-shaped cache adapter — stores entities as Redis hashes ({@code HSET / HGETALL}).
 *
 * <p>Compared to {@link LettuceCacheAdapter} which stores each entity as one opaque
 * blob via {@code SET}, this adapter:
 *
 * <ul>
 *   <li>Stores fields under a single hash key, so partial updates only send the
 *       changed bytes — {@code HSET key field value} is one round-trip and one
 *       field's worth of bytes vs read+modify+write of the whole entity.</li>
 *   <li>Lets callers inspect individual fields with {@code HGET} without
 *       deserializing the whole entity.</li>
 *   <li>Works naturally with secondary index sets — adding/removing the entity's
 *       key from a sister set ({@code SADD/SREM}) gives O(1) "list all sessions
 *       for client X" semantics.</li>
 * </ul>
 *
 * <p>Entities must implement {@link HashEntity} which provides serialize/deserialize
 * for the field map. Field values are byte arrays so callers can use any
 * encoding (UTF-8 strings, protobuf, raw bytes).
 *
 * <p>Thread-safety: this adapter is stateless and reuses the connection-pool
 * pattern from {@link LettuceCacheAdapter}. Multiple threads can call
 * methods concurrently.
 */
public final class HashCacheAdapter<K> {

    private static final Logger logger = Logger.getLogger(HashCacheAdapter.class);

    private final String name;
    private final RedisClientManager clientManager;
    private final byte[] keyPrefix;
    private final byte[] indexPrefix;
    private final RedisMetrics metrics;

    public HashCacheAdapter(String name, RedisClientManager clientManager) {
        this(name, clientManager, null);
    }

    public HashCacheAdapter(String name, RedisClientManager clientManager, RedisMetrics metrics) {
        this.name = name;
        this.clientManager = clientManager;
        this.metrics = metrics;
        this.keyPrefix = (name + ":h:").getBytes(StandardCharsets.UTF_8);
        this.indexPrefix = (name + ":idx:").getBytes(StandardCharsets.UTF_8);
    }

    public String getName() {
        return name;
    }

    /** Read all fields of the entity at {@code key}. Returns null if no key. */
    public Map<String, byte[]> getAll(K key) {
        if (metrics != null) metrics.incrementL2Op(name, "hgetall");
        long t0 = System.nanoTime();
        try {
            return withConnection(cmd -> {
                byte[] redisKey = entityKey(key);
                Map<byte[], byte[]> raw = cmd.hgetall(redisKey);
                if (raw == null || raw.isEmpty()) return null;
                Map<String, byte[]> out = new LinkedHashMap<>(raw.size() * 2);
                for (Map.Entry<byte[], byte[]> e : raw.entrySet()) {
                    out.put(new String(e.getKey(), StandardCharsets.UTF_8), e.getValue());
                }
                return out;
            });
        } finally {
            if (metrics != null) metrics.l2Timer(name, "hgetall").record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** Read a single field. Returns null if field or key is absent. */
    public byte[] getField(K key, String field) {
        if (metrics != null) metrics.incrementL2Op(name, "hget");
        long t0 = System.nanoTime();
        try {
            return withConnection(cmd -> cmd.hget(entityKey(key), field.getBytes(StandardCharsets.UTF_8)));
        } finally {
            if (metrics != null) metrics.l2Timer(name, "hget").record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Write all fields atomically; sets TTL on the key.
     *
     * <p>Pipelined: the {@code HSET} and {@code EXPIRE} commands are sent on the
     * same connection in one network write window. Lettuce's async API issues
     * the second command without waiting for the first's response, so the wire
     * cost approaches one round-trip total instead of two sequential ones.
     */
    public void putAll(K key, Map<String, byte[]> fields, long ttlSeconds) {
        if (fields == null || fields.isEmpty()) return;
        Map<byte[], byte[]> raw = new HashMap<>(fields.size() * 2);
        for (Map.Entry<String, byte[]> e : fields.entrySet()) {
            raw.put(e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
        }
        if (metrics != null) metrics.incrementL2Op(name, "hset_multi");
        long t0 = System.nanoTime();
        try {
            pipelineWrite(key, async -> {
                RedisFuture<?> f1 = async.hset(entityKey(key), raw);
                RedisFuture<?> f2 = ttlSeconds > 0 ? async.expire(entityKey(key), ttlSeconds) : null;
                return f2 == null ? new RedisFuture<?>[]{f1} : new RedisFuture<?>[]{f1, f2};
            });
        } finally {
            if (metrics != null) metrics.l2Timer(name, "hset_multi").record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** Update a single field. Does NOT change TTL — entity must already exist with TTL. */
    public void putField(K key, String field, byte[] value) {
        withConnection(cmd -> cmd.hset(entityKey(key), field.getBytes(StandardCharsets.UTF_8), value));
    }

    /** Update a single field and refresh TTL. Pipelined HSET + EXPIRE — 1 RT. */
    public void putField(K key, String field, byte[] value, long ttlSeconds) {
        if (metrics != null) metrics.incrementL2Op(name, "hset");
        long t0 = System.nanoTime();
        try {
            pipelineWrite(key, async -> {
                byte[] redisKey = entityKey(key);
                byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
                RedisFuture<?> f1 = async.hset(redisKey, fieldBytes, value);
                RedisFuture<?> f2 = ttlSeconds > 0 ? async.expire(redisKey, ttlSeconds) : null;
                return f2 == null ? new RedisFuture<?>[]{f1} : new RedisFuture<?>[]{f1, f2};
            });
        } finally {
            if (metrics != null) metrics.l2Timer(name, "hset").record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** Delete a hash entity. Returns true if deleted. */
    public boolean remove(K key) {
        return withConnection(cmd -> cmd.del(entityKey(key)) > 0);
    }

    /** Delete a single field from a hash. Returns true if the field existed. */
    public boolean deleteField(K key, String field) {
        return withConnection(cmd -> cmd.hdel(entityKey(key), field.getBytes(StandardCharsets.UTF_8)) > 0);
    }

    /**
     * Atomically delete a field and refresh the parent hash's TTL.
     * Used by parent-child schemas where removing a child should keep the
     * parent's TTL fresh (otherwise the parent could be evicted right after).
     * Pipelined HDEL + EXPIRE — 1 RT instead of 2.
     */
    public boolean deleteFieldRefreshTtl(K key, String field, long ttlSeconds) {
        Object conn = clientManager.getConnection();
        try {
            RedisClusterAsyncCommands<byte[], byte[]> async = clientManager.async(conn);
            byte[] redisKey = entityKey(key);
            RedisFuture<Long> hdel = async.hdel(redisKey, field.getBytes(StandardCharsets.UTF_8));
            RedisFuture<?> exp = ttlSeconds > 0 ? async.expire(redisKey, ttlSeconds) : null;
            // Await both. Lettuce sends them in one write window.
            io.lettuce.core.LettuceFutures.awaitAll(2, java.util.concurrent.TimeUnit.SECONDS,
                    exp == null ? new RedisFuture<?>[]{hdel} : new RedisFuture<?>[]{hdel, exp});
            return hdel.get() > 0;
        } catch (Exception e) {
            throw new RuntimeException("HDEL+EXPIRE pipeline failed", e);
        } finally {
            clientManager.returnConnection(conn);
        }
    }

    /** List the field names of a hash ({@code HKEYS}). Empty list if the key is absent. */
    public java.util.List<String> fieldNames(K key) {
        return withConnection(cmd -> {
            java.util.List<byte[]> raw = cmd.hkeys(entityKey(key));
            java.util.List<String> out = new java.util.ArrayList<>(raw.size());
            for (byte[] b : raw) {
                out.add(new String(b, StandardCharsets.UTF_8));
            }
            return out;
        });
    }

    public boolean exists(K key) {
        return withConnection(cmd -> cmd.exists(entityKey(key)) > 0);
    }

    // -- Secondary index sets ---------------------------------------------------

    /** Add {@code memberKey} to the index set named {@code indexName}. */
    public void addToIndex(String indexName, K memberKey, long ttlSeconds) {
        withConnection(cmd -> {
            byte[] idxKey = indexKey(indexName);
            cmd.sadd(idxKey, serializeKey(memberKey));
            if (ttlSeconds > 0) cmd.expire(idxKey, ttlSeconds);
            return null;
        });
    }

    public void removeFromIndex(String indexName, K memberKey) {
        withConnection(cmd -> cmd.srem(indexKey(indexName), serializeKey(memberKey)));
    }

    /** Count members in an index set. */
    public long indexSize(String indexName) {
        return withConnection(cmd -> cmd.scard(indexKey(indexName)));
    }

    // -- Plumbing ---------------------------------------------------------------

    private byte[] entityKey(K key) {
        byte[] keyBytes = serializeKey(key);
        byte[] result = new byte[keyPrefix.length + keyBytes.length];
        System.arraycopy(keyPrefix, 0, result, 0, keyPrefix.length);
        System.arraycopy(keyBytes, 0, result, keyPrefix.length, keyBytes.length);
        return result;
    }

    private byte[] indexKey(String indexName) {
        byte[] nameBytes = indexName.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[indexPrefix.length + nameBytes.length];
        System.arraycopy(indexPrefix, 0, result, 0, indexPrefix.length);
        System.arraycopy(nameBytes, 0, result, indexPrefix.length, nameBytes.length);
        return result;
    }

    /** Convert a typed key to bytes for redis. Strings used directly; everything else via toString. */
    private byte[] serializeKey(K key) {
        if (key instanceof byte[]) return (byte[]) key;
        return key.toString().getBytes(StandardCharsets.UTF_8);
    }

    private <R> R withConnection(java.util.function.Function<RedisClusterCommands<byte[], byte[]>, R> action) {
        Object connection = clientManager.getConnection();
        try {
            return action.apply(clientManager.sync(connection));
        } finally {
            clientManager.returnConnection(connection);
        }
    }

    /**
     * Pipeline two or more async writes on the same connection so they ride in
     * one TCP write window. Bench-proven win over sequential .sync() calls when
     * the operations together would otherwise require N round-trips.
     */
    private void pipelineWrite(K key,
                               java.util.function.Function<RedisClusterAsyncCommands<byte[], byte[]>, RedisFuture<?>[]> action) {
        Object conn = clientManager.getConnection();
        try {
            RedisFuture<?>[] futures = action.apply(clientManager.async(conn));
            io.lettuce.core.LettuceFutures.awaitAll(2, java.util.concurrent.TimeUnit.SECONDS, futures);
        } finally {
            clientManager.returnConnection(conn);
        }
    }

    /**
     * An entity that knows how to pack itself into a flat field map and rebuild
     * itself from one. Field names should be stable across versions; if a field
     * is removed, callers must tolerate {@code null}.
     */
    public interface HashEntity {
        Map<String, byte[]> toFields();
        // Reconstruction is per-entity-class so it's not in the interface; the
        // HashEntity type is a marker plus a writer. Each entity provides a
        // static {@code fromFields(Map<String,byte[]>)}.
    }
}
