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

import org.keycloak.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal in-memory {@link Config.Scope} for unit tests. Holds whatever keys the
 * test sets; everything else returns null / default. Use {@link #with(String, String)}
 * to chain entries.
 */
final class TestConfigScope implements Config.Scope {

    private final Map<String, String> values = new HashMap<>();

    static TestConfigScope empty() {
        return new TestConfigScope();
    }

    TestConfigScope with(String key, String value) {
        values.put(key, value);
        return this;
    }

    @Override public String get(String key) { return values.get(key); }
    @Override public String get(String key, String defaultValue) {
        String v = values.get(key);
        return v != null ? v : defaultValue;
    }
    @Override public Integer getInt(String key) { return null; }
    @Override public Integer getInt(String key, Integer defaultValue) { return defaultValue; }
    @Override public Long getLong(String key) { return null; }
    @Override public Long getLong(String key, Long defaultValue) { return defaultValue; }
    @Override public Boolean getBoolean(String key) { return null; }
    @Override public Boolean getBoolean(String key, Boolean defaultValue) { return defaultValue; }
    @Override public String[] getArray(String key) { return null; }
    @Override public Config.Scope scope(String... scope) { return this; }
    @Override public Config.Scope root() { return this; }
    @Override public Set<String> getPropertyNames() { return values.keySet(); }
}
