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

import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;
import org.keycloak.connections.redis.RedisClientManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Redis cache implementation using Lettuce client.
 * Keys are stored as "cacheName:key" prefixed byte arrays.
 * Values are Java-serialized byte arrays.
 */
public class LettuceCacheAdapter<K, V> implements RedisCache<K, V> {

    private static final Logger logger = Logger.getLogger(LettuceCacheAdapter.class);

    private final String name;
    private final RedisClientManager clientManager;
    private final byte[] prefix;
    private final RedisMetrics metrics;
    private final LuaScripts luaScripts;

    public LettuceCacheAdapter(String name, RedisClientManager clientManager) {
        this(name, clientManager, null, null);
    }

    public LettuceCacheAdapter(String name, RedisClientManager clientManager, RedisMetrics metrics) {
        this(name, clientManager, metrics, null);
    }

    public LettuceCacheAdapter(String name, RedisClientManager clientManager, RedisMetrics metrics, LuaScripts luaScripts) {
        this.name = name;
        this.clientManager = clientManager;
        this.metrics = metrics;
        this.luaScripts = luaScripts;
        this.prefix = (name + ":").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key) {
        if (metrics != null) metrics.incrementL2Op(name, "get");
        long t0 = System.nanoTime();
        try {
            return withConnection(cmd -> {
                byte[] raw = cmd.get(toRedisKey(key));
                return raw == null ? null : (V) deserialize(raw);
            });
        } finally {
            if (metrics != null) metrics.l2Timer(name, "get").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        return withConnection(cmd -> {
            byte[] redisKey = toRedisKey(key);
            byte[] old = cmd.getset(redisKey, serialize(value));
            return old == null ? null : (V) deserialize(old);
        });
    }

    @Override
    public V put(K key, V value, long ttl, TimeUnit unit) {
        // Fold GETSET + PEXPIRE (2 round-trips) into a single SET ... PX command.
        if (metrics != null) metrics.incrementL2Op(name, "set_px");
        long t0 = System.nanoTime();
        try {
            withConnection(cmd -> {
                cmd.set(toRedisKey(key), serialize(value), SetArgs.Builder.px(unit.toMillis(ttl)));
                return null;
            });
        } finally {
            if (metrics != null) metrics.l2Timer(name, "set_px").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V putIfAbsent(K key, V value, long ttl, TimeUnit unit) {
        return withConnection(cmd -> {
            byte[] redisKey = toRedisKey(key);
            byte[] existing = cmd.get(redisKey);
            if (existing != null) {
                return (V) deserialize(existing);
            }
            String result = cmd.set(redisKey, serialize(value), SetArgs.Builder.nx().px(unit.toMillis(ttl)));
            if ("OK".equals(result)) {
                return null; // successfully stored
            }
            // Race condition: another thread stored a value
            existing = cmd.get(redisKey);
            return existing == null ? null : (V) deserialize(existing);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public V putIfAbsent(K key, V value) {
        return withConnection(cmd -> {
            byte[] redisKey = toRedisKey(key);
            byte[] existing = cmd.get(redisKey);
            if (existing != null) {
                return (V) deserialize(existing);
            }
            String result = cmd.set(redisKey, serialize(value), SetArgs.Builder.nx());
            if ("OK".equals(result)) {
                return null;
            }
            existing = cmd.get(redisKey);
            return existing == null ? null : (V) deserialize(existing);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(K key) {
        if (metrics != null) metrics.incrementL2Op(name, "getdel");
        long t0 = System.nanoTime();
        try {
            byte[] redisKey = toRedisKey(key);
            // Atomic get-and-delete. The Lua GET+DEL path runs on Redis 6.0 (classic Azure
            // Cache for Redis); native GETDEL needs 6.2+ (see docs/adr/0003). luaScripts manages
            // its own pooled connection, so it must NOT be nested inside withConnection.
            byte[] old = (luaScripts != null)
                    ? luaScripts.getDel(redisKey)
                    : withConnection(cmd -> cmd.getdel(redisKey));
            return old == null ? null : (V) deserialize(old);
        } finally {
            if (metrics != null) metrics.l2Timer(name, "getdel").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void clear() {
        withConnection(cmd -> {
            // Use SCAN to find all keys with our prefix and delete them
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(new String(prefix, StandardCharsets.UTF_8) + "*").limit(100);
            io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
            do {
                io.lettuce.core.KeyScanCursor<byte[]> result = cmd.scan(cursor, scanArgs);
                if (!result.getKeys().isEmpty()) {
                    cmd.del(result.getKeys().toArray(new byte[0][]));
                }
                cursor = result;
            } while (!cursor.isFinished());
            return null;
        });
    }

    @Override
    public boolean containsKey(K key) {
        return withConnection(cmd -> cmd.exists(toRedisKey(key)) > 0);
    }

    @Override
    public long size() {
        // Count keys matching our prefix using SCAN
        return withConnection(cmd -> {
            long count = 0;
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(new String(prefix, StandardCharsets.UTF_8) + "*").limit(1000);
            io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
            do {
                io.lettuce.core.KeyScanCursor<byte[]> result = cmd.scan(cursor, scanArgs);
                count += result.getKeys().size();
                cursor = result;
            } while (!cursor.isFinished());
            return count;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> getAll(Set<K> keys) {
        return withConnection(cmd -> {
            Map<K, V> result = new HashMap<>();
            for (K key : keys) {
                byte[] raw = cmd.get(toRedisKey(key));
                if (raw != null) {
                    result.put(key, (V) deserialize(raw));
                }
            }
            return result;
        });
    }

    @Override
    public void putAll(Map<K, V> entries) {
        withConnection(cmd -> {
            for (Map.Entry<K, V> entry : entries.entrySet()) {
                cmd.set(toRedisKey(entry.getKey()), serialize(entry.getValue()));
            }
            return null;
        });
    }

    @Override
    public void putAll(Map<K, V> entries, long ttl, TimeUnit unit) {
        withConnection(cmd -> {
            for (Map.Entry<K, V> entry : entries.entrySet()) {
                cmd.set(toRedisKey(entry.getKey()), serialize(entry.getValue()),
                        SetArgs.Builder.px(unit.toMillis(ttl)));
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<Map.Entry<K, V>> entrySet() {
        return withConnection(cmd -> {
            Map<K, V> result = new HashMap<>();
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(new String(prefix, StandardCharsets.UTF_8) + "*").limit(100);
            io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
            do {
                io.lettuce.core.KeyScanCursor<byte[]> scanResult = cmd.scan(cursor, scanArgs);
                for (byte[] redisKey : scanResult.getKeys()) {
                    byte[] raw = cmd.get(redisKey);
                    if (raw != null) {
                        K key = (K) fromRedisKey(redisKey);
                        result.put(key, (V) deserialize(raw));
                    }
                }
                cursor = scanResult;
            } while (!cursor.isFinished());
            return result.entrySet().stream();
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<K> keySet() {
        return withConnection(cmd -> {
            java.util.List<K> keys = new java.util.ArrayList<>();
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(new String(prefix, StandardCharsets.UTF_8) + "*").limit(100);
            io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
            do {
                io.lettuce.core.KeyScanCursor<byte[]> scanResult = cmd.scan(cursor, scanArgs);
                for (byte[] redisKey : scanResult.getKeys()) {
                    keys.add((K) fromRedisKey(redisKey));
                }
                cursor = scanResult;
            } while (!cursor.isFinished());
            return keys.stream();
        });
    }

    /** Visible to {@link L1RedisCache} so the L1 layer can compute identical key bytes for invalidation. */
    public byte[] toRedisKey(K key) {
        byte[] keyBytes = serialize(key);
        byte[] result = new byte[prefix.length + keyBytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(keyBytes, 0, result, prefix.length, keyBytes.length);
        return result;
    }

    private Object fromRedisKey(byte[] redisKey) {
        byte[] keyBytes = new byte[redisKey.length - prefix.length];
        System.arraycopy(redisKey, prefix.length, keyBytes, 0, keyBytes.length);
        return deserialize(keyBytes);
    }

    private byte[] serialize(Object obj) {
        if (obj == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object: " + obj.getClass().getName(), e);
        }
    }

    private Object deserialize(byte[] bytes) {
        if (bytes == null) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize object", e);
        }
    }

    private <R> R withConnection(java.util.function.Function<RedisClusterCommands<byte[], byte[]>, R> action) {
        Object connection = clientManager.getConnection();
        try {
            return action.apply(clientManager.sync(connection));
        } finally {
            clientManager.returnConnection(connection);
        }
    }
}
