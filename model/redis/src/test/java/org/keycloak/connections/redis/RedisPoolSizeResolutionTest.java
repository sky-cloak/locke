/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.connections.redis;

import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression: the documented {@code cache-redis-max-pool-size} / {@code cache-redis-min-idle}
 * options must actually reach the built RedisConnectionConfig. Like the timeout before it, the
 * factory never resolved them and applyResolvedOverrides() rebuilt the config without them, so the
 * hardcoded Builder defaults (64 / 16) always won. That starves high-latency managed Redis, where
 * required concurrency = ops/sec * per-op latency needs a larger pool. These tests pin the
 * three-tier resolution and that the resolved values land on the config.
 */
public class RedisPoolSizeResolutionTest {

    @After
    public void clearSysProps() {
        System.clearProperty("kc.cache-redis-max-pool-size");
        System.clearProperty("kc.cache-redis-min-idle");
        System.clearProperty("kc.cache-redis-topology-refresh-seconds");
    }

    private DefaultRedisConnectionProviderFactory factoryWithScope(TestConfigScope scope) {
        DefaultRedisConnectionProviderFactory factory = new DefaultRedisConnectionProviderFactory();
        factory.init(scope);
        return factory;
    }

    @Test
    public void resolvePoolSize_NullWhenUnset() {
        assertThat(factoryWithScope(TestConfigScope.empty()).resolvePoolSize(), is(nullValue()));
    }

    @Test
    public void resolvePoolSize_SpiScopeWins() {
        System.setProperty("kc.cache-redis-max-pool-size", "9999");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty().with("max-pool-size", "256"));
        assertThat(f.resolvePoolSize(), is(256));
    }

    @Test
    public void resolvePoolSize_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-max-pool-size", "128");
        assertThat(factoryWithScope(TestConfigScope.empty()).resolvePoolSize(), is(128));
    }

    @Test(expected = RuntimeException.class)
    public void resolvePoolSize_NonNumericFailsFast() {
        factoryWithScope(TestConfigScope.empty().with("max-pool-size", "big")).resolvePoolSize();
    }

    @Test(expected = RuntimeException.class)
    public void resolvePoolSize_NonPositiveFailsFast() {
        factoryWithScope(TestConfigScope.empty().with("max-pool-size", "0")).resolvePoolSize();
    }

    @Test
    public void resolveMinIdle_SpiScopeWins() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty().with("min-idle", "32"));
        assertThat(f.resolveMinIdle(), is(32));
    }

    @Test
    public void resolveTopologyRefreshSeconds_NullWhenUnset() {
        assertThat(factoryWithScope(TestConfigScope.empty()).resolveTopologyRefreshSeconds(), is(nullValue()));
    }

    @Test
    public void resolveTopologyRefreshSeconds_SpiScopeWins() {
        System.setProperty("kc.cache-redis-topology-refresh-seconds", "99");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty().with("topology-refresh-seconds", "5"));
        assertThat(f.resolveTopologyRefreshSeconds(), is(5));
    }

    @Test
    public void resolveTopologyRefreshSeconds_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-topology-refresh-seconds", "10");
        assertThat(factoryWithScope(TestConfigScope.empty()).resolveTopologyRefreshSeconds(), is(10));
    }

    @Test(expected = RuntimeException.class)
    public void resolveTopologyRefreshSeconds_NonPositiveFailsFast() {
        factoryWithScope(TestConfigScope.empty().with("topology-refresh-seconds", "0")).resolveTopologyRefreshSeconds();
    }

    @Test
    public void overrides_ReachBuiltConfig() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty()
                .with("max-pool-size", "256").with("min-idle", "32").with("topology-refresh-seconds", "5"));
        RedisConnectionConfig cfg = f.applyResolvedOverrides(RedisConnectionConfig.parse("redis://localhost:6379"));
        assertThat(cfg.getPoolMaxSize(), is(256));
        assertThat(cfg.getPoolMinSize(), is(32));
        assertThat(cfg.getTopologyRefreshSeconds(), is(5));
    }

    @Test
    public void defaults_PreservedWhenUnset() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        RedisConnectionConfig cfg = f.applyResolvedOverrides(RedisConnectionConfig.parse("redis://localhost:6379"));
        assertThat(cfg.getPoolMaxSize(), is(64));
        assertThat(cfg.getPoolMinSize(), is(16));
        assertThat(cfg.getTopologyRefreshSeconds(), is(30));
    }
}
