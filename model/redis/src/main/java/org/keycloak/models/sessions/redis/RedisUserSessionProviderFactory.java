package org.keycloak.models.sessions.redis;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.UserSessionProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis user session provider.
 *
 * In KC26+, user sessions are persisted to the database (JPA) as the source of truth.
 * This factory creates a provider that delegates all operations to the JPA persister,
 * without an intermediate Infinispan cache layer.
 */
public class RedisUserSessionProviderFactory implements UserSessionProviderFactory<RedisUserSessionProvider>,
                                                         EnvironmentDependentProviderFactory {

    @Override
    public RedisUserSessionProvider create(KeycloakSession session) {
        return new RedisUserSessionProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
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
