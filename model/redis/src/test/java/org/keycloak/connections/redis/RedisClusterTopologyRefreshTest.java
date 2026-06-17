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

import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.junit.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/** Pins the cluster topology-refresh config so a regression that silently drops it is caught. */
public class RedisClusterTopologyRefreshTest {

    @Test
    public void topologyRefreshIsConfiguredForResilience() {
        ClusterTopologyRefreshOptions opts = RedisClientManager.buildClusterTopologyRefreshOptions();

        assertThat("periodic refresh must be on so slot moves are picked up",
                opts.isPeriodicRefreshEnabled(), is(true));
        assertThat(opts.getRefreshPeriod(), is(Duration.ofSeconds(30)));

        assertThat("adaptive triggers (MOVED/ASK/reconnect/uncovered-slot) must fire refresh on failover",
                opts.getAdaptiveRefreshTriggers().size(), is(greaterThan(0)));

        assertThat("dynamic refresh sources are required to discover nodes from the live topology (ElastiCache)",
                opts.useDynamicRefreshSources(), is(true));
    }
}
