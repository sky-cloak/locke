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

package org.keycloak.connections.redis;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The cache adapters obtain commands through {@code RedisClientManager.sync/async}, typed to
 * {@code RedisClusterCommands}/{@code RedisClusterAsyncCommands} so the same code path works for
 * standalone, sentinel, and cluster connections. That only holds because Lettuce makes those the
 * common supertype of both the standalone and the cluster command interfaces. A Lettuce upgrade
 * that broke this would otherwise resurface as a ClassCastException on the first cluster write.
 */
public class RedisClusterCommandTypeContractTest {

    @Test
    public void clusterCommandsIsCommonSyncSupertype() {
        assertTrue(RedisClusterCommands.class.isAssignableFrom(RedisCommands.class));
        assertTrue(RedisClusterCommands.class.isAssignableFrom(RedisAdvancedClusterCommands.class));
    }

    @Test
    public void clusterAsyncCommandsIsCommonAsyncSupertype() {
        assertTrue(RedisClusterAsyncCommands.class.isAssignableFrom(RedisAsyncCommands.class));
        assertTrue(RedisClusterAsyncCommands.class.isAssignableFrom(RedisAdvancedClusterAsyncCommands.class));
    }
}
