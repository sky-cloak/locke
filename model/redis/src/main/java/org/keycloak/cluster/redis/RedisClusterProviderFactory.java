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

package org.keycloak.cluster.redis;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ClusterProviderFactory;
import org.keycloak.common.util.Time;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.connections.redis.TopologyInfo;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

/**
 * Factory for creating RedisClusterProvider instances.
 * Manages Redisson client, Pub/Sub manager, and lock manager lifecycle.
 */
public class RedisClusterProviderFactory implements ClusterProviderFactory, EnvironmentDependentProviderFactory {

    protected static final Logger logger = Logger.getLogger(RedisClusterProviderFactory.class);

    public static final String PROVIDER_ID = "redis";
    private static final String CLUSTER_STARTUP_TIME_KEY = "cluster-start-time";

    private volatile RedisClusterProvider clusterProvider;
    private volatile RedissonClient redissonClient;
    private volatile RedisDistributedLockManager lockManager;
    private volatile RedisPubSubEventManager pubSubManager;

    private final ExecutorService localExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName(this.getClass().getName() + "-" + thread.getName());
        return thread;
    });

    @Override
    public ClusterProvider create(KeycloakSession session) {
        return lazyInit(session);
    }

    private ClusterProvider lazyInit(KeycloakSession session) {
        if (clusterProvider != null) {
            return clusterProvider;
        }

        synchronized (this) {
            if (clusterProvider != null) {
                return clusterProvider;
            }

            // Get Redisson client from connection provider
            RedisConnectionProvider redisConnections = session.getProvider(RedisConnectionProvider.class);
            this.redissonClient = redisConnections.getRedissonClient();

            if (this.redissonClient == null) {
                throw new IllegalStateException("Redisson client not available from RedisConnectionProvider");
            }

            // Get topology info
            TopologyInfo topologyInfo = redisConnections.getTopologyInfo();

            // Initialize cluster startup time
            int clusterStartupTime = initClusterStartupTime(session);

            // Create lock manager
            this.lockManager = new RedisDistributedLockManager(redissonClient);

            // Create Pub/Sub manager
            this.pubSubManager = new RedisPubSubEventManager(
                    redissonClient,
                    topologyInfo.getMyNodeName(),
                    topologyInfo.getMySiteName()
            );

            // Create cluster provider
            this.clusterProvider = new RedisClusterProvider(
                    clusterStartupTime,
                    topologyInfo.getMyNodeName(),
                    topologyInfo.getMySiteName(),
                    lockManager,
                    pubSubManager,
                    localExecutor
            );

            logger.debugf("Redis cluster provider initialized for node %s in site %s",
                    topologyInfo.getMyNodeName(), topologyInfo.getMySiteName());

            return clusterProvider;
        }
    }

    protected int initClusterStartupTime(KeycloakSession session) {
        // Use Redisson RMapCache for cluster startup time coordination
        RMapCache<String, Integer> startupCache = redissonClient.getMapCache("keycloak:cluster:startup");

        Integer existingClusterStartTime = startupCache.get(CLUSTER_STARTUP_TIME_KEY);
        if (existingClusterStartTime != null) {
            if (logger.isDebugEnabled()) {
                logger.debugf("Loaded cluster startup time: %s", Time.toDate(existingClusterStartTime).toString());
            }
            return existingClusterStartTime;
        } else {
            // clusterStartTime not yet initialized. Let's try to put our startupTime
            int serverStartTime = (int) (session.getKeycloakSessionFactory().getServerStartupTimestamp() / 1000);

            existingClusterStartTime = startupCache.putIfAbsent(CLUSTER_STARTUP_TIME_KEY, serverStartTime);
            if (existingClusterStartTime == null) {
                if (logger.isDebugEnabled()) {
                    logger.debugf("Initialized cluster startup time to %s", Time.toDate(serverStartTime).toString());
                }
                return serverStartTime;
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debugf("Loaded cluster startup time: %s", Time.toDate(existingClusterStartTime).toString());
                }
                return existingClusterStartTime;
            }
        }
    }

    @Override
    public void init(Config.Scope config) {
        // Configuration is handled by RedisConnectionProvider
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Post-initialization can be added here if needed
    }

    @Override
    public void close() {
        synchronized (this) {
            if (pubSubManager != null) {
                pubSubManager.close();
                pubSubManager = null;
            }
            if (localExecutor != null) {
                localExecutor.shutdown();
            }
            clusterProvider = null;
            lockManager = null;
            redissonClient = null;
        }
        logger.debug("RedisClusterProviderFactory closed");
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
