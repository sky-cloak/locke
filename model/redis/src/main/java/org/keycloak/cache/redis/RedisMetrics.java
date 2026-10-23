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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineStatsCounter;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single holder for all Redis-cache-path Micrometer meters.
 *
 * <p>All metrics register against {@link Metrics#globalRegistry} — the same
 * registry Quarkus exposes at {@code /metrics} in Prometheus format. Names
 * follow the {@code keycloak_redis_*} convention to match other Keycloak meters
 * ({@code keycloak_user_events_*} etc.).
 *
 * <p>Lazy registration: each meter is built on first use and cached. Safe to
 * call before the registry has any reporters bound.
 *
 * <p>Tag conventions:
 * <ul>
 *   <li>{@code cache=&lt;name&gt;} — the cache name (realms, users, sessions, etc.)</li>
 *   <li>{@code op=&lt;hset|hgetall|getdel|...&gt;} — Redis op type</li>
 *   <li>{@code script=&lt;cas|set_if_newer|index_add&gt;} — Lua script name</li>
 *   <li>{@code result=&lt;hit|miss|negative&gt;} — for L1 outcomes</li>
 * </ul>
 *
 * <p>Useful Prometheus queries once this is wired:
 * <pre>
 *   # L1 hit rate, last 5 min
 *   sum(rate(keycloak_redis_l1_hits_total[5m]))
 *     / sum(rate(keycloak_redis_l1_hits_total[5m]) + rate(keycloak_redis_l1_misses_total[5m]))
 *
 *   # Lua script p99 latency
 *   histogram_quantile(0.99, sum by (le, script) (rate(keycloak_redis_lua_duration_seconds_bucket[5m])))
 *
 *   # Pipeline batch size distribution
 *   histogram_quantile(0.95, sum by (le) (rate(keycloak_redis_pipeline_batch_size_bucket[5m])))
 * </pre>
 */
public final class RedisMetrics {

    private static final Logger logger = Logger.getLogger(RedisMetrics.class);

    private final MeterRegistry registry;

    /** Per-cache-name CaffeineStatsCounter — gives hit/miss/eviction/load metrics for free. */
    private final ConcurrentHashMap<String, CaffeineStatsCounter> caffeineStats = new ConcurrentHashMap<>();

    /** L2 op counters keyed by "<cache>|<op>". */
    private final ConcurrentHashMap<String, Counter> l2Counters = new ConcurrentHashMap<>();

    /** L2 op timers keyed by "<cache>|<op>". */
    private final ConcurrentHashMap<String, Timer> l2Timers = new ConcurrentHashMap<>();

    /** Lua script counters keyed by script name. */
    private final ConcurrentHashMap<String, Counter> luaCounters = new ConcurrentHashMap<>();

    /** Lua script timers keyed by script name. */
    private final ConcurrentHashMap<String, Timer> luaTimers = new ConcurrentHashMap<>();

    private final Counter pipelineBatches;
    private final DistributionSummary pipelineBatchSize;
    private final Counter l1Invalidations;
    private final Counter l1InvalidationsReceived;

    public RedisMetrics() {
        this(Metrics.globalRegistry);
    }

    /** Test/inject ctor — usually call the no-arg one which uses the global registry. */
    public RedisMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.pipelineBatches = Counter.builder("keycloak_redis_pipeline_batches_total")
                .description("Total number of pipelined Redis batches executed")
                .register(registry);
        this.pipelineBatchSize = DistributionSummary.builder("keycloak_redis_pipeline_batch_size")
                .description("Distribution of operations per pipelined batch")
                .baseUnit("operations")
                .register(registry);
        this.l1Invalidations = Counter.builder("keycloak_redis_l1_invalidations_published_total")
                .description("L1 cache invalidations published to peers via Redis pub/sub")
                .register(registry);
        this.l1InvalidationsReceived = Counter.builder("keycloak_redis_l1_invalidations_received_total")
                .description("L1 cache invalidations received from peers and applied locally")
                .register(registry);
        logger.info("Redis cache metrics registered against the global Micrometer registry");
    }

    /**
     * Get-or-create a {@link CaffeineStatsCounter} for the named L1 cache.
     * Pass the returned counter to {@code Caffeine.newBuilder().recordStats(() -> stats)}.
     */
    public CaffeineStatsCounter caffeineStatsFor(String cacheName) {
        return caffeineStats.computeIfAbsent(cacheName,
                name -> new CaffeineStatsCounter(registry, "keycloak_redis_l1." + name));
    }

    /** Increment the counter for one L2 op type. Counters are tagged by cache + op. */
    public void incrementL2Op(String cacheName, String op) {
        l2Counters.computeIfAbsent(cacheName + "|" + op,
                k -> Counter.builder("keycloak_redis_l2_ops_total")
                        .tag("cache", cacheName).tag("op", op)
                        .description("L2 (Redis) operation count")
                        .register(registry)).increment();
    }

    /** Record duration of an L2 op. */
    public Timer l2Timer(String cacheName, String op) {
        return l2Timers.computeIfAbsent(cacheName + "|" + op,
                k -> Timer.builder("keycloak_redis_l2_duration_seconds")
                        .tag("cache", cacheName).tag("op", op)
                        .description("L2 (Redis) operation duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    /** Increment counter for a Lua script invocation; record duration via {@link #luaTimer}. */
    public void incrementLuaInvocation(String scriptName) {
        luaCounters.computeIfAbsent(scriptName,
                k -> Counter.builder("keycloak_redis_lua_invocations_total")
                        .tag("script", scriptName)
                        .description("Lua script invocation count")
                        .register(registry)).increment();
    }

    public Timer luaTimer(String scriptName) {
        return luaTimers.computeIfAbsent(scriptName,
                k -> Timer.builder("keycloak_redis_lua_duration_seconds")
                        .tag("script", scriptName)
                        .description("Lua script execution duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    /** Record a pipelined batch with {@code opCount} operations. */
    public void recordPipelineBatch(int opCount) {
        pipelineBatches.increment();
        pipelineBatchSize.record(opCount);
    }

    public void recordL1InvalidationPublished() {
        l1Invalidations.increment();
    }

    public void recordL1InvalidationReceived() {
        l1InvalidationsReceived.increment();
    }
}
