package org.keycloak.models.sessions.redis;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserLoginFailureProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis-backed UserLoginFailureProvider.
 */
public class RedisUserLoginFailureProviderFactory implements UserLoginFailureProviderFactory<RedisUserLoginFailureProvider>,
                                                              EnvironmentDependentProviderFactory {

    @Override
    public RedisUserLoginFailureProvider create(KeycloakSession session) {
        return new RedisUserLoginFailureProvider(session);
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
