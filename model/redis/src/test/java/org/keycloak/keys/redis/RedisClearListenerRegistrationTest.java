/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.keys.redis;

import org.junit.Test;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterListener;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.crl.redis.RedisCrlStorageProviderFactory;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.models.KeycloakSession;

import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * A "clear keys cache" or "clear CRL cache" issued on one node must clear every node. The
 * listener that applies a remote clear is registered by the storage factory, so a node that
 * has cached something hears the clear even though it never created the cache provider
 * itself (only the node that served the admin request does).
 */
public class RedisClearListenerRegistrationTest {

    /** Records the listeners registered on a stub {@link ClusterProvider}. */
    private static final class RecordingCluster {
        final Map<String, List<ClusterListener>> listeners = new HashMap<>();

        ClusterProvider provider() {
            return (ClusterProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { ClusterProvider.class }, (proxy, method, args) -> {
                        if (method.getName().equals("registerListener")) {
                            listeners.computeIfAbsent((String) args[0], k -> new ArrayList<>()).add((ClusterListener) args[1]);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        KeycloakSession session() {
            ClusterProvider cluster = provider();
            return (KeycloakSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { KeycloakSession.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getProvider") && args.length == 1 && args[0] == ClusterProvider.class) {
                            return cluster;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        void fire(String eventKey) {
            listeners.getOrDefault(eventKey, List.of()).forEach(l -> l.eventReceived(null));
        }
    }

    private static Config.Scope emptyScope() {
        return new Config.SystemPropertiesScope("keycloak.test.redis.clear.listener.");
    }

    @Test
    public void publicKeyStorageRegistersClearListenerOnce() throws Exception {
        RecordingCluster cluster = new RecordingCluster();
        RedisPublicKeyStorageProviderFactory factory = new RedisPublicKeyStorageProviderFactory();
        factory.init(emptyScope());

        KeycloakSession session = cluster.session();
        PublicKeyStorageProvider provider = factory.create(session);
        factory.create(session);
        assertThat(cluster.listeners.keySet(), contains(RedisPublicKeyStorageProviderFactory.KEYS_CLEAR_CACHE_EVENTS));
        assertThat(cluster.listeners.get(RedisPublicKeyStorageProviderFactory.KEYS_CLEAR_CACHE_EVENTS).size(), is(1));

        KeyWrapper key = new KeyWrapper();
        key.setKid("kid-1");
        key.setAlgorithm("RS256");
        key.setType("RSA");
        key.setPublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());
        AtomicInteger loads = new AtomicInteger();
        PublicKeyLoader loader = () -> {
            loads.incrementAndGet();
            return new PublicKeysWrapper(List.of(key));
        };

        provider.getPublicKey("realm-a:client-x", "kid-1", "RS256", loader);
        provider.getPublicKey("realm-a:client-x", "kid-1", "RS256", loader);
        assertThat("served from the per-node cache", loads.get(), is(1));

        cluster.fire(RedisPublicKeyStorageProviderFactory.KEYS_CLEAR_CACHE_EVENTS);

        provider.getPublicKey("realm-a:client-x", "kid-1", "RS256", loader);
        assertThat("a clear from another node dropped the cached key", loads.get(), is(2));
    }

    @Test
    public void crlStorageRegistersClearListenerOnce() {
        RecordingCluster cluster = new RecordingCluster();
        RedisCrlStorageProviderFactory factory = new RedisCrlStorageProviderFactory();
        factory.init(emptyScope());

        KeycloakSession session = cluster.session();
        factory.create(session);
        factory.create(session);
        assertThat(cluster.listeners.keySet(), contains(RedisCrlStorageProviderFactory.CRL_CLEAR_CACHE_EVENTS));
        assertThat(cluster.listeners.get(RedisCrlStorageProviderFactory.CRL_CLEAR_CACHE_EVENTS).size(), is(1));
    }
}
