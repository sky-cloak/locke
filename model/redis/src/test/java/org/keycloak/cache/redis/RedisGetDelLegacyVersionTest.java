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

package org.keycloak.cache.redis;

import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.connections.redis.RedisClientManager;
import org.keycloak.connections.redis.RedisConnectionConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

/**
 * Cross-version proof for the GETDEL compatibility fix (docs/adr/0003 / #40).
 *
 * <p>Runs against a real <b>Redis 6.0</b> server — the version classic Azure Cache for Redis
 * runs, and which predates {@code GETDEL} (added in 6.2). The control test confirms native
 * {@code GETDEL} is genuinely unavailable on 6.0; the main test shows
 * {@code LettuceCacheAdapter.remove} (atomic get-and-delete via the Lua {@code GET}+{@code DEL}
 * script) works there anyway.
 *
 * <p>Excluded from the default build (needs Docker) — see {@code model/redis/pom.xml}. Run with
 * Docker available: {@code ./mvnw -pl model/redis test -Dtest=RedisGetDelLegacyVersionTest}.
 */
public class RedisGetDelLegacyVersionTest {

    private static final byte[] PROBE_KEY = "getdel:probe".getBytes(StandardCharsets.UTF_8);

    private static GenericContainer<?> redis;
    private static RedisClientManager clientManager;
    private static LuaScripts luaScripts;

    @BeforeClass
    public static void up() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:6.0")).withExposedPorts(6379);
        redis.start();
        String uri = String.format("redis://%s:%d", redis.getHost(), redis.getMappedPort(6379));
        clientManager = new RedisClientManager(RedisConnectionConfig.parse(uri));
        clientManager.init();
        luaScripts = new LuaScripts(clientManager);
        luaScripts.loadAll();
    }

    @AfterClass
    public static void down() {
        if (clientManager != null) clientManager.close();
        if (redis != null) redis.stop();
    }

    /** Control: Redis 6.0 really does reject native GETDEL — the condition the fix addresses. */
    @Test
    public void nativeGetdel_isUnsupportedOnRedis60() {
        Object conn = clientManager.getConnection();
        try {
            RedisClusterCommands<byte[], byte[]> cmd = clientManager.sync(conn);
            cmd.set(PROBE_KEY, "v".getBytes(StandardCharsets.UTF_8));
            Exception ex = assertThrows(Exception.class, () -> cmd.getdel(PROBE_KEY));
            assertThat(ex.getMessage().toLowerCase(), containsString("unknown command"));
        } finally {
            clientManager.returnConnection(conn);
        }
    }

    /** The fix: single-use get-and-delete works on Redis 6.0 via the Lua GET+DEL script. */
    @Test
    public void adapterRemove_worksViaLua_onRedis60() {
        LettuceCacheAdapter<String, String> adapter =
                new LettuceCacheAdapter<>("singleUse", clientManager, null, luaScripts);

        adapter.put("token", "secret", 60, TimeUnit.SECONDS);

        String removed = adapter.remove("token");      // atomic get-and-delete via Lua
        assertThat("remove returns the consumed value", removed, is("secret"));
        assertThat("key is deleted after remove", adapter.containsKey("token"), is(false));
        assertThat("second remove finds nothing", adapter.remove("token"), is(nullValue()));
    }
}
