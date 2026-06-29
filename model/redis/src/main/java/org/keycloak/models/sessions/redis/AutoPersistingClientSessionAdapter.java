package org.keycloak.models.sessions.redis;

import java.util.Map;

import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.session.UserSessionPersisterProvider;

/**
 * Wrapper around an AuthenticatedClientSessionModel that auto-persists changes
 * back to the JPA store when mutable methods are called.
 *
 * The PersistentAuthenticatedClientSessionAdapter modifies notes in-memory but
 * doesn't flush changes to the JPA entity's data field. This wrapper detects
 * mutations and re-persists the client session so changes are visible on reload.
 */
class AutoPersistingClientSessionAdapter implements AuthenticatedClientSessionModel {

    private final AuthenticatedClientSessionModel delegate;
    private final UserSessionPersisterProvider persister;
    private final boolean offline;

    AutoPersistingClientSessionAdapter(AuthenticatedClientSessionModel delegate,
                                       UserSessionPersisterProvider persister,
                                       boolean offline) {
        this.delegate = delegate;
        this.persister = persister;
        this.offline = offline;
    }

    private void persist() {
        persister.createClientSession(delegate, offline);
    }

    @Override
    public String getId() { return delegate.getId(); }

    @Override
    public int getTimestamp() { return delegate.getTimestamp(); }

    @Override
    public void setTimestamp(int timestamp) {
        delegate.setTimestamp(timestamp);
        persist();
    }

    @Override
    public void detachFromUserSession() { delegate.detachFromUserSession(); }

    @Override
    public UserSessionModel getUserSession() { return delegate.getUserSession(); }

    @Override
    public String getRedirectUri() { return delegate.getRedirectUri(); }

    @Override
    public void setRedirectUri(String uri) {
        delegate.setRedirectUri(uri);
        persist();
    }

    @Override
    public RealmModel getRealm() { return delegate.getRealm(); }

    @Override
    public ClientModel getClient() { return delegate.getClient(); }

    @Override
    public String getAction() { return delegate.getAction(); }

    @Override
    public void setAction(String action) {
        delegate.setAction(action);
        persist();
    }

    @Override
    public String getProtocol() { return delegate.getProtocol(); }

    @Override
    public void setProtocol(String method) {
        delegate.setProtocol(method);
        persist();
    }

    @Override
    public String getNote(String name) { return delegate.getNote(name); }

    @Override
    public void setNote(String name, String value) {
        delegate.setNote(name, value);
        persist();
    }

    @Override
    public void removeNote(String name) {
        delegate.removeNote(name);
        persist();
    }

    @Override
    public Map<String, String> getNotes() { return delegate.getNotes(); }
}
