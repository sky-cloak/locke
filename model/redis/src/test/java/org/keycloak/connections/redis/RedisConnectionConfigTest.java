/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.connections.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;

/**
 * Unit tests for RedisConnectionConfig.
 * Tests URI parsing and configuration building for different Redis deployment modes.
 *
 * @author Keycloak Redis Team
 */
public class RedisConnectionConfigTest {

    @Test
    public void testParseStandaloneUri_Success() {
        // Given
        String uri = "redis://localhost:6379";

        // When
        RedisConnectionConfig config = RedisConnectionConfig.parse(uri);

        // Then
        assertThat(config, notNullValue());
        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.STANDALONE));
        assertThat(config.getHosts().size(), equalTo(1));
        assertThat(config.getHosts().get(0).getHost(), equalTo("localhost"));
        assertThat(config.getHosts().get(0).getPort(), equalTo(6379));
    }

    @Test
    public void testParseSentinelUri_WithMasterId() {
        // Given
        String uri = "redis-sentinel://host1:26379,host2:26379?sentinelMasterId=mymaster";

        // When
        RedisConnectionConfig config = RedisConnectionConfig.parse(uri);

        // Then
        assertThat(config, notNullValue());
        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.SENTINEL));
        assertThat(config.getSentinelMasterId(), equalTo("mymaster"));
        assertThat(config.getHosts().size(), equalTo(2));
        assertThat(config.getHosts().get(0).getHost(), equalTo("host1"));
        assertThat(config.getHosts().get(0).getPort(), equalTo(26379));
        assertThat(config.getHosts().get(1).getHost(), equalTo("host2"));
        assertThat(config.getHosts().get(1).getPort(), equalTo(26379));
    }

    @Test
    public void testParseClusterUri_MultipleHosts() {
        // Given
        String uri = "redis-cluster://node1:6379,node2:6379,node3:6379";

        // When
        RedisConnectionConfig config = RedisConnectionConfig.parse(uri);

        // Then
        assertThat(config, notNullValue());
        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.CLUSTER));
        assertThat(config.getHosts().size(), equalTo(3));
        assertThat(config.getHosts().get(0).getHost(), equalTo("node1"));
        assertThat(config.getHosts().get(1).getHost(), equalTo("node2"));
        assertThat(config.getHosts().get(2).getHost(), equalTo("node3"));
    }

    @Test
    public void testParseUri_WithPassword() {
        // Given
        String uri = "redis://user:password@localhost:6379";

        // When
        RedisConnectionConfig config = RedisConnectionConfig.parse(uri);

        // Then
        assertThat(config, notNullValue());
        assertThat(config.getPassword(), equalTo("password"));
    }

    @Test
    public void testBuilder_DefaultValues() {
        // Given/When
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .addHost("localhost", 6379)
                .build();

        // Then
        assertThat(config, notNullValue());
        // Defaults bumped from 5/20 -> 16/64 to match production sizing
        // (perf-tuned away from Lettuce defaults that thrashed at >20 concurrent VUs).
        assertThat(config.getPoolMinSize(), equalTo(16));
        assertThat(config.getPoolMaxSize(), equalTo(64));
        assertThat(config.getRetryAttempts(), equalTo(3));
        assertThat(config.getTimeout().toMillis(), equalTo(2000L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidUri_ThrowsException() {
        // Given/When/Then - should throw IllegalArgumentException
        RedisConnectionConfig.parse("invalid-uri-without-scheme");
    }

    @Test
    public void testParseRedissStandalone_EnablesSsl() {
        RedisConnectionConfig config = RedisConnectionConfig.parse("rediss://example.com:6380");

        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.STANDALONE));
        assertThat(config.isSslEnabled(), is(true));
        assertThat(config.isTlsVerifyHostname(), is(true));
    }

    @Test
    public void testParseRedissSentinel_EnablesSsl() {
        RedisConnectionConfig config = RedisConnectionConfig.parse(
                "rediss-sentinel://s1:26379,s2:26379?sentinelMasterId=mymaster");

        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.SENTINEL));
        assertThat(config.isSslEnabled(), is(true));
        assertThat(config.getSentinelMasterId(), equalTo("mymaster"));
    }

    @Test
    public void testParseRedissCluster_EnablesSsl() {
        RedisConnectionConfig config = RedisConnectionConfig.parse(
                "rediss-cluster://n1:6379,n2:6379,n3:6379");

        assertThat(config.getMode(), equalTo(RedisConnectionConfig.Mode.CLUSTER));
        assertThat(config.isSslEnabled(), is(true));
        assertThat(config.getHosts().size(), equalTo(3));
    }

    @Test
    public void testParseUserAndPassword_BothExtracted() {
        RedisConnectionConfig config = RedisConnectionConfig.parse(
                "rediss://alice:secret@example.com:6380");

        assertThat(config.getUsername(), equalTo("alice"));
        assertThat(config.getPassword(), equalTo("secret"));
        assertThat(config.isSslEnabled(), is(true));
    }

    @Test
    public void testParseUsernameOnly_NoColon() {
        // No colon in userinfo means just a username, no password segment.
        RedisConnectionConfig config = RedisConnectionConfig.parse("redis://alice@example.com:6379");

        assertThat(config.getUsername(), equalTo("alice"));
        assertThat(config.getPassword(), nullValue());
    }

    @Test
    public void testParseLegacyAuth_ColonOnlyPassword() {
        // The legacy AUTH-only Redis form is `redis://:password@host`. No username.
        RedisConnectionConfig config = RedisConnectionConfig.parse("redis://:secret@example.com:6379");

        assertThat(config.getUsername(), nullValue());
        assertThat(config.getPassword(), equalTo("secret"));
    }

    @Test
    public void testToString_RedactsPassword() {
        RedisConnectionConfig config = RedisConnectionConfig.parse("redis://alice:supersekret@example.com:6379");

        String rendered = config.toString();
        assertThat(rendered, containsString("password=***"));
        assertThat(rendered, not(containsString("supersekret")));
        // Username is not a secret and may appear in logs.
        assertThat(rendered, containsString("username=alice"));
    }

    @Test
    public void testToRedactedString_RedactsPassword() {
        RedisConnectionConfig config = RedisConnectionConfig.parse("redis://alice:supersekret@example.com:6379");

        String rendered = config.toRedactedString();
        assertThat(rendered, containsString("password=***"));
        assertThat(rendered, not(containsString("supersekret")));
    }

    @Test
    public void testBuilder_TlsDefaults() {
        // sslEnabled defaults to false; tlsVerifyHostname defaults to true (secure default).
        RedisConnectionConfig config = new RedisConnectionConfig.Builder()
                .addHost("localhost", 6379)
                .build();

        assertThat(config.isSslEnabled(), is(false));
        assertThat(config.isTlsVerifyHostname(), is(true));
        assertThat(config.getTlsCaFile(), nullValue());
    }
}
