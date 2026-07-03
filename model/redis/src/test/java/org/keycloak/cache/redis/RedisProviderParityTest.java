/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.cache.redis;

import org.junit.Test;
import org.keycloak.Config;
import org.keycloak.crl.CrlStorageProviderFactory;
import org.keycloak.keys.PublicKeyStorageProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Functional-parity guard (docs/adr/0004). Every SPI that has any provider factory must
 * have at least one factory ENABLED under {@code KC_CACHE=redis} — either a Redis-native
 * factory or a non-environment-dependent fallback. If an SPI's only factory is an
 * Infinispan one guarded off under redis, {@code session.getProvider(...)} resolves to
 * null and the feature breaks (this is what took down external-IdP brokering: the only
 * {@code PublicKeyStorageProvider} was the Infinispan one, disabled under redis, with no
 * Redis replacement).
 *
 * <p>These two SPIs are the confirmed gaps from that incident; the assertion generalises to
 * any SPI. {@code model/redis} depends on {@code model/infinispan}, so the ServiceLoader
 * sees both the Infinispan factories (filtered out under redis) and Locke's Redis ones.
 */
public class RedisProviderParityTest {

    @Test
    public void publicKeyStorage_hasFactoryEnabledUnderRedis() {
        assertSpiHasEnabledFactoryUnderRedis(PublicKeyStorageProviderFactory.class);
    }

    @Test
    public void crlStorage_hasFactoryEnabledUnderRedis() {
        assertSpiHasEnabledFactoryUnderRedis(CrlStorageProviderFactory.class);
    }

    private static <T extends ProviderFactory> void assertSpiHasEnabledFactoryUnderRedis(Class<T> spi) {
        Config.Scope redis = scopeWithCache("redis");
        List<String> enabled = new ArrayList<>();
        for (T f : ServiceLoader.load(spi)) {
            boolean supported = !(f instanceof EnvironmentDependentProviderFactory)
                    || ((EnvironmentDependentProviderFactory) f).isSupported(redis);
            if (supported) enabled.add(f.getClass().getName());
        }
        assertThat(spi.getSimpleName() + " has no factory enabled under KC_CACHE=redis; "
                        + "session.getProvider() would return null and the feature would break. Enabled=" + enabled,
                enabled.size(), greaterThanOrEqualTo(1));
    }

    // ---- Config.Scope stub: root().get("cache") returns the given value -------------

    private static Config.Scope scopeWithCache(String value) {
        Map<String, String> root = value == null ? Map.of() : Map.of("cache", value);
        return new MapConfigScope(root);
    }

    private static final class MapConfigScope implements Config.Scope {
        private final Map<String, String> values;
        MapConfigScope(Map<String, String> values) { this.values = values; }
        @Override public String get(String key) { return values.get(key); }
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public String[] getArray(String key) { return null; }
        @Override public Integer getInt(String key) { return null; }
        @Override public Integer getInt(String key, Integer defaultValue) { return defaultValue; }
        @Override public Long getLong(String key) { return null; }
        @Override public Long getLong(String key, Long defaultValue) { return defaultValue; }
        @Override public Boolean getBoolean(String key) { return null; }
        @Override public Boolean getBoolean(String key, Boolean defaultValue) { return defaultValue; }
        @Override public Config.Scope scope(String... scope) { return this; }
        @Override public java.util.Set<String> getPropertyNames() { return values.keySet(); }
        @Override public Config.Scope root() { return this; }
    }
}
