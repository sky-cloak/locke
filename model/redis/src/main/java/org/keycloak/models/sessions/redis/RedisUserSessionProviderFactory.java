package org.keycloak.models.sessions.redis;

import java.util.UUID;

import org.keycloak.Config;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.UserSessionProviderFactory;
import org.keycloak.models.sessions.infinispan.expiration.ExpirationTask;
import org.keycloak.models.sessions.infinispan.expiration.ExpirationTaskHolder;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis user session provider.
 *
 * In KC26+, user sessions are persisted to the database (JPA) as the source of truth.
 * This factory creates a provider that delegates all operations to the JPA persister,
 * without an intermediate Infinispan cache layer.
 */
public class RedisUserSessionProviderFactory implements UserSessionProviderFactory<RedisUserSessionProvider>,
                                                         EnvironmentDependentProviderFactory,
                                                         ExpirationTaskHolder {

    // Same key and default as upstream's user-session SPI config.
    private static final String CONFIG_EXPIRATION_PERIOD = "sessionExpirationPeriod";
    private static final int DEFAULT_EXPIRATION_PERIOD_SECONDS = 180;

    private final String nodeId = UUID.randomUUID().toString();
    private int expirationPeriodSeconds = DEFAULT_EXPIRATION_PERIOD_SECONDS;
    private volatile ExpirationTask expirationTask;

    @Override
    public RedisUserSessionProvider create(KeycloakSession session) {
        return new RedisUserSessionProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        expirationPeriodSeconds = config.getInt(CONFIG_EXPIRATION_PERIOD, DEFAULT_EXPIRATION_PERIOD_SECONDS);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof UserModel.UserRemovedEvent userRemovedEvent) {
                UserSessionProvider provider = userRemovedEvent.getKeycloakSession().getProvider(UserSessionProvider.class, getId());
                if (provider != null) {
                    provider.removeUserSessions(userRemovedEvent.getRealm(), userRemovedEvent.getUser());
                }
            }
        });

        // Periodic purge of expired sessions from the database — same duty as upstream's
        // expiration task, distributed across nodes via a per-realm Redis lease.
        try (KeycloakSession session = factory.create()) {
            RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
            expirationTask = new RedisExpirationTask(factory, redis.getScheduledExecutorService(),
                    expirationPeriodSeconds, null,
                    redis.getCache(RedisConnectionProvider.WORK_CACHE_NAME), nodeId);
        }
        expirationTask.start();
    }

    @Override
    public void close() {
        if (expirationTask != null) {
            expirationTask.stop();
            expirationTask = null;
        }
    }

    @Override
    public ExpirationTask getExpirationTask() {
        return expirationTask;
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
