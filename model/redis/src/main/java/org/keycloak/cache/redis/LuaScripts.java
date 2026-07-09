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

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.jboss.logging.Logger;
import org.keycloak.connections.redis.RedisClientManager;

import java.nio.charset.StandardCharsets;

/**
 * Server-side Lua scripts for atomic ops that would otherwise require a
 * round-trip dance (WATCH/MULTI/EXEC, optimistic-retry, distributed lock).
 *
 * <p>Each script is loaded once via {@code SCRIPT LOAD} (or implicitly on first
 * {@code EVAL}) and subsequently invoked via {@code EVALSHA <sha>} which sends
 * only the script hash, not the body. If the server has restarted and forgotten
 * the script, Lettuce auto-falls back to {@code EVAL} (this is built into
 * {@code evalsha} via the {@code NOSCRIPT} retry).
 *
 * <p>Why these scripts:
 * <ul>
 *   <li>{@link #CAS_FIELD_AND_TTL} — set a field iff a version field matches the
 *       caller's expected value, then bump the version and refresh the TTL.
 *       Used by {@code HashCacheAdapter} for safe concurrent updates without
 *       a separate lock or WATCH retry loop.</li>
 *   <li>{@link #SET_IF_NEWER_TIMESTAMP} — useful for last-seen / heartbeat
 *       updates. Server-side branch avoids a read+compare+write round-trip when
 *       the new value is older than what's stored.</li>
 *   <li>{@link #INDEX_ADD_WITH_TTL} — add a member to a set and ensure the set
 *       itself has a TTL >= a target. Eliminates a read+write dance to keep
 *       index sets from outliving their members.</li>
 * </ul>
 *
 * <p>Scripts are deliberately small — Redis stalls all commands during script
 * execution, so long scripts hurt throughput.
 */
// Non-final so the cache adapter can be unit-tested with a LuaScripts test double.
public class LuaScripts {

    private static final Logger logger = Logger.getLogger(LuaScripts.class);

    /**
     * Atomic compare-and-set of one field, with version bump and TTL refresh.
     * <p>KEYS[1] = hash key
     * <br>ARGV[1] = expected version (string-encoded number)
     * <br>ARGV[2] = field name
     * <br>ARGV[3] = new field value
     * <br>ARGV[4] = TTL seconds
     * <p>Returns: new version if successful, -1 if version mismatch.
     */
    public static final String CAS_FIELD_AND_TTL =
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')\n" +
            "local expected = tonumber(ARGV[1])\n" +
            "if cur ~= expected then return -1 end\n" +
            "local nv = cur + 1\n" +
            "redis.call('HSET', KEYS[1], 'version', tostring(nv), ARGV[2], ARGV[3])\n" +
            "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))\n" +
            "return nv";

    /**
     * Set field {@code timestamp} only if the new value is greater than the stored.
     * <p>KEYS[1] = hash key
     * <br>ARGV[1] = field name (typically 'timestamp' or 'lastSeen')
     * <br>ARGV[2] = new numeric value
     * <br>ARGV[3] = TTL seconds
     * <p>Returns: 1 if updated, 0 if older.
     */
    public static final String SET_IF_NEWER_TIMESTAMP =
            "local cur = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')\n" +
            "local nv = tonumber(ARGV[2])\n" +
            "if nv <= cur then return 0 end\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])\n" +
            "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))\n" +
            "return 1";

    /**
     * Add a member to a set and ensure the set's TTL is at least {@code targetTtl}.
     * <p>KEYS[1] = set key
     * <br>ARGV[1] = member to add
     * <br>ARGV[2] = target TTL seconds
     * <p>Returns: 1 if added (was new), 0 if already present.
     */
    public static final String INDEX_ADD_WITH_TTL =
            "local added = redis.call('SADD', KEYS[1], ARGV[1])\n" +
            "local current_ttl = redis.call('TTL', KEYS[1])\n" +
            "local target = tonumber(ARGV[2])\n" +
            "if current_ttl < target then redis.call('EXPIRE', KEYS[1], target) end\n" +
            "return added";

    /**
     * Atomic get-and-delete — the equivalent of Redis 6.2's {@code GETDEL}, but built from
     * {@code GET}+{@code DEL} inside a Lua script so it runs on {@code EVAL} (Redis 2.6+).
     * This keeps Locke runnable on Redis 6.0, notably classic Azure Cache for Redis. See
     * docs/adr/0003.
     * <p>KEYS[1] = key
     * <p>Returns: the old value (bulk), or nil if the key was absent.
     */
    public static final String GET_DEL =
            "local v = redis.call('GET', KEYS[1])\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "return v";

