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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression guard for the GETDEL compatibility fix (docs/adr/0003 / #40):
 * {@code LettuceCacheAdapter.remove} must route single-use get-and-delete through the
 * Lua {@code GET}+{@code DEL} script (so it runs on Redis 6.0 — classic Azure Cache for
 * Redis) and NOT through the native {@code GETDEL} command (Redis 6.2+).
 *
 * <p>The adapter is built with a <b>null</b> {@code RedisClientManager} on purpose: the
 * native-{@code GETDEL} path goes through {@code withConnection(clientManager...)} and would
 * NPE. So if {@code remove} returns a value here, it provably took the injected-{@code LuaScripts}
 * branch. If a future change reverts to native {@code GETDEL}, this test fails (NPE), and the
 * cross-version {@code redis:6.0} integration test fails for real.
 */
public class LettuceCacheAdapterGetDelTest {

    /** Records the call and returns a canned value, without touching a real Redis. */
    private static final class RecordingLuaScripts extends LuaScripts {
        boolean getDelCalled;
        byte[] lastKey;
        byte[] toReturn;

        RecordingLuaScripts() { super(null); }

        @Override
        public byte[] getDel(byte[] key) {
            getDelCalled = true;
            lastKey = key;
            return toReturn;
        }
    }

    private static byte[] javaSerialize(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(o);
        }
        return baos.toByteArray();
    }

    @Test
    public void remove_routesThroughLuaGetDel_andDeserializesValue() throws Exception {
        RecordingLuaScripts lua = new RecordingLuaScripts();
        // null clientManager: native GETDEL path would NPE, so success proves the Lua branch.
        LettuceCacheAdapter<String, String> adapter =
                new LettuceCacheAdapter<>("test", null, null, lua);

        lua.toReturn = javaSerialize("hello");

        String result = adapter.remove("k");

        assertThat("value came back through the Lua get-and-delete path", result, is("hello"));
        assertThat("remove() invoked LuaScripts.getDel (not native cmd.getdel)", lua.getDelCalled, is(true));
        assertThat(lua.lastKey, is(notNullValue()));

        // The key handed to getDel is the adapter's prefixed key: "test:" + serialized("k").
        byte[] expectedPrefix = "test:".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat("getDel was given the prefixed redis key",
                Arrays.copyOf(lua.lastKey, expectedPrefix.length), is(expectedPrefix));
    }

    @Test
    public void remove_returnsNull_whenKeyAbsent() {
        RecordingLuaScripts lua = new RecordingLuaScripts();
        LettuceCacheAdapter<String, String> adapter =
                new LettuceCacheAdapter<>("test", null, null, lua);

        lua.toReturn = null; // GET on a missing key

        assertThat(adapter.remove("k"), is(nullValue()));
        assertThat(lua.getDelCalled, is(true));
    }
}
