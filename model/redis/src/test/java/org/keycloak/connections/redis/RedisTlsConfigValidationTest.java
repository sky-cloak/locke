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
import static org.junit.Assert.assertThrows;

/**
 * Refuses to start when a TLS knob is set but the URL scheme is plain {@code redis://}.
 * Silently ignoring a TLS option in a security-sensitive feature is worse than failing
 * loudly: an operator who set {@code KC_CACHE_REDIS_TLS_CA_FILE} may genuinely believe
 * the connection is encrypted when it isn't.
 */
public class RedisTlsConfigValidationTest {

    private static final String[] SYS_PROPS = {
            "kc.cache-redis-tls-ca-file",
            "kc.cache-redis-tls-verify-hostname",
            "kc.cache-redis-username",
            "kc.cache-redis-password",
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
    public void plainRedisWithTlsCaFile_FailsLoudly() {
        System.setProperty("kc.cache-redis-tls-ca-file", "/etc/ssl/ca.crt");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://host:6379");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> factory().applyResolvedOverrides(parsed));

        assertThat(ex.getMessage(), containsString("KC_CACHE_REDIS_TLS_*"));
        assertThat(ex.getMessage(), containsString("redis://"));
        assertThat(ex.getMessage(), containsString("rediss://"));
    }

    @Test
    public void plainRedisWithVerifyHostnameFalse_FailsLoudly() {
        System.setProperty("kc.cache-redis-tls-verify-hostname", "false");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://host:6379");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> factory().applyResolvedOverrides(parsed));

        assertThat(ex.getMessage(), containsString("KC_CACHE_REDIS_TLS_*"));
    }

    @Test
    public void redissWithTlsCaFile_Succeeds() {
        System.setProperty("kc.cache-redis-tls-ca-file", "/etc/ssl/ca.crt");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("rediss://host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.isSslEnabled(), is(true));
        assertThat(effective.getTlsCaFile(), is("/etc/ssl/ca.crt"));
    }

    @Test
    public void redissWithVerifyHostnameFalse_Succeeds() {
        System.setProperty("kc.cache-redis-tls-verify-hostname", "false");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("rediss://host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.isSslEnabled(), is(true));
        assertThat(effective.isTlsVerifyHostname(), is(false));
    }

    @Test
    public void plainRedisWithJustAuth_IsAllowed() {
        // AUTH without TLS is allowed by Redis, even if not recommended. We don't refuse it;
        // the user opted into plaintext explicitly by picking the `redis://` scheme.
        System.setProperty("kc.cache-redis-password", "secret");

        RedisConnectionConfig parsed = RedisConnectionConfig.parse("redis://host:6379");
        RedisConnectionConfig effective = factory().applyResolvedOverrides(parsed);

        assertThat(effective.isSslEnabled(), is(false));
        assertThat(effective.getPassword(), is("secret"));
    }
}
