package org.keycloak.models.sessions.redis;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.AuthenticationSessionProviderFactory;

/**
 * Factory for Redis-backed AuthenticationSessionProvider.
 */
public class RedisAuthenticationSessionProviderFactory implements AuthenticationSessionProviderFactory<RedisAuthenticationSessionProvider>,
                                                                   EnvironmentDependentProviderFactory {

    private static final String CONFIG_AUTH_SESSIONS_LIMIT = "authSessionsLimit";
    private static final int DEFAULT_AUTH_SESSIONS_LIMIT = 300;

    private int authSessionsLimit;

    @Override
    public RedisAuthenticationSessionProvider create(KeycloakSession session) {
        return new RedisAuthenticationSessionProvider(session, authSessionsLimit);
    }

    @Override
    public void init(Config.Scope config) {
        authSessionsLimit = config.getInt(CONFIG_AUTH_SESSIONS_LIMIT, DEFAULT_AUTH_SESSIONS_LIMIT);
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

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(CONFIG_AUTH_SESSIONS_LIMIT)
                .type("int")
                .helpText("The maximum number of concurrent authentication sessions per root session.")
                .defaultValue(DEFAULT_AUTH_SESSIONS_LIMIT)
                .add()
                .build();
    }
}
