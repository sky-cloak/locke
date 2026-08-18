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

import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Cross-node L1 invalidation over the {@code kc:l1:invalidate} Redis channel: a node that
 * mutates a cache entry publishes the key, other nodes evict it from their local Caffeine
 * L1. Self-messages are filtered by node id. Works in standalone, sentinel, and cluster
 * modes (cluster uses classic cluster-wide PUBLISH).
 *
 * <p>Pub/sub is fire-and-forget, so a node that loses its connection misses invalidations
 * published during the gap. On reconnect (re-subscription) the bus flushes its local L1
 * rather than trust possibly-stale entries. Message format
 * {@code <nodeId>|<cacheName>|<l1Key>}; {@code l1Key == "*"} flushes that cache.
 */
public final class L1InvalidationBus {

    private static final Logger logger = Logger.getLogger(L1InvalidationBus.class);
    private static final String CHANNEL = "kc:l1:invalidate";
    private static final byte[] CHANNEL_BYTES = CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final String SEP = "|";
    public static final String FLUSH_KEY = "*";

    private final String nodeId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private final AutoCloseable subConn;
    private final AutoCloseable pubConn;
    private final BiConsumer<byte[], byte[]> publisher;
    private final RedisMetrics metrics;
    private volatile boolean subscribedOnce = false;
    private volatile boolean closed = false;

    /** Standalone / sentinel: a single Lettuce client whose pub/sub reaches the (promoted) primary. */
    public L1InvalidationBus(RedisClient client, RedisMetrics metrics) {
        this.metrics = metrics;
        StatefulRedisPubSubConnection<byte[], byte[]> sub = client.connectPubSub(new ByteArrayCodec());
        StatefulRedisPubSubConnection<byte[], byte[]> pub = client.connectPubSub(new ByteArrayCodec());
        this.subConn = sub;
        this.pubConn = pub;
        this.publisher = (channel, message) -> pub.async().publish(channel, message);
        sub.addListener(new RedisPubSubAdapter<byte[], byte[]>() {
            @Override public void message(byte[] channel, byte[] message) {
                if (!closed) handleIncoming(new String(message, StandardCharsets.UTF_8));
            }
            @Override public void subscribed(byte[] channel, long count) {
                onSubscribed();
            }
        });
        sub.sync().subscribe(CHANNEL_BYTES);
        logger.infof("L1 invalidation bus started (standalone/sentinel); nodeId=%s channel=%s", nodeId, CHANNEL);
    }

    /** Cluster: classic PUBLISH propagates cluster-wide, so one subscription receives every node's messages. */
    public L1InvalidationBus(RedisClusterClient client, RedisMetrics metrics) {
        this.metrics = metrics;
        StatefulRedisClusterPubSubConnection<byte[], byte[]> sub = client.connectPubSub(new ByteArrayCodec());
        StatefulRedisClusterPubSubConnection<byte[], byte[]> pub = client.connectPubSub(new ByteArrayCodec());
        sub.setNodeMessagePropagation(true);
        this.subConn = sub;
        this.pubConn = pub;
        this.publisher = (channel, message) -> pub.async().publish(channel, message);
        sub.addListener(new RedisClusterPubSubAdapter<byte[], byte[]>() {
            @Override public void message(RedisClusterNode node, byte[] channel, byte[] message) {
                if (!closed) handleIncoming(new String(message, StandardCharsets.UTF_8));
            }
            @Override public void subscribed(RedisClusterNode node, byte[] channel, long count) {
                onSubscribed();
            }
        });
        sub.sync().subscribe(CHANNEL_BYTES);
        logger.infof("L1 invalidation bus started (cluster); nodeId=%s channel=%s", nodeId, CHANNEL);
    }

    /** No-op (single-node / tests): publish is discarded, nothing is subscribed. */
    private L1InvalidationBus() {
        this.subConn = null;
        this.pubConn = null;
        this.publisher = null;
        this.metrics = null;
        this.closed = true;
    }

