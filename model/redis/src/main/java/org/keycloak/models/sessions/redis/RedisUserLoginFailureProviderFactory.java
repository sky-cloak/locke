package org.keycloak.models.sessions.redis;

import org.keycloak.Config;
import org.keycloak.common.Profile;
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

    /**
     * In-memory login failures are the {@code login-failures:v1} feature, as for upstream's
     * Infinispan provider. With the default {@code v2}, login failures persist in the database
     * through the JPA provider in every cache mode.
     */
    @Override
    public boolean isSupported(Config.Scope config) {
        return Profile.isFeatureEnabled(Profile.Feature.LOGIN_FAILURES_V1) && "redis".equals(config.root().get("cache"));
    }

    @Override
    public String getId() {
        return "redis";
    }
}
