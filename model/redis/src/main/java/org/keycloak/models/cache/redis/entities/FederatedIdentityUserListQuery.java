package org.keycloak.models.cache.redis.entities;

import org.keycloak.models.RealmModel;

public class FederatedIdentityUserListQuery extends UserListQuery implements InIdentityProvider {

    private final String identityProvider;

    public FederatedIdentityUserListQuery(Long revisioned, String id, RealmModel realm, String userId, String identityProvider) {
        super(revisioned, id, realm, userId);
        this.identityProvider = identityProvider;
    }

    @Override
    public boolean contains(String providerId) {
        return identityProvider != null && identityProvider.equals(providerId);
    }
}
