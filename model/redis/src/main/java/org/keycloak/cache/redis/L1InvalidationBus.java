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
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Cross-node L1 cache invalidation channel.
 *
 * <p>When a node mutates a cache entry it publishes a message to the
 * {@code kc:l1:invalidate} Redis channel. Other nodes subscribe and evict the
 * corresponding key from their local Caffeine L1 cache.
 *
 * <p>Self-published messages are filtered out by node-id so a writer doesn't
 * evict its own freshly-written value.
 *
 * <p>Message format: {@code <nodeId>|<cacheName>|<l1Key>}
 *
 * <p>Limitations:
 * <ul>
 *   <li>Redis pub/sub is fire-and-forget. A node that reconnects after a
 *       network partition will not see invalidations published while it was
 *       offline; entries become stale until the L1 TTL expires. For stronger
 *       semantics, a future iteration could replay missed events from a
 *       TTL'd Redis stream, or substitute NATS JetStream.</li>
 *   <li>The wildcard channel {@code <name>:*} forces a full cache flush.</li>
 * </ul>
 */
public final class L1InvalidationBus {

    private static final Logger logger = Logger.getLogger(L1InvalidationBus.class);
    private static final String CHANNEL = "kc:l1:invalidate";
    private static final byte[] CHANNEL_BYTES = CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final String SEP = "|";
    public static final String FLUSH_KEY = "*";

    private final String nodeId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private final StatefulRedisPubSubConnection<byte[], byte[]> subConn;
    private final StatefulRedisPubSubConnection<byte[], byte[]> pubConn;
    private volatile boolean closed = false;

    public L1InvalidationBus(RedisClient client) {
        this.subConn = client.connectPubSub(new ByteArrayCodec());
        this.pubConn = client.connectPubSub(new ByteArrayCodec());

        subConn.addListener(new RedisPubSubAdapter<byte[], byte[]>() {
            @Override
            public void message(byte[] channel, byte[] message) {
                if (closed) return;
                handleIncoming(new String(message, StandardCharsets.UTF_8));
            }
        });
        subConn.sync().subscribe(CHANNEL_BYTES);
        logger.infof("L1 invalidation bus started; nodeId=%s channel=%s", nodeId, CHANNEL);
    }

    /** Internal ctor for the no-op factory (no Redis connection). */
    private L1InvalidationBus() {
        this.subConn = null;
        this.pubConn = null;
        this.closed = true;
    }

    /**
     * Build a no-op bus for use in single-node deployments or unit tests where
     * cross-node invalidation isn't needed. {@link #register} returns a no-op
     * subscription and {@link #publish} silently discards. Local cache writes
     * still work; only cross-node propagation is disabled.
     */
    public static L1InvalidationBus noOp() {
        return new L1InvalidationBus();
    }

    /**
     * Register a per-cache handler that knows how to evict from its local L1.
     * Returns an unregistration token; call {@link Subscription#unregister()} on shutdown.
     */
    public Subscription register(String cacheName, Consumer<String> evictor) {
        handlers.put(cacheName, evictor);
        return () -> handlers.remove(cacheName);
    }

    /**
     * Publish an invalidation. Pass {@link #FLUSH_KEY} as l1Key to flush the whole cache.
     */
    public void publish(String cacheName, String l1Key) {
        if (closed || pubConn == null) return;
        String msg = nodeId + SEP + cacheName + SEP + l1Key;
        try {
            pubConn.async().publish(CHANNEL_BYTES, msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warnf(e, "L1 invalidation publish failed for %s/%s", cacheName, l1Key);
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
        if (subConn != null) try { subConn.close(); } catch (Exception ignored) {}
        if (pubConn != null) try { pubConn.close(); } catch (Exception ignored) {}
        handlers.clear();
        logger.info("L1 invalidation bus closed");
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
