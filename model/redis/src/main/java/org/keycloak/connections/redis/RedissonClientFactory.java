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

import org.jboss.logging.Logger;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Factory for creating Redisson client instances.
 * Redisson is used for distributed primitives (locks, pub/sub, maps).
 *
 * @author Claude Code
 */
public class RedissonClientFactory {

    private static final Logger logger = Logger.getLogger(RedissonClientFactory.class);

    /**
     * Creates a Redisson client from RedisConnectionConfig.
     *
     * @param config the Redis connection configuration
     * @return Redisson client instance
     */
    public static RedissonClient createClient(RedisConnectionConfig config) {
        Config redissonConfig = new Config();
        String scheme = config.isSslEnabled() ? "rediss://" : "redis://";

        switch (config.getMode()) {
            case STANDALONE:
                // Get first host for standalone mode
                String address = config.getHosts().isEmpty() ?
                        scheme + "localhost:6379" :
                        scheme + config.getHosts().get(0).getHost() + ":" + config.getHosts().get(0).getPort();

                org.redisson.config.SingleServerConfig single = redissonConfig.useSingleServer()
                        .setAddress(address)
                        .setDatabase(config.getDatabase())
                        .setUsername(config.getUsername())
                        .setPassword(config.getPassword())
                        .setConnectionPoolSize(config.getPoolMaxSize())
                        .setConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());
                applySslConfig(single, config);

                logger.infof("Creating Redisson client for standalone Redis: %s", address);
                break;

            case SENTINEL:
                String masterName = config.getSentinelMasterId() != null ?
                        config.getSentinelMasterId() : "mymaster";

                org.redisson.config.SentinelServersConfig sentinel = redissonConfig.useSentinelServers()
                        .setMasterName(masterName)
                        .setDatabase(config.getDatabase())
                        .setUsername(config.getUsername())
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxSize())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());
                applySslConfig(sentinel, config);

                // Add sentinel addresses
                for (RedisConnectionConfig.HostPort host : config.getHosts()) {
                    String sentinelAddress = scheme + host.getHost() + ":" + host.getPort();
                    redissonConfig.useSentinelServers().addSentinelAddress(sentinelAddress);
                }

                logger.infof("Creating Redisson client for Redis Sentinel: master=%s, sentinels=%d",
                        masterName, config.getHosts().size());
                break;

            case CLUSTER:
                org.redisson.config.ClusterServersConfig cluster = redissonConfig.useClusterServers()
                        .setUsername(config.getUsername())
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxSize())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());
                applySslConfig(cluster, config);

                // Add cluster nodes
                for (RedisConnectionConfig.HostPort host : config.getHosts()) {
                    String clusterAddress = scheme + host.getHost() + ":" + host.getPort();
                    redissonConfig.useClusterServers().addNodeAddress(clusterAddress);
                }

                logger.infof("Creating Redisson client for Redis Cluster: nodes=%d",
                        config.getHosts().size());
                break;

            default:
                throw new IllegalArgumentException("Unsupported Redis mode: " + config.getMode());
        }

        // Common configuration
        redissonConfig.setCodec(new org.redisson.codec.SerializationCodec());
        redissonConfig.setThreads(16); // Default thread pool size
        redissonConfig.setNettyThreads(32); // Default netty thread pool

        try {
            RedissonClient client = Redisson.create(redissonConfig);
            logger.info("Redisson client created successfully");
            return client;
        } catch (Exception e) {
            logger.errorf(e, "Failed to create Redisson client");
            throw new RuntimeException("Failed to create Redisson client", e);
        }
    }

    /**
     * Apply TLS settings from a {@link RedisConnectionConfig} to a Redisson per-mode
     * config (single, sentinel, cluster: they all extend {@link BaseConfig}, which
     * is where the SSL knobs live).
     *
     * <p>Redisson's SSL APIs accept a JKS truststore via {@code setSslTruststore(URL)}
     * but do not accept a raw PEM file. To honor {@code KC_CACHE_REDIS_TLS_CA_FILE},
     * we load the PEM and write a one-shot in-memory truststore to a temp file, then
     * point Redisson at that.</p>
     */
    // package-private for unit testing
    static void applySslConfig(BaseConfig<?> cfg, RedisConnectionConfig config) {
        if (!config.isSslEnabled()) {
            return;
        }
        cfg.setSslEnableEndpointIdentification(config.isTlsVerifyHostname());
        if (config.getTlsCaFile() != null && !config.getTlsCaFile().isEmpty()) {
            try {
                cfg.setSslTruststore(pemToTruststore(config.getTlsCaFile()));
                // The password is required by KeyStore.load(...) but the truststore is
                // public; we use a fixed value rather than a generated one so the temp
                // file is fully reproducible from the inputs.
                cfg.setSslTruststorePassword("changeit");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load CA from " + config.getTlsCaFile(), e);
            }
        }
    }

    // package-private for unit testing
    static URL pemToTruststore(String caPath) throws Exception {
        File pem = new File(caPath);
        if (!pem.canRead()) {
            throw new RuntimeException("KC_CACHE_REDIS_TLS_CA_FILE points to a missing or unreadable file: " + caPath);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int i = 0;
        try (FileInputStream in = new FileInputStream(pem)) {
            for (Certificate c : cf.generateCertificates(in)) {
                ks.setCertificateEntry("locke-ca-" + (i++), c);
            }
        }
        if (i == 0) {
            throw new RuntimeException("No certificates found in " + caPath);
        }
        Path tmp = Files.createTempFile("locke-redis-truststore", ".jks");
        tmp.toFile().deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            ks.store(out, "changeit".toCharArray());
        }
        return tmp.toUri().toURL();
    }

    /**
     * Closes a Redisson client.
     *
     * @param client the client to close
     */
    public static void closeClient(RedissonClient client) {
        if (client != null && !client.isShutdown()) {
            try {
                client.shutdown();
                logger.info("Redisson client closed successfully");
            } catch (Exception e) {
                logger.warnf(e, "Error closing Redisson client");
            }
        }
    }
}
