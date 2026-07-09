/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.cache.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Unit tests for {@link RedisMetrics}. Uses an injected {@link SimpleMeterRegistry}
 * (no global state) to verify counters/timers/distributions register and increment
 * with the expected names + tags.
 *
 * <p>The defensive registration pattern (every getter goes through computeIfAbsent)
 * means the no-op cost of "first call" is bounded; these tests pin that contract.
 */
public class RedisMetricsTest {

    private MeterRegistry registry;
    private RedisMetrics metrics;

    @Before
    public void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RedisMetrics(registry);
    }

    @Test
    public void l2OpCounter_registersWithCacheAndOpTags_andIncrements() {
        metrics.incrementL2Op("realms", "get");
        metrics.incrementL2Op("realms", "get");
        metrics.incrementL2Op("realms", "set_px");

        Counter realmsGet = registry.find("keycloak_redis_l2_ops_total")
                .tag("cache", "realms").tag("op", "get").counter();
        Counter realmsSet = registry.find("keycloak_redis_l2_ops_total")
                .tag("cache", "realms").tag("op", "set_px").counter();
        assertThat(realmsGet, notNullValue());
        assertThat(realmsGet.count(), equalTo(2.0));
        assertThat(realmsSet, notNullValue());
        assertThat(realmsSet.count(), equalTo(1.0));
    }

    @Test
    public void l2Timer_returnsSameInstanceForSameTags() {
        Timer t1 = metrics.l2Timer("users", "hgetall");
        Timer t2 = metrics.l2Timer("users", "hgetall");
        assertThat(t1, notNullValue());
        assertThat(t2, equalTo(t1)); // computeIfAbsent — no duplicate registration
    }

    @Test
    public void luaInvocationsAndTimer_haveScriptTag() {
        metrics.incrementLuaInvocation("cas_field_and_ttl");
        metrics.luaTimer("cas_field_and_ttl").record(java.time.Duration.ofMillis(7));

        Counter c = registry.find("keycloak_redis_lua_invocations_total")
                .tag("script", "cas_field_and_ttl").counter();
        Timer t = registry.find("keycloak_redis_lua_duration_seconds")
                .tag("script", "cas_field_and_ttl").timer();
        assertThat(c, notNullValue());
        assertThat(c.count(), equalTo(1.0));
        assertThat(t, notNullValue());
        assertThat(t.count(), equalTo(1L));
    }

    @Test
    public void pipelineBatch_recordsCountAndDistribution() {
        metrics.recordPipelineBatch(3);
        metrics.recordPipelineBatch(7);

        Counter batches = registry.find("keycloak_redis_pipeline_batches_total").counter();
        io.micrometer.core.instrument.DistributionSummary sum =
                registry.find("keycloak_redis_pipeline_batch_size").summary();
        assertThat(batches, notNullValue());
        assertThat(batches.count(), equalTo(2.0));
        assertThat(sum, notNullValue());
        assertThat(sum.count(), equalTo(2L));
        assertThat(sum.totalAmount(), equalTo(10.0)); // 3 + 7
    }

    @Test
    public void l1InvalidationCounters_increment() {
        metrics.recordL1InvalidationPublished();
        metrics.recordL1InvalidationPublished();
        metrics.recordL1InvalidationReceived();

        assertThat(registry.find("keycloak_redis_l1_invalidations_published_total").counter().count(), equalTo(2.0));
        assertThat(registry.find("keycloak_redis_l1_invalidations_received_total").counter().count(), equalTo(1.0));
    }

    @Test
    public void caffeineStatsCounter_returnsSameInstanceForSameCacheName() {
        var s1 = metrics.caffeineStatsFor("realms");
        var s2 = metrics.caffeineStatsFor("realms");
        var s3 = metrics.caffeineStatsFor("users");
        assertThat(s1, notNullValue());
        assertThat(s2, equalTo(s1));   // cached per cache name
        assertThat(s3 == s1, equalTo(false));  // different cache → different counter
    }

    @Test
    public void recordingTinyDuration_doesNotThrow() {
        // Bench-relevant edge: when a HSET takes <1ns the timer can see negative
        // System.nanoTime() deltas. Verify we don't blow up.
        metrics.l2Timer("realms", "get").record(0, java.util.concurrent.TimeUnit.NANOSECONDS);
        metrics.luaTimer("cas_field_and_ttl").record(1, java.util.concurrent.TimeUnit.NANOSECONDS);
        // No assertion — pass if no exception.
    }
}
