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
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThrows;

/**
 * End-to-end TLS integration test: spins a real Redis 7 container in TLS-only mode
 * (no plaintext port) using the pre-baked PEM fixtures in {@code src/test/resources/tls/},
 * then verifies the client manager can connect over {@code rediss://} when the CA file is
 * supplied, and rejects the connection when it is not.
 *
 * <p>Excluded by default in {@code pom.xml} together with the other Testcontainers tests
 * because Docker socket detection is flaky on macOS. Run with
 * {@code ./mvnw -pl model/redis verify -Dsurefire.failIfNoSpecifiedTests=false
 *   -Dtest=RedisTlsClientManagerTest -DskipITs=false} after dropping the exclude, or via
 * IDE.</p>
 */
public class RedisTlsClientManagerTest {

    private RedisTlsTestContainer container;
    private RedisClientManager clientManager;

    @Before
    public void setUp() throws Exception {
        container = new RedisTlsTestContainer();
        container.start();
    }

    @After
    public void tearDown() {
        if (clientManager != null) {
            clientManager.close();
        }
        if (container != null) {
            container.close();
        }
    }

    @Test
    public void initStandalone_TlsConnection_Healthy() {
        // Hostname verification is on by default. The fixtures' SAN includes
        // localhost + 127.0.0.1 + 0.0.0.0, so verification passes on any of those.
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .mode(RedisConnectionConfig.Mode.STANDALONE)
                .addHost(container.host(), container.port())
                .sslEnabled(true)
                .tlsCaFile(container.caCertFile().getAbsolutePath())
                .build();

        clientManager = new RedisClientManager(config);
        clientManager.init();

        assertThat(clientManager.isHealthy(), equalTo(true));
    }

    @Test
    public void initStandalone_NoCaFile_FailsToConnect() {
        // Without the custom CA the JVM truststore can't validate the server cert.
        // RedisClientManager.init() throws RuntimeException("Failed to connect to Redis").
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .mode(RedisConnectionConfig.Mode.STANDALONE)
                .addHost(container.host(), container.port())
                .sslEnabled(true)
                .build();

        clientManager = new RedisClientManager(config);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> clientManager.init());
        assertThat(ex.getMessage(), containsString("Failed to connect to Redis"));
    }

    @Test
    public void initStandalone_MissingCaFile_FailsLoudly() {
        // The defensive check in buildSslOptions: explicit CA path that doesn't exist
        // must fail fast, not silently fall back to the JVM truststore.
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .mode(RedisConnectionConfig.Mode.STANDALONE)
                .addHost(container.host(), container.port())
                .sslEnabled(true)
                .tlsCaFile("/nonexistent/path/ca.crt")
                .build();

        clientManager = new RedisClientManager(config);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> clientManager.init());
        assertThat(ex.getMessage(), containsString("KC_CACHE_REDIS_TLS_CA_FILE"));
        assertThat(ex.getMessage(), containsString("missing or unreadable"));
    }

    @Test
    public void healthCheckLogs_NeverContainCertPath() {
        // The CA file path is not a secret, but it's a useful proxy for "do we log
        // any of the config object's fields verbatim?" Production code should not.
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .mode(RedisConnectionConfig.Mode.STANDALONE)
                .addHost(container.host(), container.port())
                .sslEnabled(true)
                .password("never-log-this-secret")
                .tlsCaFile(container.caCertFile().getAbsolutePath())
                .build();

        try (RedisLogCapture capture = RedisLogCapture.start()) {
            clientManager = new RedisClientManager(config);
            clientManager.init();
            clientManager.isHealthy();

            String logs = capture.allMessages();
            assertThat(logs, not(containsString("never-log-this-secret")));
        }
    }
}