    /** Test seam: an active bus with no Redis connection, so the flush/register logic is unit-testable. */
    private L1InvalidationBus(boolean active) {
        this.subConn = null;
        this.pubConn = null;
        this.publisher = null;
        this.metrics = null;
        this.closed = false;
    }

    public static L1InvalidationBus noOp() {
        return new L1InvalidationBus();
    }

    static L1InvalidationBus forTest() {
        return new L1InvalidationBus(true);
    }

    /**
     * Register a per-cache handler that evicts from its local L1. Returns an
     * unregistration token; call {@link Subscription#unregister()} on shutdown.
     */
    public Subscription register(String cacheName, Consumer<String> evictor) {
        handlers.put(cacheName, evictor);
        return () -> handlers.remove(cacheName);
    }

    /** Publish an invalidation. Pass {@link #FLUSH_KEY} as l1Key to flush the whole cache. */
    public void publish(String cacheName, String l1Key) {
        if (closed || publisher == null) return;
        String msg = nodeId + SEP + cacheName + SEP + l1Key;
        try {
            publisher.accept(CHANNEL_BYTES, msg.getBytes(StandardCharsets.UTF_8));
            if (metrics != null) metrics.recordL1InvalidationPublished();
        } catch (Exception e) {
            logger.warnf(e, "L1 invalidation publish failed for %s/%s", cacheName, l1Key);
        }
    }

    // First subscribe is startup; a later one means we reconnected and may have missed
    // invalidations, so the local L1 is suspect and gets flushed.
    void onSubscribed() {
        if (closed) return;
        if (subscribedOnce) {
            flushLocal();
            logger.info("L1 flushed after pub/sub reconnect");
        } else {
            subscribedOnce = true;
        }
    }

    void flushLocal() {
        for (Consumer<String> handler : handlers.values()) {
            try {
                handler.accept(FLUSH_KEY);
            } catch (Exception e) {
                logger.warnf(e, "L1 flush evictor threw");
            }
        }
    }

    private void handleIncoming(String msg) {
        int firstSep = msg.indexOf(SEP);
        if (firstSep < 0) return;
        String fromNode = msg.substring(0, firstSep);
        if (nodeId.equals(fromNode)) return; // skip self
        int secondSep = msg.indexOf(SEP, firstSep + 1);
        if (secondSep < 0) return;
        String cacheName = msg.substring(firstSep + 1, secondSep);
        String l1Key = msg.substring(secondSep + 1);
        Consumer<String> handler = handlers.get(cacheName);
        if (handler != null) {
            try {
                handler.accept(l1Key);
                if (metrics != null) metrics.recordL1InvalidationReceived();
            } catch (Exception e) {
                logger.warnf(e, "L1 evictor threw for %s/%s", cacheName, l1Key);
            }
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public void close() {
        closed = true;
        closeQuietly(subConn);
        closeQuietly(pubConn);
        handlers.clear();
        logger.info("L1 invalidation bus closed");
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    /** Token returned by {@link #register} to unregister a handler. */
    @FunctionalInterface
    public interface Subscription {
        void unregister();
    }

    /** Byte[] codec for raw bytes — same shape as the one in RedisClientManager. */
    private static class ByteArrayCodec implements io.lettuce.core.codec.RedisCodec<byte[], byte[]> {
        @Override public byte[] decodeKey(java.nio.ByteBuffer b)   { return drain(b); }
        @Override public byte[] decodeValue(java.nio.ByteBuffer b) { return drain(b); }
        @Override public java.nio.ByteBuffer encodeKey(byte[] k)   { return java.nio.ByteBuffer.wrap(k); }
        @Override public java.nio.ByteBuffer encodeValue(byte[] v) { return java.nio.ByteBuffer.wrap(v); }
        private static byte[] drain(java.nio.ByteBuffer buf) {
            byte[] arr = new byte[buf.remaining()];
            buf.get(arr);
            return arr;
        }
    }
}
