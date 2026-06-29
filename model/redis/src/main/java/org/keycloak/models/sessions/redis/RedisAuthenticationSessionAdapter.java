package org.keycloak.models.sessions.redis;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.sessions.redis.entities.RedisAuthenticationSessionEntity;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * Adapter for a single authentication session tab, backed by Redis entity.
 */
public class RedisAuthenticationSessionAdapter implements AuthenticationSessionModel {

    private final KeycloakSession session;
    private final RedisRootAuthenticationSessionAdapter parent;
    private final String tabId;
    private final RedisAuthenticationSessionEntity entity;

    public RedisAuthenticationSessionAdapter(KeycloakSession session,
                                              RedisRootAuthenticationSessionAdapter parent,
                                              String tabId,
                                              RedisAuthenticationSessionEntity entity) {
        this.session = session;
        this.parent = parent;
        this.tabId = tabId;
        this.entity = entity;
    }

    @Override
    public String getTabId() {
        return tabId;
    }

    @Override
    public RootAuthenticationSessionModel getParentSession() {
        return parent;
    }

    @Override
    public Map<String, ExecutionStatus> getExecutionStatus() {
        return entity.getExecutionStatus() != null ? entity.getExecutionStatus() : Collections.emptyMap();
    }

    @Override
    public void setExecutionStatus(String authenticator, ExecutionStatus status) {
        if (entity.getExecutionStatus() == null) {
            entity.setExecutionStatus(new HashMap<>());
        }
        entity.getExecutionStatus().put(authenticator, status);
        update();
    }

    @Override
    public void clearExecutionStatus() {
        entity.getExecutionStatus().clear();
        update();
    }

    @Override
    public UserModel getAuthenticatedUser() {
        String authUserId = entity.getAuthUserId();
        return authUserId == null ? null : session.users().getUserById(getRealm(), authUserId);
    }

    @Override
    public void setAuthenticatedUser(UserModel user) {
        entity.setAuthUserId(user == null ? null : user.getId());
        update();
    }

    @Override
    public Set<String> getRequiredActions() {
        return entity.getRequiredActions() != null ? entity.getRequiredActions() : Collections.emptySet();
    }

    @Override
    public void addRequiredAction(String action) {
        if (entity.getRequiredActions() == null) {
            entity.setRequiredActions(new HashSet<>());
        }
        entity.getRequiredActions().add(action);
        update();
    }

    @Override
    public void removeRequiredAction(String action) {
        if (entity.getRequiredActions() != null) {
            entity.getRequiredActions().remove(action);
            update();
        }
    }

    @Override
    public void addRequiredAction(UserModel.RequiredAction action) {
        addRequiredAction(action.name());
    }

    @Override
    public void removeRequiredAction(UserModel.RequiredAction action) {
        removeRequiredAction(action.name());
    }

    @Override
    public void setUserSessionNote(String name, String value) {
        if (name == null || value == null) return;
        if (entity.getUserSessionNotes() == null) {
            entity.setUserSessionNotes(new HashMap<>());
        }
        entity.getUserSessionNotes().put(name, value);
        update();
    }

    @Override
    public Map<String, String> getUserSessionNotes() {
        return entity.getUserSessionNotes() != null ? entity.getUserSessionNotes() : Collections.emptyMap();
    }

    @Override
    public void clearUserSessionNotes() {
        if (entity.getUserSessionNotes() != null) {
            entity.getUserSessionNotes().clear();
            update();
        }
    }

    @Override
    public String getAuthNote(String name) {
        if (name == null) return null;
        return entity.getAuthNotes() != null ? entity.getAuthNotes().get(name) : null;
    }

    @Override
    public void setAuthNote(String name, String value) {
        if (name == null || value == null) return;
        if (entity.getAuthNotes() == null) {
            entity.setAuthNotes(new HashMap<>());
        }
        entity.getAuthNotes().put(name, value);
        update();
    }

    @Override
    public void removeAuthNote(String name) {
        if (name == null || entity.getAuthNotes() == null) return;
        entity.getAuthNotes().remove(name);
        update();
    }

    @Override
    public void clearAuthNotes() {
        if (entity.getAuthNotes() != null) {
            entity.getAuthNotes().clear();
            update();
        }
    }

    @Override
    public String getClientNote(String name) {
        if (name == null) return null;
        return entity.getClientNotes() != null ? entity.getClientNotes().get(name) : null;
    }

    @Override
    public void setClientNote(String name, String value) {
        if (name == null || value == null) return;
        if (entity.getClientNotes() == null) {
            entity.setClientNotes(new HashMap<>());
        }
        entity.getClientNotes().put(name, value);
        update();
    }

    @Override
    public void removeClientNote(String name) {
        if (name == null || entity.getClientNotes() == null) return;
        entity.getClientNotes().remove(name);
        update();
    }

    @Override
    public Map<String, String> getClientNotes() {
        return entity.getClientNotes() != null ? entity.getClientNotes() : Collections.emptyMap();
    }

    @Override
    public void clearClientNotes() {
        if (entity.getClientNotes() != null) {
            entity.getClientNotes().clear();
            update();
        }
    }

    @Override
    public Set<String> getClientScopes() {
        return entity.getClientScopes() != null ? entity.getClientScopes() : Collections.emptySet();
    }

    @Override
    public void setClientScopes(Set<String> clientScopes) {
        entity.setClientScopes(clientScopes);
        update();
    }

    @Override
    public String getRedirectUri() {
        return entity.getRedirectUri();
    }

    @Override
    public void setRedirectUri(String uri) {
        entity.setRedirectUri(uri);
        update();
    }

    @Override
    public RealmModel getRealm() {
        return parent.getRealm();
    }

    @Override
    public ClientModel getClient() {
        return getRealm().getClientById(entity.getClientUUID());
    }

    @Override
    public String getAction() {
        return entity.getAction();
    }

    @Override
    public void setAction(String action) {
        entity.setAction(action);
        update();
    }

    @Override
    public String getProtocol() {
        return entity.getProtocol();
    }

    @Override
    public void setProtocol(String method) {
        entity.setProtocol(method);
        update();
    }

    private void update() {
        parent.onChildUpdated(tabId, entity);
    }
}
