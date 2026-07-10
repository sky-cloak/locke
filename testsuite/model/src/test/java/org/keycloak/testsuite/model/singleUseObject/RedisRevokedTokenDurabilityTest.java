/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.model.singleUseObject;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.testsuite.model.KeycloakModelTest;
import org.keycloak.testsuite.model.RequireProvider;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Token revocation (RFC 7009) must outlive the Redis keyspace.
 *
 * <p>Upstream's {@code SingleUseObjectModelTest#testRevokedTokenIsPresentAfterRestartAndEventuallyExpires}
 * cannot cover this for Locke: it simulates a restart with
 * {@code reinitializeKeycloakSessionFactory()}, which empties an <em>in-process</em> Infinispan
 * cache. Redis is external, so the entry survives that restart and the assertion passes whether or
 * not the revocation was ever written to the database.
 *
 * <p>The failure this guards is losing the keyspace while Keycloak keeps running or restarts
 * against an emptied Redis: eviction under {@code maxmemory-policy}, a failover that drops an
 * unreplicated write, or a cache tier with no persistence. Without a durable record, a revoked
 * refresh token silently becomes valid again until its natural expiry.
 */
@RequireProvider(RedisConnectionProvider.class)
@RequireProvider(RevokedTokenProvider.class)
public class RedisRevokedTokenDurabilityTest extends KeycloakModelTest {

    @Test
    public void revokedTokenSurvivesRedisKeyspaceLoss() {
        String tokenId = UUID.randomUUID().toString();

        inComittedTransaction(session -> {
            session.revokedTokens().put(tokenId, 60);
        });
        inComittedTransaction(session -> {
            assertThat("revocation should be visible once written",
                    session.revokedTokens().contains(tokenId), Matchers.is(true));
        });

        // Wipe Redis out from under the running server.
        inComittedTransaction(session -> {
            session.getProvider(RedisConnectionProvider.class)
                    .getCache(RedisConnectionProvider.ACTION_TOKEN_CACHE)
                    .clear();
        });
        inComittedTransaction(session -> {
            assertThat("keyspace should actually be empty now",
                    session.revokedTokens().contains(tokenId), Matchers.is(false));
        });

        // Restart: the factory repopulates Redis from the database because the sentinel is gone.
        reinitializeKeycloakSessionFactory();

        inComittedTransaction(session -> {
            assertThat("revocation must be restored from the database",
                    session.revokedTokens().contains(tokenId), Matchers.is(true));
        });
    }
}
