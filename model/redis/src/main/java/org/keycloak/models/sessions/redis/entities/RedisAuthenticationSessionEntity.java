package org.keycloak.models.sessions.redis.entities;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Serializable entity for a single authentication session (one browser tab).
 */
public class RedisAuthenticationSessionEntity implements Serializable {

    private String clientUUID;
    private String authUserId;
    private int timestamp;
    private String redirectUri;
    private String action;
    private Set<String> clientScopes = new HashSet<>();
    private Map<String, AuthenticationSessionModel.ExecutionStatus> executionStatus = new ConcurrentHashMap<>();
    private String protocol;
    private Map<String, String> clientNotes = new ConcurrentHashMap<>();
    private Map<String, String> authNotes = new ConcurrentHashMap<>();
    private Set<String> requiredActions = ConcurrentHashMap.newKeySet();
    private Map<String, String> userSessionNotes = new ConcurrentHashMap<>();

    public String getClientUUID() { return clientUUID; }
    public void setClientUUID(String clientUUID) { this.clientUUID = clientUUID; }

    public String getAuthUserId() { return authUserId; }
    public void setAuthUserId(String authUserId) { this.authUserId = authUserId; }

    public int getTimestamp() { return timestamp; }
    public void setTimestamp(int timestamp) { this.timestamp = timestamp; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Set<String> getClientScopes() { return clientScopes; }
    public void setClientScopes(Set<String> clientScopes) { this.clientScopes = clientScopes; }

    public Map<String, AuthenticationSessionModel.ExecutionStatus> getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(Map<String, AuthenticationSessionModel.ExecutionStatus> executionStatus) { this.executionStatus = executionStatus; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public Map<String, String> getClientNotes() { return clientNotes; }
    public void setClientNotes(Map<String, String> clientNotes) { this.clientNotes = clientNotes; }

    public Map<String, String> getAuthNotes() { return authNotes; }
    public void setAuthNotes(Map<String, String> authNotes) { this.authNotes = authNotes; }

    public Set<String> getRequiredActions() { return requiredActions; }
    public void setRequiredActions(Set<String> requiredActions) { this.requiredActions = requiredActions; }

    public Map<String, String> getUserSessionNotes() { return userSessionNotes; }
    public void setUserSessionNotes(Map<String, String> userSessionNotes) { this.userSessionNotes = userSessionNotes; }
}
