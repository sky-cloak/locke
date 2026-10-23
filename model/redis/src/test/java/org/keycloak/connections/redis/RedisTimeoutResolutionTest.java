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

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression: the documented {@code cache-redis-timeout} option must actually reach the built
 * RedisConnectionConfig. Previously the factory never resolved it and applyResolvedOverrides()
 * rebuilt the config without a timeout, so the hardcoded Builder default always won regardless of
 * the option. These tests pin the three-tier resolution and, crucially, that the resolved value
 * lands on {@code config.getTimeout()}.
 */
public class RedisTimeoutResolutionTest {

    @After
    public void clearSysProps() {
        System.clearProperty("kc.cache-redis-timeout");
    }

    private DefaultRedisConnectionProviderFactory factoryWithScope(TestConfigScope scope) {
        DefaultRedisConnectionProviderFactory factory = new DefaultRedisConnectionProviderFactory();
        factory.init(scope);
        return factory;
    }

    @Test
    public void resolveTimeout_NullWhenUnset() {
        assertThat(factoryWithScope(TestConfigScope.empty()).resolveTimeoutMillis(), is(nullValue()));
    }

    @Test
    public void resolveTimeout_SpiScopeWins() {
        System.setProperty("kc.cache-redis-timeout", "9999");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty().with("timeout", "1500"));
        assertThat(f.resolveTimeoutMillis(), is(1500));
    }

    @Test
    public void resolveTimeout_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-timeout", "750");
        assertThat(factoryWithScope(TestConfigScope.empty()).resolveTimeoutMillis(), is(750));
    }

    @Test(expected = RuntimeException.class)
    public void resolveTimeout_NonNumericFailsFast() {
        factoryWithScope(TestConfigScope.empty().with("timeout", "soon")).resolveTimeoutMillis();
    }

    @Test(expected = RuntimeException.class)
    public void resolveTimeout_NonPositiveFailsFast() {
        factoryWithScope(TestConfigScope.empty().with("timeout", "0")).resolveTimeoutMillis();
    }

    @Test
    public void override_ReachesBuiltConfig() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty().with("timeout", "500"));
        RedisConnectionConfig cfg = f.applyResolvedOverrides(RedisConnectionConfig.parse("redis://localhost:6379"));
        assertThat(cfg.getTimeout(), is(Duration.ofMillis(500)));
    }

    @Test
    public void default_PreservedWhenUnset() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        RedisConnectionConfig cfg = f.applyResolvedOverrides(RedisConnectionConfig.parse("redis://localhost:6379"));
        assertThat(cfg.getTimeout(), is(Duration.ofMillis(1000)));
    }
}
