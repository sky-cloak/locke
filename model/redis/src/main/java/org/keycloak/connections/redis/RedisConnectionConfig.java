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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Redis connections.
 * Parses connection URIs and holds all connection settings.
 *
 * Supported URI formats:
 * - Standalone: redis://host:port
 * - Sentinel: redis-sentinel://host1:port1,host2:port2?sentinelMasterId=master
 * - Cluster: redis-cluster://host1:port1,host2:port2,host3:port3
 *
 * @author Keycloak Redis Team
 */
public class RedisConnectionConfig {

    public enum Mode {
        STANDALONE,
        SENTINEL,
        CLUSTER
    }

    private final Mode mode;
    private final List<HostPort> hosts;
    private final String sentinelMasterId;
    private final String password;
    private final int database;

    // Connection pool settings
    private final int poolMinSize;
    private final int poolMaxSize;

    // Timeout settings
    private final Duration timeout;

    // Retry settings
    private final int retryAttempts;
    private final Duration retryDelay;

    private RedisConnectionConfig(Builder builder) {
        this.mode = builder.mode;
        this.hosts = builder.hosts;
        this.sentinelMasterId = builder.sentinelMasterId;
        this.password = builder.password;
        this.database = builder.database;
        this.poolMinSize = builder.poolMinSize;
        this.poolMaxSize = builder.poolMaxSize;
        this.timeout = builder.timeout;
        this.retryAttempts = builder.retryAttempts;
        this.retryDelay = builder.retryDelay;
    }

    /**
     * Parse a connection URI and create a configuration.
     *
     * @param connectionUri Redis connection URI
     * @return configuration
     */
    public static RedisConnectionConfig parse(String connectionUri) {
        if (connectionUri == null || connectionUri.isEmpty()) {
            throw new IllegalArgumentException("Connection URI cannot be null or empty");
        }

        URI uri = URI.create(connectionUri);
        String scheme = uri.getScheme();

        if (scheme == null) {
            throw new IllegalArgumentException("Invalid connection URI: missing scheme");
        }

        Mode mode;
        if (scheme.equals("redis")) {
            mode = Mode.STANDALONE;
        } else if (scheme.equals("redis-sentinel")) {
            mode = Mode.SENTINEL;
        } else if (scheme.equals("redis-cluster")) {
            mode = Mode.CLUSTER;
        } else {
            throw new IllegalArgumentException("Unsupported scheme: " + scheme +
                    ". Use redis://, redis-sentinel://, or redis-cluster://");
        }

        // Parse hosts
        List<HostPort> hosts = parseHosts(uri);

        // Parse query parameters
        String sentinelMasterId = null;
        if (mode == Mode.SENTINEL && uri.getQuery() != null) {
            String[] params = uri.getQuery().split("&");
            for (String param : params) {
                String[] kv = param.split("=");
                if (kv.length == 2 && kv[0].equals("sentinelMasterId")) {
                    sentinelMasterId = kv[1];
                }
            }
        }

        // Parse authentication
        String password = null;
        if (uri.getUserInfo() != null) {
            String[] userInfo = uri.getUserInfo().split(":");
            if (userInfo.length == 2) {
                password = userInfo[1];
            }
        }

        return new Builder()
                .mode(mode)
                .hosts(hosts)
                .sentinelMasterId(sentinelMasterId)
                .password(password)
                .build();
    }

    private static List<HostPort> parseHosts(URI uri) {
        List<HostPort> hosts = new ArrayList<>();

        // Check if multiple hosts are specified (comma-separated in authority)
        String authority = uri.getAuthority();
        if (authority == null) {
            throw new IllegalArgumentException("Invalid connection URI: missing host");
        }

        // Remove userInfo if present
        if (authority.contains("@")) {
            authority = authority.substring(authority.indexOf('@') + 1);
        }

        if (authority.contains(",")) {
            // Multiple hosts (sentinel or cluster)
            String[] hostPorts = authority.split(",");
            for (String hostPort : hostPorts) {
                hosts.add(parseHostPort(hostPort.trim()));
            }
        } else {
            // Single host (standalone)
            hosts.add(parseHostPort(authority));
        }

        return hosts;
    }

    private static HostPort parseHostPort(String hostPort) {
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;
        return new HostPort(host, port);
    }

    public Mode getMode() {
        return mode;
    }

    public List<HostPort> getHosts() {
        return hosts;
    }

    public String getSentinelMasterId() {
        return sentinelMasterId;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    public int getPoolMinSize() {
        return poolMinSize;
    }

    public int getPoolMaxSize() {
        return poolMaxSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    /**
     * Host and port pair.
     */
    public static class HostPort {
        private final String host;
        private final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /**
     * Builder for RedisConnectionConfig.
     */
    public static class Builder {
        private Mode mode = Mode.STANDALONE;
        private List<HostPort> hosts = new ArrayList<>();
        private String sentinelMasterId;
        private String password;
        private int database = 0;
        private int poolMinSize = 16;
        private int poolMaxSize = 64;
        private Duration timeout = Duration.ofMillis(2000);
        private int retryAttempts = 3;
        private Duration retryDelay = Duration.ofMillis(100);

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder hosts(List<HostPort> hosts) {
            this.hosts = hosts;
            return this;
        }

        public Builder addHost(String host, int port) {
            this.hosts.add(new HostPort(host, port));
            return this;
        }

        public Builder sentinelMasterId(String sentinelMasterId) {
            this.sentinelMasterId = sentinelMasterId;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder poolMinSize(int poolMinSize) {
            this.poolMinSize = poolMinSize;
            return this;
        }

        public Builder poolMaxSize(int poolMaxSize) {
            this.poolMaxSize = poolMaxSize;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
            return this;
        }

        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public RedisConnectionConfig build() {
            if (hosts.isEmpty()) {
                throw new IllegalStateException("At least one host must be specified");
            }
            if (mode == Mode.SENTINEL && (sentinelMasterId == null || sentinelMasterId.isEmpty())) {
                throw new IllegalStateException("Sentinel mode requires a sentinel master ID");
            }
            return new RedisConnectionConfig(this);
        }
    }
}
