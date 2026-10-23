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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Spins a TLS-enabled Redis container using the pre-baked PEM fixtures in
 * {@code src/test/resources/tls/}. The fixtures are copied to a JUnit-friendly
 * temp directory and bind-mounted into the container at {@code /tls/}.
 *
 * <p>Redis is started with {@code --tls-port 6379 --port 0} (TLS only; plaintext
 * disabled) and {@code --tls-auth-clients no} (mTLS is out of scope for phase 1).</p>
 */
public final class RedisTlsTestContainer {

    private static final String REDIS_IMAGE = "redis:7";
    private static final int TLS_PORT = 6379;

    private GenericContainer<?> container;
    private Path tlsDir;
    private Path caFile;

    public void start() throws IOException {
        // Copy fixtures from the classpath into a temp dir so Testcontainers can mount them
        // by path. The Locke test JAR doesn't expose `src/test/resources/tls` as a directory
        // at runtime when run from a packaged tree, so the copy is the robust path.
        tlsDir = Files.createTempDirectory("locke-tls-it");
        Path serverCrt = copyResource("tls/server.crt", tlsDir.resolve("server.crt"));
        Path serverKey = copyResource("tls/server.key", tlsDir.resolve("server.key"));
        caFile = copyResource("tls/ca.crt", tlsDir.resolve("ca.crt"));

        container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(TLS_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(serverCrt), "/tls/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(serverKey), "/tls/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(caFile), "/tls/ca.crt")
                .withCommand("redis-server",
                        "--tls-port", "6379",
                        "--port", "0",
                        "--tls-cert-file", "/tls/server.crt",
                        "--tls-key-file", "/tls/server.key",
                        "--tls-ca-cert-file", "/tls/ca.crt",
                        "--tls-auth-clients", "no");
        container.start();
    }

    private static Path copyResource(String classpath, Path dest) throws IOException {
        try (InputStream in = RedisTlsTestContainer.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("Missing test resource: " + classpath);
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return container.getMappedPort(TLS_PORT);
    }

    public String redissConnectionUri() {
        return String.format("rediss://%s:%d", host(), port());
    }

    public File caCertFile() {
        return caFile.toFile();
    }

    public void close() {
        if (container != null) {
            container.stop();
            container = null;
        }
        if (tlsDir != null) {
            try {
                Files.walk(tlsDir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(p -> p.toFile().delete());
            } catch (IOException ignored) { }
            tlsDir = null;
        }
    }
}
