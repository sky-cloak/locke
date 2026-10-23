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

import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.RedisFuture;
import org.jboss.logging.Logger;
import org.keycloak.connections.redis.RedisClientManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Per-request write batching using Lettuce's async/pipelining API.
 *
 * <p>Standard {@code .sync()} calls block per command — under sustained load,
 * each cache write is a separate TCP round-trip even when there's nothing to
 * wait for. Lettuce's async API returns a {@link RedisFuture} immediately,
 * letting multiple commands ride in the same TCP write window. The peer
 * pipelines responses; the wire-time-per-op approaches zero as batch size grows.
 *
 * <p>Usage:
 *
 * <pre>{@code
 *   try (var batch = pipeline.beginBatch()) {
 *       batch.set("k1", v1, 60_000);
 *       batch.set("k2", v2, 60_000);
 *       batch.del("k3");
 *       // automatic await on close()
 *   }
 * }</pre>
 *
 * <p>Compared to {@code MULTI/EXEC}: this is *not* atomic from Redis's
 * perspective — readers can see partial state mid-batch. But for cache writes
 * (Postgres is the SOT), atomicity is unnecessary; we only care about throughput.
 *
 * <p>Compared to per-op {@code .sync()}: same correctness, far fewer round-trips.
 *
 * <p>This class is tier-2 infrastructure — the existing {@link LettuceCacheAdapter}
 * stays on the {@code .sync()} path until callers explicitly switch.
 */
public final class PipelinedRedisCache {

    private static final Logger logger = Logger.getLogger(PipelinedRedisCache.class);

    private final RedisClientManager clientManager;
    private final RedisMetrics metrics;

    public PipelinedRedisCache(RedisClientManager clientManager) {
        this(clientManager, null);
    }

    public PipelinedRedisCache(RedisClientManager clientManager, RedisMetrics metrics) {
        this.clientManager = clientManager;
        this.metrics = metrics;
    }

    /** Open a new pipeline batch. Caller MUST close it (try-with-resources). */
    public Batch beginBatch() {
        return new Batch();
    }

    public final class Batch implements AutoCloseable {

        private final Object connection;
        private final RedisClusterAsyncCommands<byte[], byte[]> async;
        private final List<RedisFuture<?>> pending = new ArrayList<>(16);
        private boolean closed = false;

        Batch() {
            this.connection = clientManager.getConnection();
            this.async = clientManager.async(connection);
        }

        /** Queue a {@code SET key value PX ms}. Returns immediately. */
        public void set(byte[] key, byte[] value, long ttlMs) {
            check();
            pending.add(async.set(key, value, io.lettuce.core.SetArgs.Builder.px(ttlMs)));
        }

        /** Queue a {@code SET key value} with no TTL. */
        public void set(byte[] key, byte[] value) {
            check();
            pending.add(async.set(key, value));
        }

        /** Queue a {@code DEL key}. */
        public void del(byte[] key) {
            check();
            pending.add(async.del(key));
        }

        /** Queue a hash field set. */
        public void hset(byte[] key, byte[] field, byte[] value) {
            check();
            pending.add(async.hset(key, field, value));
        }

        /** Queue a {@code EXPIRE key seconds}. */
        public void expire(byte[] key, long seconds) {
            check();
            pending.add(async.expire(key, seconds));
        }

        /** Queue a {@code SADD set member}. */
        public void sadd(byte[] setKey, byte[] member) {
            check();
            pending.add(async.sadd(setKey, member));
        }

        /** Queue a {@code SREM set member}. */
        public void srem(byte[] setKey, byte[] member) {
            check();
            pending.add(async.srem(setKey, member));
        }

        /** Number of queued ops. */
        public int size() {
            return pending.size();
        }

        /**
         * Wait for all queued ops to complete. Throws if any op fails.
         * Idempotent (called once on close()). Default 5 s timeout; tune via overload.
         */
        public void await() {
            await(5, TimeUnit.SECONDS);
        }

        public void await(long timeout, TimeUnit unit) {
            check();
            try {
                io.lettuce.core.LettuceFutures.awaitAll(timeout, unit, pending.toArray(new RedisFuture[0]));
            } catch (Exception e) {
                logger.warnf(e, "Pipeline batch failed (%d ops); state may be partially applied", pending.size());
                throw new RuntimeException("pipeline batch failed", e);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (!pending.isEmpty()) {
                    int size = pending.size();
                    await();
                    if (metrics != null) metrics.recordPipelineBatch(size);
                }
            } finally {
                clientManager.returnConnection(connection);
            }
        }

        private void check() {
            if (closed) throw new IllegalStateException("Batch already closed");
        }
    }
}
