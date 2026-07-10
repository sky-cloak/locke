/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.cache.redis;

import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.Config;
import org.keycloak.common.Profile;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;

/**
 * Functional-parity guard (docs/adr/0004). Every SPI that has a provider factory on the
 * classpath must keep at least one factory ENABLED under {@code KC_CACHE=redis} — either a
 * Redis-native factory or a backend-agnostic one. If an SPI's only factory is an Infinispan
 * one guarded off under redis, {@code session.getProvider(...)} resolves to null and the
 * feature breaks. That is what took down external-IdP brokering: the only
 * {@code PublicKeyStorageProvider} was the Infinispan one, disabled under redis, with no
 * Redis replacement.
 *
 * <p>This walks every registered {@link Spi} rather than a hand-listed few, so an SPI that
 * upstream adds later cannot silently arrive with no provider under redis. When a new SPI
 * legitimately has none, add it to {@link #EXPECTED_ABSENT_UNDER_REDIS} with a reason —
 * the exemption is then a deliberate, reviewed decision instead of a null at runtime.
 *
 * <p>{@code model/redis} depends on {@code model/infinispan}, so the ServiceLoader sees both
 * the Infinispan factories (filtered out under redis) and Locke's Redis ones.
 */
public class RedisProviderParityTest {

    /**
     * SPIs with no provider under redis by design. Each entry is a decision, not a gap.
     */
    private static final Set<String> EXPECTED_ABSENT_UNDER_REDIS = Set.of(
            // The one SPI redis is meant to displace. Leaving it enabled would start the
            // embedded cache manager and JGroups, which is the cost the fork exists to avoid.
            "connectionsInfinispan",
            // Configuration for those same embedded caches; nothing resolves it under redis.
            "cacheEmbedded"
    );

    @BeforeClass
    public static void initProfile() {
        // Several isSupported() guards read Profile.isFeatureEnabled(...).
        Profile.defaults();
    }

    @Test
    public void noSpiLosesItsOnlyProviderWhenSwitchingToRedis() {
        Config.Scope infinispan = scopeWithCache("infinispan");
        Config.Scope redis = scopeWithCache("redis");
        Map<String, List<String>> gaps = new TreeMap<>();

        for (Spi spi : safeLoad(Spi.class)) {
            if (EXPECTED_ABSENT_UNDER_REDIS.contains(spi.getName())) {
                continue;
            }

            List<String> underInfinispan = new ArrayList<>();
            List<String> underRedis = new ArrayList<>();
            for (ProviderFactory<?> f : safeLoad(spi.getProviderFactoryClass())) {
                if (isEnabled(f, infinispan)) {
                    underInfinispan.add(f.getClass().getName());
                }
                if (isEnabled(f, redis)) {
                    underRedis.add(f.getClass().getName());
                }
            }

            // Compare the two modes rather than testing redis alone: an SPI switched off by a
            // Profile feature (OID4VC, client types, ...) has no provider in either mode, and
            // that is not a redis regression.
            if (underInfinispan.isEmpty() || !underRedis.isEmpty()) {
                continue;
            }
            gaps.put(spi.getName(), underInfinispan);
        }

        assertThat("SPIs that have a provider under KC_CACHE=infinispan but none under KC_CACHE=redis. "
                        + "session.getProvider() would return null and the feature would break. Either ship a Redis "
                        + "factory, keep a backend-agnostic one enabled, or add the SPI to EXPECTED_ABSENT_UNDER_REDIS "
                        + "with a reason. Factories lost per SPI: " + gaps,
                gaps, anEmptyMap());
    }

    /**
     * ServiceLoader iteration over the full Keycloak classpath hits entries this module cannot
     * instantiate (a factory whose dependencies aren't here). Those say nothing about redis
     * parity, so skip them instead of failing the sweep.
     */
    private static <T> List<T> safeLoad(Class<T> service) {
        List<T> loaded = new ArrayList<>();
        Iterator<T> it = ServiceLoader.load(service).iterator();
        for (int guard = 0; guard < 10_000; guard++) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                loaded.add(it.next());
            } catch (ServiceConfigurationError | NoClassDefFoundError e) {
                // Skip this provider entry and keep going.
            }
        }
        return loaded;
    }

    private static boolean isEnabled(ProviderFactory<?> f, Config.Scope scope) {
        if (!(f instanceof EnvironmentDependentProviderFactory edpf)) {
            return true;
        }
        try {
            return edpf.isSupported(scope);
        } catch (RuntimeException e) {
            // A guard that cannot answer outside a running server tells us nothing about
            // parity; treat it as enabled rather than reporting a false gap.
            return true;
        }
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
