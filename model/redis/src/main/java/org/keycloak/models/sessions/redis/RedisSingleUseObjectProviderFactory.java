package org.keycloak.models.sessions.redis;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cache.redis.RedisCache;
import org.keycloak.common.util.Time;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.SingleUseObjectProviderFactory;
import org.keycloak.models.session.RevokedTokenPersisterProvider;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis-backed SingleUseObjectProvider.
 */
public class RedisSingleUseObjectProviderFactory implements SingleUseObjectProviderFactory<RedisSingleUseObjectProvider>,
                                                             EnvironmentDependentProviderFactory {

    private static final Logger logger = Logger.getLogger(RedisSingleUseObjectProviderFactory.class);

    public static final String CONFIG_PERSIST_REVOKED_TOKENS = "persistRevokedTokens";
    public static final boolean DEFAULT_PERSIST_REVOKED_TOKENS = true;

    /** Sentinel marking that this Redis keyspace already holds the revocations from the database. */
    static final String LOADED = "loaded" + SingleUseObjectProvider.REVOKED_KEY;

    private boolean persistRevokedTokens;

    @Override
    public RedisSingleUseObjectProvider create(KeycloakSession session) {
        return new RedisSingleUseObjectProvider(session, persistRevokedTokens);
    }

    @Override
    public void init(Config.Scope config) {
        persistRevokedTokens = config.getBoolean(CONFIG_PERSIST_REVOKED_TOKENS, DEFAULT_PERSIST_REVOKED_TOKENS);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (!persistRevokedTokens) {
            return;
        }
        // Preload at startup rather than on first request, to keep the congestion off the
        // login path. The sentinel lives in Redis, so a keyspace that still holds the
        // revocations is left alone and a flushed/evicted one is repopulated.
        factory.register(event -> {
            if (event instanceof PostMigrationEvent pme) {
                try (KeycloakSession session = pme.getFactory().create()) {
                    preloadRevokedTokens(session);
                }
            }
        });
    }

    private void preloadRevokedTokens(KeycloakSession session) {
        RedisCache<String, Map<String, String>> cache = session.getProvider(RedisConnectionProvider.class)
                .getCache(RedisConnectionProvider.ACTION_TOKEN_CACHE);

        if (cache.get(RedisSingleUseObjectProvider.cacheKey(LOADED)) != null) {
            return;
        }

        logger.debug("Preloading revoked tokens from database into Redis.");
        int currentTime = Time.currentTime();
        long[] count = {0};
        // Racing nodes may preload concurrently; the writes are idempotent, so that is harmless.
        session.getProvider(RevokedTokenPersisterProvider.class).getAllRevokedTokens().forEach(token -> {
            long lifespanSeconds = token.expiry() - currentTime;
            if (lifespanSeconds > 0) {
                Map<String, String> existing = cache.putIfAbsent(
                        RedisSingleUseObjectProvider.cacheKey(token.tokenId() + SingleUseObjectProvider.REVOKED_KEY),
                        RedisSingleUseObjectProvider.withExpiry(Collections.emptyMap(), token.expiry()),
                        lifespanSeconds, TimeUnit.SECONDS);
                if (existing == null) {
                    count[0]++;
                }
            }
        });
        cache.put(RedisSingleUseObjectProvider.cacheKey(LOADED), Collections.emptyMap());
        logger.debugf("Preloaded %d revoked tokens from database.", count[0]);
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(config.root().get("cache"));
    }

    @Override
    public String getId() {
        return "redis";
    }
}
