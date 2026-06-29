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

package org.keycloak.connections.redis;

import org.junit.After;
import org.junit.Test;
import org.keycloak.Config;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the Redis URL resolution fallback. Under `start --optimized` the runtime
 * property mapper may not populate the SPI scope, so the factory must fall back to the
 * user-facing kc.cache-redis-url / KC_CACHE_REDIS_URL before defaulting to localhost.
 */
public class RedisUrlResolutionTest {

    private static final String SYS_PROP = "kc.cache-redis-url";

    @After
    public void clearSysProp() {
        System.clearProperty(SYS_PROP);
    }

    private DefaultRedisConnectionProviderFactory factoryWithSpiUrl(String spiUrl) {
        DefaultRedisConnectionProviderFactory factory = new DefaultRedisConnectionProviderFactory();
        factory.init(scopeReturningUrl(spiUrl));
        return factory;
    }

    @Test
    public void spiScopeUrlWins() {
        System.setProperty(SYS_PROP, "redis://ignored:6379");
        DefaultRedisConnectionProviderFactory factory = factoryWithSpiUrl("redis://from-spi:6379");
        assertThat(factory.resolveConnectionUri(), is("redis://from-spi:6379"));
    }

    @Test
    public void fallsBackToUserFacingPropertyWhenSpiEmpty() {
        // SPI scope returns null (the --optimized case); the user-facing option is set.
        System.setProperty(SYS_PROP, "redis://from-prop:6379");
        DefaultRedisConnectionProviderFactory factory = factoryWithSpiUrl(null);
        assertThat(factory.resolveConnectionUri(), is("redis://from-prop:6379"));
    }

    @Test
    public void defaultsToLocalhostWhenNothingSet() {
        DefaultRedisConnectionProviderFactory factory = factoryWithSpiUrl(null);
        assertThat(factory.resolveConnectionUri(), is("redis://localhost:6379"));
    }

    @Test
    public void blankSpiUrlIsTreatedAsUnset() {
        System.setProperty(SYS_PROP, "redis://from-prop:6379");
        DefaultRedisConnectionProviderFactory factory = factoryWithSpiUrl("   ");
        assertThat(factory.resolveConnectionUri(), is("redis://from-prop:6379"));
    }

    private Config.Scope scopeReturningUrl(String url) {
        return new Config.Scope() {
            @Override public String get(String key) { return "url".equals(key) ? url : null; }
            @Override public String get(String key, String defaultValue) {
                String v = get(key); return v != null ? v : defaultValue;
            }
            @Override public Integer getInt(String key) { return null; }
            @Override public Integer getInt(String key, Integer defaultValue) { return defaultValue; }
            @Override public Long getLong(String key) { return null; }
            @Override public Long getLong(String key, Long defaultValue) { return defaultValue; }
            @Override public Boolean getBoolean(String key) { return null; }
            @Override public Boolean getBoolean(String key, Boolean defaultValue) { return defaultValue; }
            @Override public String[] getArray(String key) { return null; }
            @Override public Config.Scope scope(String... scope) { return this; }
            @Override public Config.Scope root() { return this; }
            @Override public Set<String> getPropertyNames() { return Set.of(); }
        };
    }
}
