package org.keycloak.models.sessions.redis;

import java.util.Objects;

import org.keycloak.Config;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.sessions.StickySessionEncoderProvider;
import org.keycloak.sessions.StickySessionEncoderProviderFactory;

/**
 * Sticky session encoder for Redis cache mode.
 * Since Redis is an external shared store (all nodes see the same data),
 * there is no topology-aware routing needed. The encoder simply appends
 * the current node's name for basic load balancer affinity.
 */
public class RedisStickySessionEncoderProviderFactory implements StickySessionEncoderProviderFactory,
                                                                  StickySessionEncoderProvider,
                                                                  EnvironmentDependentProviderFactory {

    private static final char SEPARATOR = '.';

    private boolean shouldAttachRoute = true;
    private String myNodeName;

    @Override
    public StickySessionEncoderProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
        this.shouldAttachRoute = config.getBoolean("shouldAttachRoute", true);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        try (KeycloakSession session = factory.create()) {
            RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);
            if (redisProvider != null && redisProvider.getTopologyInfo() != null) {
                this.myNodeName = redisProvider.getTopologyInfo().getMyNodeName();
            }
            if (this.myNodeName == null) {
                this.myNodeName = java.util.UUID.randomUUID().toString().substring(0, 8);
            }
        }
    }

    @Override
    public String encodeSessionId(String message, String sessionId) {
        Objects.requireNonNull(message);
        String route = sessionIdRoute(sessionId);
        return route == null ? message : message + SEPARATOR + route;
    }

    @Override
    public SessionIdAndRoute decodeSessionIdAndRoute(String encodedSessionId) {
        Objects.requireNonNull(encodedSessionId);
        int idx = encodedSessionId.indexOf(SEPARATOR);
        if (idx == -1) {
            return new SessionIdAndRoute(encodedSessionId, null);
        }
        return new SessionIdAndRoute(
                encodedSessionId.substring(0, idx),
                encodedSessionId.substring(idx + 1)
        );
    }

    @Override
    public boolean shouldAttachRoute() {
        return shouldAttachRoute;
    }

    @Override
    public String sessionIdRoute(String sessionId) {
        return shouldAttachRoute ? myNodeName : null;
    }

    @Override
    public void setShouldAttachRoute(boolean shouldAttachRoute) {
        this.shouldAttachRoute = shouldAttachRoute;
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
    public void close() {
    }
}
