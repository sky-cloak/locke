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

import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies that when both URL userinfo and an env / system-property override are present,
 * the override wins. URLs leak in {@code ps} output, heap dumps, error stacks, and audit
 * logs; env vars / secret mounts are the conventional secrets surface.
 */
public class RedisAuthPrecedenceTest {

    private static final String[] SYS_PROPS = {
            "kc.cache-redis-username",
            "kc.cache-redis-password",
            "kc.cache-redis-tls-ca-file",
            "kc.cache-redis-tls-verify-hostname",
    };

    @After
    public void clearSysProps() {
        for (String p : SYS_PROPS) {
            System.clearProperty(p);
        }
    }

    private DefaultRedisConnectionProviderFactory factory() {
        DefaultRedisConnectionProviderFactory f = new DefaultRedisConnectionProviderFactory();
        f.init(TestConfigScope.empty());
        return f;
    }

    @Test
    public void sysPropPasswordOverridesUrlUserinfo() {
        System.setProperty("kc.cache-redis-password", "from-prop");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://alice:from-url@host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.getUsername(), is("alice"));
        assertThat(effective.getPassword(), is("from-prop"));
    }

    @Test
    public void sysPropUsernameOverridesUrlUserinfo() {
        System.setProperty("kc.cache-redis-username", "bob");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://alice:secret@host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.getUsername(), is("bob"));
        assertThat(effective.getPassword(), is("secret"));
    }

    @Test
    public void urlValuesPreservedWhenNoOverride() {
        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://alice:secret@host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.getUsername(), is("alice"));
        assertThat(effective.getPassword(), is("secret"));
    }

    @Test
    public void sysPropAppliesEvenWithoutUrlUserinfo() {
        System.setProperty("kc.cache-redis-username", "alice");
        System.setProperty("kc.cache-redis-password", "secret");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.getUsername(), is("alice"));
        assertThat(effective.getPassword(), is("secret"));
    }

    @Test
    public void noAuthAtAll_RemainsNull() {
        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.getUsername(), is(nullValue()));
        assertThat(effective.getPassword(), is(nullValue()));
    }

    @Test
    public void warnsWhenSysPropOverridesUrl_AndNeverLogsThePasswordItself() {
        System.setProperty("kc.cache-redis-password", "env-secret");

        try (RedisLogCapture capture = RedisLogCapture.start()) {
            RedisConnectionConfig parsed = RedisConnectionConfig.parse(
                    "redis://alice:url-secret@host:6379");
            factory().applyResolvedOverrides(parsed);

            String logs = capture.allMessages();
            // Override is auditable: a single WARN line names the env var by name only.
            assertThat(logs, containsString("KC_CACHE_REDIS_PASSWORD"));
            assertThat(logs, containsString("overrides"));
            // The redaction contract: no log line may contain the secret value itself.
            assertThat(logs, not(containsString("env-secret")));
            assertThat(logs, not(containsString("url-secret")));
        }
    }
}
