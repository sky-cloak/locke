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
import org.redisson.config.Config;

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

        switch (config.getMode()) {
            case STANDALONE:
                // Get first host for standalone mode
                String address = config.getHosts().isEmpty() ?
                        "redis://localhost:6379" :
                        "redis://" + config.getHosts().get(0).getHost() + ":" + config.getHosts().get(0).getPort();

                redissonConfig.useSingleServer()
                        .setAddress(address)
                        .setDatabase(config.getDatabase())
                        .setPassword(config.getPassword())
                        .setConnectionPoolSize(config.getPoolMaxSize())
                        .setConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());

                logger.infof("Creating Redisson client for standalone Redis: %s", address);
                break;

            case SENTINEL:
                String masterName = config.getSentinelMasterId() != null ?
                        config.getSentinelMasterId() : "mymaster";

                redissonConfig.useSentinelServers()
                        .setMasterName(masterName)
                        .setDatabase(config.getDatabase())
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxSize())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());

                // Add sentinel addresses
                for (RedisConnectionConfig.HostPort host : config.getHosts()) {
                    String sentinelAddress = "redis://" + host.getHost() + ":" + host.getPort();
                    redissonConfig.useSentinelServers().addSentinelAddress(sentinelAddress);
                }

                logger.infof("Creating Redisson client for Redis Sentinel: master=%s, sentinels=%d",
                        masterName, config.getHosts().size());
                break;

            case CLUSTER:
                redissonConfig.useClusterServers()
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxSize())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinSize())
                        .setTimeout((int) config.getTimeout().toMillis())
                        .setConnectTimeout((int) config.getTimeout().toMillis())
                        .setRetryAttempts(config.getRetryAttempts())
                        .setRetryInterval((int) config.getRetryDelay().toMillis());

                // Add cluster nodes
                for (RedisConnectionConfig.HostPort host : config.getHosts()) {
                    String clusterAddress = "redis://" + host.getHost() + ":" + host.getPort();
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
