package org.keycloak.models.sessions.redis;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.SingleUseObjectProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis-backed SingleUseObjectProvider.
 */
public class RedisSingleUseObjectProviderFactory implements SingleUseObjectProviderFactory<RedisSingleUseObjectProvider>,
                                                             EnvironmentDependentProviderFactory {

    @Override
    public RedisSingleUseObjectProvider create(KeycloakSession session) {
        return new RedisSingleUseObjectProvider(session);
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