    private final RedisClientManager clientManager;
    private final RedisMetrics metrics;
    private volatile String casFieldSha;
    private volatile String setIfNewerSha;
    private volatile String indexAddSha;
    private volatile String getDelSha;

    public LuaScripts(RedisClientManager clientManager) {
        this(clientManager, null);
    }

    public LuaScripts(RedisClientManager clientManager, RedisMetrics metrics) {
        this.clientManager = clientManager;
        this.metrics = metrics;
    }

    /** Load all scripts into the server's script cache. Idempotent. */
    public synchronized void loadAll() {
        casFieldSha = scriptLoad(CAS_FIELD_AND_TTL);
        setIfNewerSha = scriptLoad(SET_IF_NEWER_TIMESTAMP);
        indexAddSha = scriptLoad(INDEX_ADD_WITH_TTL);
        getDelSha = scriptLoad(GET_DEL);
        logger.infof("Loaded %d Lua scripts (CAS=%s, NEWER=%s, IDXADD=%s, GETDEL=%s)",
                4, casFieldSha, setIfNewerSha, indexAddSha, getDelSha);
    }

    /**
     * Run {@link #CAS_FIELD_AND_TTL}. Returns -1 on version mismatch, new version on success.
     */
    public long casFieldAndTtl(byte[] hashKey, long expectedVersion, String field, byte[] newValue, long ttlSeconds) {
        return timed("cas_field_and_ttl", () -> evalLong(casFieldSha, CAS_FIELD_AND_TTL,
                new byte[][]{hashKey},
                bytes(String.valueOf(expectedVersion)),
                bytes(field),
                newValue,
                bytes(String.valueOf(ttlSeconds))));
    }

    /**
     * Run {@link #SET_IF_NEWER_TIMESTAMP}. Returns 1 if updated, 0 if older.
     */
    public long setIfNewerTimestamp(byte[] hashKey, String field, long newValue, long ttlSeconds) {
        return timed("set_if_newer_timestamp", () -> evalLong(setIfNewerSha, SET_IF_NEWER_TIMESTAMP,
                new byte[][]{hashKey},
                bytes(field),
                bytes(String.valueOf(newValue)),
                bytes(String.valueOf(ttlSeconds))));
    }

    /**
     * Run {@link #INDEX_ADD_WITH_TTL}. Returns 1 if added (new), 0 if already present.
     */
    public long indexAddWithTtl(byte[] setKey, byte[] member, long ttlSeconds) {
        return timed("index_add_with_ttl", () -> evalLong(indexAddSha, INDEX_ADD_WITH_TTL,
                new byte[][]{setKey},
                member,
                bytes(String.valueOf(ttlSeconds))));
    }

    /**
     * Run {@link #GET_DEL}: atomically return the old value and delete the key.
     * Equivalent to {@code GETDEL} but works on Redis 6.0 (see docs/adr/0003).
     * Returns the old value, or {@code null} if the key was absent.
     */
    public byte[] getDel(byte[] key) {
        return timedBytes("get_del", () -> evalBytes(getDelSha, GET_DEL, new byte[][]{key}));
    }

    private long timed(String scriptName, java.util.function.LongSupplier action) {
        if (metrics == null) return action.getAsLong();
        metrics.incrementLuaInvocation(scriptName);
        long t0 = System.nanoTime();
        try {
            return action.getAsLong();
        } finally {
            metrics.luaTimer(scriptName).record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private byte[] timedBytes(String scriptName, java.util.function.Supplier<byte[]> action) {
        if (metrics == null) return action.get();
        metrics.incrementLuaInvocation(scriptName);
        long t0 = System.nanoTime();
        try {
            return action.get();
        } finally {
            metrics.luaTimer(scriptName).record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    // -- Plumbing ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private long evalLong(String sha, String body, byte[][] keys, byte[]... args) {
        return withConnection(cmd -> {
            try {
                if (sha != null) {
                    return cmd.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
                }
            } catch (io.lettuce.core.RedisNoScriptException nse) {
                // Script flushed; fall through to EVAL which re-loads
            }
            return cmd.eval(body, ScriptOutputType.INTEGER, keys, args);
        });
    }

    @SuppressWarnings("unchecked")
    private byte[] evalBytes(String sha, String body, byte[][] keys, byte[]... args) {
        return withConnection(cmd -> {
            try {
                if (sha != null) {
                    return cmd.evalsha(sha, ScriptOutputType.VALUE, keys, args);
                }
            } catch (io.lettuce.core.RedisNoScriptException nse) {
                // Script flushed (server restart / failover); fall through to EVAL which re-loads.
            }
            return cmd.eval(body, ScriptOutputType.VALUE, keys, args);
        });
    }

    @SuppressWarnings("unchecked")
    private String scriptLoad(String body) {
        return withConnection(cmd -> cmd.scriptLoad(body));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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
