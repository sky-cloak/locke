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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the three-tier fallback (SPI scope -> system property -> env var) for the
 * username, password, and TLS resolvers in {@link DefaultRedisConnectionProviderFactory}.
 * Same `--optimized` failure mode the URL resolver guards against: without the env
 * fallback, KC_CACHE_REDIS_* options are silently ignored under `start --optimized`.
 *
 * <p>Env-var assertions go through the system-property tier (they share the same
 * resolveString helper), so we exercise SPI and sysprop paths; the env-var path is
 * the same code with a different read source.</p>
 */
public class RedisOverrideResolutionTest {

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

    private DefaultRedisConnectionProviderFactory factoryWithScope(TestConfigScope scope) {
        DefaultRedisConnectionProviderFactory factory = new DefaultRedisConnectionProviderFactory();
        factory.init(scope);
        return factory;
    }

    @Test
    public void resolveUsername_SpiScopeWins() {
        System.setProperty("kc.cache-redis-username", "from-prop");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(
                TestConfigScope.empty().with("username", "from-spi"));
        assertThat(f.resolveUsername(), is("from-spi"));
    }

    @Test
    public void resolveUsername_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-username", "from-prop");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveUsername(), is("from-prop"));
    }

    @Test
    public void resolveUsername_NullWhenUnset() {
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveUsername(), is(nullValue()));
    }

    @Test
    public void resolvePassword_SpiScopeWins() {
        System.setProperty("kc.cache-redis-password", "from-prop");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(
                TestConfigScope.empty().with("password", "from-spi"));
        assertThat(f.resolvePassword(), is("from-spi"));
    }

    @Test
    public void resolvePassword_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-password", "from-prop");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolvePassword(), is("from-prop"));
    }

    @Test
    public void resolveTlsCaFile_FallsBackToSysProp() {
        System.setProperty("kc.cache-redis-tls-ca-file", "/etc/ssl/ca.crt");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveTlsCaFile(), is("/etc/ssl/ca.crt"));
    }

    @Test
    public void resolveTlsVerifyHostname_DefaultsToTrue() {
        // Secure default: when nothing is set, hostname verification is on.
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveTlsVerifyHostname(), is(true));
    }

    @Test
    public void resolveTlsVerifyHostname_FalseHonoredFromSysProp() {
        System.setProperty("kc.cache-redis-tls-verify-hostname", "false");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveTlsVerifyHostname(), is(false));
    }

    @Test
    public void resolveTlsVerifyHostname_TrueExplicitlyHonored() {
        System.setProperty("kc.cache-redis-tls-verify-hostname", "true");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(TestConfigScope.empty());
        assertThat(f.resolveTlsVerifyHostname(), is(true));
    }

    @Test
    public void resolveTlsVerifyHostname_SpiScopeWins() {
        System.setProperty("kc.cache-redis-tls-verify-hostname", "true");
        DefaultRedisConnectionProviderFactory f = factoryWithScope(
                TestConfigScope.empty().with("tls-verify-hostname", "false"));
        assertThat(f.resolveTlsVerifyHostname(), is(false));
    }
}
