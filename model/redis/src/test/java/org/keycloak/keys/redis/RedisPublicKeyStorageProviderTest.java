/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.keys.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.Test;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;

import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Confirms the Redis-mode public key store actually resolves and returns a key, i.e. the
 * exact path that returned null / NPE'd under KC_CACHE=redis before docs/adr/0004: an
 * external-IdP / client key is loaded via the {@link PublicKeyLoader}, cached, and served.
 */
public class RedisPublicKeyStorageProviderTest {

    @Test
    public void getPublicKey_loadsViaLoaderThenServesFromCache() throws Exception {
        Cache<String, PublicKeysEntry> cache = Caffeine.newBuilder().build();
        RedisPublicKeyStorageProvider provider =
                new RedisPublicKeyStorageProvider(cache, new ConcurrentHashMap<>(), 10, 24 * 60 * 60);

        KeyWrapper kw = new KeyWrapper();
        kw.setKid("kid-1");
        kw.setAlgorithm("RS256");
        kw.setType("RSA");
        kw.setPublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());

        AtomicInteger loads = new AtomicInteger();
        PublicKeyLoader loader = () -> {
            loads.incrementAndGet();
            return new PublicKeysWrapper(List.of(kw));
        };

        KeyWrapper got = provider.getPublicKey("realm-a:client-x", "kid-1", "RS256", loader);
        assertThat("provider resolved and returned the IdP key (previously null under redis)", got, notNullValue());
        assertThat(got.getKid(), is("kid-1"));
        assertThat("fetched from the IdP exactly once", loads.get(), is(1));

        KeyWrapper cached = provider.getPublicKey("realm-a:client-x", "kid-1", "RS256", loader);
        assertThat(cached, notNullValue());
        assertThat("second lookup served from the per-node cache, no refetch", loads.get(), is(1));
    }
}
