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

package org.keycloak.keys.redis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.keycloak.Config;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.keys.PublicKeyStorageProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Redis-mode public key storage factory. Active when {@code KC_CACHE=redis}; the Infinispan
 * factory is disabled in that mode, so this becomes the sole {@code PublicKeyStorageProvider}
 * and {@code session.getProvider(PublicKeyStorageProvider.class)} resolves to it. Closes the
 * parity gap that broke external-IdP token verification under redis (docs/adr/0004).
 */
public class RedisPublicKeyStorageProviderFactory implements PublicKeyStorageProviderFactory, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "redis";

    private volatile Cache<String, PublicKeysEntry> keysCache;

    private final Map<String, FutureTask<PublicKeysEntry>> tasksInProgress = new ConcurrentHashMap<>();

    private int minTimeBetweenRequests;
    private int maxCacheTime;

    @Override
    public PublicKeyStorageProvider create(KeycloakSession session) {
        lazyInit();
        return new RedisPublicKeyStorageProvider(keysCache, tasksInProgress, minTimeBetweenRequests, maxCacheTime);
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name("minTimeBetweenRequests")
                    .type("int")
                    .helpText("Minimum interval in seconds between two requests to retrieve the new public keys. "
                            + "The server will always try to download new public keys when a single key is requested and not found. "
                            + "However it will avoid the download if the previous refresh was done less than 10 seconds ago (by default). "
                            + "This behavior is used to avoid DoS attacks against the external keys endpoint.")
                    .defaultValue(10)
                    .add()
                .property()
                    .name("maxCacheTime")
                    .type("int")
                    .helpText("Maximum interval in seconds that keys are cached when they are retrieved via all keys methods. "
                            + "When all keys for the entry are retrieved there is no way to detect if a key is missing "
                            + "(different to the case when the key is retrieved via ID for example). "
                            + "In that situation this option forces a refresh from time to time. "
                            + "This time can be overriden by the protocol (for example using cacheDuration or validUntil in the SAML descriptor). "
                            + "Default 24 hours.")
                    .defaultValue(24 * 60 * 60)
                    .add()
                .build();
    }

    @Override
    public void init(Config.Scope config) {
        minTimeBetweenRequests = config.getInt("minTimeBetweenRequests", 10);
        maxCacheTime = config.getInt("maxCacheTime", 24 * 60 * 60);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No cross-node invalidation: keys are per-node, refreshed by TTL (see the provider javadoc).
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

    private void lazyInit() {
        if (keysCache == null) {
            synchronized (this) {
                if (keysCache == null) {
                    this.keysCache = Caffeine.newBuilder()
                            .maximumSize(10_000)
                            .expireAfterWrite(maxCacheTime, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
    }
}
