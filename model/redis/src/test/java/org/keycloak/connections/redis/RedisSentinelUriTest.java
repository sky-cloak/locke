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

import io.lettuce.core.RedisURI;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Regression: a sentinel RedisURI keeps its endpoints in a sentinels list, so building one and
 * reading getHost() back off it yields an empty host ("Host must not be empty" at connect time).
 * The URI must carry every configured sentinel plus the master id.
 */
public class RedisSentinelUriTest {

    @Test
    public void buildsUriWithAllSentinelsAndMasterId() {
        RedisConnectionConfig config = RedisConnectionConfig.parse(
                "redis-sentinel://s1:26379,s2:26379,s3:26379?sentinelMasterId=mymaster");

        RedisURI uri = new RedisClientManager(config).buildSentinelUri();

        assertThat(uri.getSentinels(), hasSize(3));
        assertThat(uri.getSentinelMasterId(), is("mymaster"));
    }
}
