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
                redissonConfig.useSingleServer()
                        .setAddress("redis://" + config.getHost() + ":" + config.getPort())
                        .setDatabase(config.getDatabase())
                        .setPassword(config.getPassword())
                        .setConnectionPoolSize(config.getPoolMaxTotal())
                        .setConnectionMinimumIdleSize(config.getPoolMinIdle())
                        .setTimeout(config.getTimeout())
                        .setConnectTimeout(config.getTimeout())
                        .setSslEnableEndpointIdentification(config.isSslEnabled());

                if (config.isSslEnabled()) {
                    redissonConfig.useSingleServer().setSslProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                }

                logger.infof("Creating Redisson client for standalone Redis: %s:%d",
                        config.getHost(), config.getPort());
                break;

            case SENTINEL:
                String masterName = config.getSentinelMasterId() != null ?
                        config.getSentinelMasterId() : "mymaster";

                redissonConfig.useSentinelServers()
                        .setMasterName(masterName)
                        .setDatabase(config.getDatabase())
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxTotal())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinIdle())
                        .setTimeout(config.getTimeout())
                        .setConnectTimeout(config.getTimeout());

                // Add sentinel addresses
                if (config.getSentinelNodes() != null) {
                    for (String node : config.getSentinelNodes()) {
                        redissonConfig.useSentinelServers().addSentinelAddress("redis://" + node);
                    }
                }

                if (config.getSentinelPassword() != null) {
                    redissonConfig.useSentinelServers().setSentinelPassword(config.getSentinelPassword());
                }

                logger.infof("Creating Redisson client for Redis Sentinel: master=%s, sentinels=%d",
                        masterName, config.getSentinelNodes() != null ? config.getSentinelNodes().size() : 0);
                break;

            case CLUSTER:
                redissonConfig.useClusterServers()
                        .setPassword(config.getPassword())
                        .setMasterConnectionPoolSize(config.getPoolMaxTotal())
                        .setMasterConnectionMinimumIdleSize(config.getPoolMinIdle())
                        .setTimeout(config.getTimeout())
                        .setConnectTimeout(config.getTimeout());

                // Add cluster nodes
                if (config.getClusterNodes() != null) {
                    for (String node : config.getClusterNodes()) {
                        redissonConfig.useClusterServers().addNodeAddress("redis://" + node);
                    }
                }

                logger.infof("Creating Redisson client for Redis Cluster: nodes=%d",
                        config.getClusterNodes() != null ? config.getClusterNodes().size() : 0);
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
