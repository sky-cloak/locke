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
import org.keycloak.Config;
import org.keycloak.cache.redis.L1InvalidationBus;
import org.keycloak.cache.redis.L1RedisCache;
import org.keycloak.cache.redis.LuaScripts;
import org.keycloak.cache.redis.PipelinedRedisCache;
import org.keycloak.cache.redis.RedisMetrics;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ServerInfoAwareProviderFactory;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Factory for creating DefaultRedisConnectionProvider instances.
 * Handles lazy initialization and lifecycle management.
 *
 * @author Keycloak Redis Team
 */
public class DefaultRedisConnectionProviderFactory
        implements RedisConnectionProviderFactory, ServerInfoAwareProviderFactory, EnvironmentDependentProviderFactory {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);
    private static final ReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();

    private Config.Scope config;
    private volatile RedisConnectionProvider connectionProvider;
    private volatile RedisClientManager clientManager;
    private volatile RedissonClient redissonClient;
    private volatile L1InvalidationBus l1Bus;
    private volatile LuaScripts luaScripts;
    private volatile PipelinedRedisCache pipelinedCache;
    private volatile RedisMetrics metrics;

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        return lazyInit();
    }

    /**
     * Lazy initialization with double-checked locking.
     * Same pattern as Infinispan to prevent race conditions.
     */
    private RedisConnectionProvider lazyInit() {
        if (connectionProvider != null) {
            return connectionProvider;
        }

        synchronized (this) {
            if (connectionProvider != null) {
                return connectionProvider;
            }

            logger.info("Initializing Redis connection provider");

            // Read configuration. parse() handles the URL itself; env / sysprop / SPI overrides
            // for username, password, and TLS settings are folded in below.
            String connectionUri = resolveConnectionUri();
            RedisConnectionConfig redisConfig = applyResolvedOverrides(RedisConnectionConfig.parse(connectionUri));

            // Create client manager
            this.clientManager = new RedisClientManager(redisConfig);
            clientManager.init();

            // Create Redisson client for distributed primitives
            this.redissonClient = RedissonClientFactory.createClient(redisConfig);
            logger.info("Redisson client initialized for distributed locks and pub/sub");

            // Create topology info
            String nodeName = config.get("nodeName");
            String siteName = config.get("siteName");
            TopologyInfo topologyInfo = new TopologyInfo(nodeName, siteName);

            logger.infof("Redis topology: %s", topologyInfo);

            // Initialize the L1 cache invalidation bus (skip in cluster mode for now —
            // cluster pub/sub requires per-shard subscriptions). Bus is null-safe in
            // DefaultRedisConnectionProvider so the L1 layer is bypassed if absent.
            L1RedisCache.L1Config l1Config = new L1RedisCache.L1Config(
                    config.getInt("l1MaxEntries", 10_000),
                    Duration.ofSeconds(config.getInt("l1TtlSeconds", 60))
            );
            boolean l1Enabled = config.getBoolean("l1Enabled", true);
            if (l1Enabled && clientManager.getStandaloneClient() != null) {
                this.l1Bus = new L1InvalidationBus(clientManager.getStandaloneClient());
                logger.infof("L1 cache enabled (max=%d entries, ttl=%s)",
                        l1Config.maxEntries, l1Config.ttl);
            } else {
                this.l1Bus = null;
                logger.info("L1 cache disabled");
            }

            // Tier 2 infra: Lua scripts (preloaded into Redis script cache) and pipeline factory.
            this.metrics = new RedisMetrics();
            this.luaScripts = new LuaScripts(clientManager, metrics);
            try {
                luaScripts.loadAll();
            } catch (Exception e) {
                logger.warnf(e, "Lua script preload failed; will fall back to inline EVAL on first use");
            }
            this.pipelinedCache = new PipelinedRedisCache(clientManager, metrics);

            // Create provider
            this.connectionProvider = new DefaultRedisConnectionProvider(
                    clientManager, redissonClient, topologyInfo, l1Bus, l1Config,
                    luaScripts, pipelinedCache, metrics);

            return connectionProvider;
        }
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
    }

    // Resolve the Redis URL. Primary source is the SPI scope (populated by the cache-redis-url
    // property mapper). Under `start --optimized` that runtime mapper may not populate the SPI
    // scope, so fall back to the user-facing option / env var before the localhost default —
    // otherwise KC_CACHE_REDIS_URL is silently ignored and Locke connects to localhost.
    // package-private for unit testing
    String resolveConnectionUri() {
        String uri = config.get("url");
        if (uri != null && !uri.isBlank()) {
            return uri;
        }
        String sys = System.getProperty("kc.cache-redis-url");
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String env = System.getenv("KC_CACHE_REDIS_URL");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "redis://localhost:6379";
    }

    // The username/password/TLS resolvers mirror resolveConnectionUri()'s three-tier fallback:
    // SPI scope (populated by the property mapper) -> system property -> env var. The same
    // `--optimized` failure mode applies: without the env fallback, KC_CACHE_REDIS_* options
    // are silently ignored under `start --optimized`.
    // package-private for unit testing
    String resolveUsername() {
        return resolveString("username", "kc.cache-redis-username", "KC_CACHE_REDIS_USERNAME");
    }

    String resolvePassword() {
        return resolveString("password", "kc.cache-redis-password", "KC_CACHE_REDIS_PASSWORD");
    }

    String resolveTlsCaFile() {
        return resolveString("tls-ca-file", "kc.cache-redis-tls-ca-file", "KC_CACHE_REDIS_TLS_CA_FILE");
    }

    boolean resolveTlsVerifyHostname() {
        String raw = resolveString("tls-verify-hostname", "kc.cache-redis-tls-verify-hostname", "KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME");
        return raw == null || Boolean.parseBoolean(raw);
    }

    private String resolveString(String spiKey, String sysProp, String envVar) {
        String spi = config.get(spiKey);
        if (spi != null && !spi.isBlank()) {
            return spi;
        }
        String sys = System.getProperty(sysProp);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return null;
    }

    /**
     * Fold env / sysprop / SPI overrides into the URL-parsed config:
     *
     * <ul>
     *   <li>Env vars win over URL userinfo for username and password (URLs leak in {@code ps},
     *       heap dumps, error stacks, audit logs; env / secret mounts are the conventional
     *       secrets surface). A single WARN line is emitted when both are set with different
     *       values so the override is auditable.</li>
     *   <li>If any TLS knob is set but the URL scheme is plain {@code redis://}, refuse to
     *       start. Silently ignoring a TLS knob risks shipping a "TLS-enabled" connection
     *       that is actually plaintext.</li>
     * </ul>
     */
    // package-private for unit testing
    RedisConnectionConfig applyResolvedOverrides(RedisConnectionConfig parsed) {
        String envUsername = resolveUsername();
        String envPassword = resolvePassword();
        String tlsCaFile = resolveTlsCaFile();
        boolean tlsVerifyHostname = resolveTlsVerifyHostname();

        // TLS-knob / scheme consistency check. tlsVerifyHostname is true by default; treat the
        // explicit `false` opt-out as a "user clearly set this" signal.
        boolean explicitVerifyOff = !tlsVerifyHostname;
        if ((tlsCaFile != null || explicitVerifyOff) && !parsed.isSslEnabled()) {
            throw new RuntimeException(
                    "KC_CACHE_REDIS_TLS_* options are set but the connection URL scheme is `redis://`, "
                            + "not `rediss://`. Either change the scheme to `rediss://` or unset the TLS options.");
        }

        String effectiveUsername = parsed.getUsername();
        if (envUsername != null) {
            if (parsed.getUsername() != null && !parsed.getUsername().equals(envUsername)) {
                logger.warn("KC_CACHE_REDIS_USERNAME env var overrides the username embedded in KC_CACHE_REDIS_URL");
            }
            effectiveUsername = envUsername;
        }

        String effectivePassword = parsed.getPassword();
        if (envPassword != null) {
            if (parsed.getPassword() != null && !parsed.getPassword().equals(envPassword)) {
                logger.warn("KC_CACHE_REDIS_PASSWORD env var overrides the password embedded in KC_CACHE_REDIS_URL");
            }
            effectivePassword = envPassword;
        }

        return new RedisConnectionConfig.Builder()
                .mode(parsed.getMode())
                .hosts(parsed.getHosts())
                .sentinelMasterId(parsed.getSentinelMasterId())
                .username(effectiveUsername)
                .password(effectivePassword)
                .sslEnabled(parsed.isSslEnabled())
                .tlsCaFile(tlsCaFile)
                .tlsVerifyHostname(tlsVerifyHostname)
                .build();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Register for lifecycle events if needed
    }

    @Override
    public void close() {
        logger.info("Closing Redis connection provider factory");

        runWithWriteLock(() -> {
            if (connectionProvider != null) {
                connectionProvider.close();
                connectionProvider = null;
            }
            if (l1Bus != null) {
                l1Bus.close();
                l1Bus = null;
            }
            if (redissonClient != null) {
                RedissonClientFactory.closeClient(redissonClient);
                redissonClient = null;
            }
            if (clientManager != null) {
                clientManager.close();
                clientManager = null;
            }
        });
    }

    @Override
    public String getId() {
        return "default";
    }

    @Override
    public Map<String, String> getOperationalInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("implementation", "Redis (Lettuce)");

        if (connectionProvider != null) {
            TopologyInfo topology = connectionProvider.getTopologyInfo();
            info.put("nodeName", topology.getMyNodeName());
            if (topology.getMySiteName() != null) {
                info.put("siteName", topology.getMySiteName());
            }
            info.put("healthy", String.valueOf(connectionProvider.isHealthy()));
        }

        return info;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

    /**
     * Run task with read lock to prevent deadlocks during shutdown.
     */
    public static void runWithReadLock(Runnable task) {
        Lock lock = READ_WRITE_LOCK.readLock();
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run task with write lock during shutdown.
     */
    public static void runWithWriteLock(Runnable task) {
        Lock lock = READ_WRITE_LOCK.writeLock();
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
