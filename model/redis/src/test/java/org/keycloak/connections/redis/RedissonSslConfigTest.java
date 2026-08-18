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

import org.junit.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

/**
 * Inspects the {@link org.redisson.config.BaseConfig} graph that
 * {@link RedissonClientFactory#applySslConfig} writes, without spinning a live Redis.
 * Mirrors the URI-graph assertion pattern used for the Lettuce side.
 *
 * <p>End-to-end Redisson + TLS is exercised by the manual smoke documented in the PR
 * description; this class is the fast unit gate.</p>
 */
public class RedissonSslConfigTest {

    private static final Path TEST_CA = Paths.get(
            "src/test/resources/tls/ca.crt").toAbsolutePath();

    private SingleServerConfig newSingleServerConfig() {
        // Construct a per-mode config through the public Config builder. SingleServerConfig
        // extends BaseConfig and exposes the same SSL knobs as Sentinel / Cluster configs,
        // so a single mode is sufficient to exercise the helper.
        return new Config().useSingleServer();
    }

    @Test
    public void sslDisabled_isNoop() {
        SingleServerConfig cfg = newSingleServerConfig();
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .addHost("h", 6379) // sslEnabled defaults to false
                .build();

        RedissonClientFactory.applySslConfig(cfg, conn);

        assertThat(cfg.getSslTruststore(), is(nullValue()));
        // Redisson defaults endpoint identification to true; the helper must not touch it
        // when SSL is disabled, so it stays at Redisson's default.
        assertThat(cfg.isSslEnableEndpointIdentification(), is(true));
    }

    @Test
    public void sslEnabledNoCaFile_setsHostnameVerifyOnly() {
        SingleServerConfig cfg = newSingleServerConfig();
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .addHost("h", 6379)
                .sslEnabled(true)
                // tlsVerifyHostname defaults to true
                .build();

        RedissonClientFactory.applySslConfig(cfg, conn);

        assertThat(cfg.isSslEnableEndpointIdentification(), is(true));
        assertThat("no CA file -> Redisson falls back to JVM truststore",
                cfg.getSslTruststore(), is(nullValue()));
    }

    @Test
    public void verifyHostnameFalse_disablesEndpointIdentification() {
        SingleServerConfig cfg = newSingleServerConfig();
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .addHost("h", 6379)
                .sslEnabled(true)
                .tlsVerifyHostname(false)
                .build();

        RedissonClientFactory.applySslConfig(cfg, conn);

        assertThat(cfg.isSslEnableEndpointIdentification(), is(false));
    }

    @Test
    public void caFile_loadedIntoTruststore() throws Exception {
        SingleServerConfig cfg = newSingleServerConfig();
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .addHost("h", 6379)
                .sslEnabled(true)
                .tlsCaFile(TEST_CA.toString())
                .build();

        RedissonClientFactory.applySslConfig(cfg, conn);

        URL truststoreUrl = cfg.getSslTruststore();
        assertThat(truststoreUrl, is(notNullValue()));

        // Load the truststore Redisson will read and confirm the CA is in there.
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = truststoreUrl.openStream()) {
            ks.load(in, "changeit".toCharArray());
        }
        assertThat(ks.size(), is(greaterThan(0)));
    }

    @Test
    public void missingCaFile_failsLoudly() {
        SingleServerConfig cfg = newSingleServerConfig();
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .addHost("h", 6379)
                .sslEnabled(true)
                .tlsCaFile("/nonexistent/path/ca.crt")
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RedissonClientFactory.applySslConfig(cfg, conn));
        assertThat(ex.getMessage(), containsString("KC_CACHE_REDIS_TLS_CA_FILE"));
        assertThat(ex.getMessage(), containsString("missing or unreadable"));
    }

    @Test
    public void pemToTruststore_producesValidJks() throws Exception {
        URL url = RedissonClientFactory.pemToTruststore(TEST_CA.toString());

        // The truststore is a temp file deleted on JVM exit; while the test runs, it must
        // exist and parse as a JKS with at least one entry (our test CA).
        Path path = Paths.get(url.toURI());
        assertThat(Files.exists(path), is(true));

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, "changeit".toCharArray());
        }
        assertThat(ks.size(), is(equalTo(1)));
    }

    @Test
    public void clusterReadAndSubscriptionModeAreMaster() {
        // Redisson is used only for master-side primitives (locks, pub/sub, the startup map).
        // The cluster config must pin readMode/subscriptionMode to MASTER so it never opens replica
        // connections / emits READONLY — which AMR's OSS clustering policy rejects.
        RedisConnectionConfig conn = new RedisConnectionConfig.Builder()
                .mode(RedisConnectionConfig.Mode.CLUSTER)
                .addHost("h1", 6379)
                .addHost("h2", 6379)
                .build();

        Config c = RedissonClientFactory.buildRedissonConfig(conn);

        // useClusterServers() returns the already-built cluster config (public accessor).
        org.redisson.config.ClusterServersConfig cs = c.useClusterServers();
        assertThat(cs.getReadMode(), is(org.redisson.config.ReadMode.MASTER));
        assertThat(cs.getSubscriptionMode(), is(org.redisson.config.SubscriptionMode.MASTER));
    }
}
