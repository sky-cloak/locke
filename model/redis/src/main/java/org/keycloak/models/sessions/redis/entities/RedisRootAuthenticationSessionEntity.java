package org.keycloak.models.sessions.redis.entities;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serializable entity for a root authentication session (one browser, potentially multiple tabs).
 */
public class RedisRootAuthenticationSessionEntity implements Serializable {

    private String id;
    private String realmId;
    private int timestamp;
    private Map<String, RedisAuthenticationSessionEntity> authenticationSessions = new ConcurrentHashMap<>();

    public RedisRootAuthenticationSessionEntity() {
    }

    public RedisRootAuthenticationSessionEntity(String id, String realmId, int timestamp) {
        this.id = id;
        this.realmId = realmId;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRealmId() { return realmId; }
    public void setRealmId(String realmId) { this.realmId = realmId; }

    public int getTimestamp() { return timestamp; }
    public void setTimestamp(int timestamp) { this.timestamp = timestamp; }

    public Map<String, RedisAuthenticationSessionEntity> getAuthenticationSessions() { return authenticationSessions; }
    public void setAuthenticationSessions(Map<String, RedisAuthenticationSessionEntity> authenticationSessions) {
        this.authenticationSessions = authenticationSessions;
    }
}
