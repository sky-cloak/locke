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
 * - Standalone:        redis://host:port               (plaintext)
 * - Standalone TLS:    rediss://host:port              (TLS)
 * - Sentinel:          redis-sentinel://h1:p1,h2:p2?sentinelMasterId=master
 * - Sentinel TLS:      rediss-sentinel://...
 * - Cluster:           redis-cluster://h1:p1,h2:p2,h3:p3
 * - Cluster TLS:       rediss-cluster://...
 *
 * Userinfo is parsed as {@code user:pass}; either half may be empty.
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
    private final String username;
    // Stored as String for parity with how every layer below us (System.getenv,
    // Quarkus config, URI userinfo decode) hands secrets to us. Switching to char[]
    // here would not protect against the String pool because the secret is already
    // interned upstream. Defense is the redaction contract in toRedactedString()
    // plus the log-capture test in src/test/java.
    private final String password;
    private final int database;

    // TLS settings (only meaningful when sslEnabled = true).
    private final boolean sslEnabled;
    private final String tlsCaFile;
    private final boolean tlsVerifyHostname;

    // Connection pool settings
    private final int poolMinSize;
    private final int poolMaxSize;

    // Timeout settings
    private final Duration timeout;

    // Retry settings
    private final int retryAttempts;
    private final Duration retryDelay;

    // Cluster topology refresh cadence (failover re-route backstop; adaptive triggers handle
    // the fast path). Lower = faster recovery from a shard failover, more CLUSTER NODES traffic.
    private final int topologyRefreshSeconds;

    private RedisConnectionConfig(Builder builder) {
        this.mode = builder.mode;
        this.hosts = builder.hosts;
        this.sentinelMasterId = builder.sentinelMasterId;
        this.username = builder.username;
        this.password = builder.password;
        this.database = builder.database;
        this.sslEnabled = builder.sslEnabled;
        this.tlsCaFile = builder.tlsCaFile;
        this.tlsVerifyHostname = builder.tlsVerifyHostname;
        this.poolMinSize = builder.poolMinSize;
        this.poolMaxSize = builder.poolMaxSize;
        this.timeout = builder.timeout;
        this.retryAttempts = builder.retryAttempts;
        this.retryDelay = builder.retryDelay;
        this.topologyRefreshSeconds = builder.topologyRefreshSeconds;
    }

    public int getTopologyRefreshSeconds() {
        return topologyRefreshSeconds;
    }

    /**
     * Parse a connection URI and create a configuration.
     *
     * <p>Schemes prefixed with {@code rediss} (note the second {@code s}) enable TLS
     * on the resulting connection. All other settings (database, pool sizes, etc.) come
     * from the builder, not the URI.</p>
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
        boolean ssl;
        switch (scheme) {
            case "redis":            mode = Mode.STANDALONE; ssl = false; break;
            case "rediss":           mode = Mode.STANDALONE; ssl = true;  break;
            case "redis-sentinel":   mode = Mode.SENTINEL;   ssl = false; break;
            case "rediss-sentinel":  mode = Mode.SENTINEL;   ssl = true;  break;
            case "redis-cluster":    mode = Mode.CLUSTER;    ssl = false; break;
            case "rediss-cluster":   mode = Mode.CLUSTER;    ssl = true;  break;
            default:
                throw new IllegalArgumentException("Unsupported scheme: " + scheme +
                        ". Use redis://, rediss://, redis-sentinel://, rediss-sentinel://, redis-cluster://, or rediss-cluster://");
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

        // Parse authentication. Userinfo is `user:pass` or `:pass` (legacy AUTH).
        // Either half may be empty; we keep null when absent to make the env-wins
        // precedence in DefaultRedisConnectionProviderFactory easy to test.
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                // Just a username (no password segment in the URL).
                if (!userInfo.isEmpty()) {
                    username = userInfo;
                }
            } else {
                String u = userInfo.substring(0, colon);
                String p = userInfo.substring(colon + 1);
                if (!u.isEmpty()) {
                    username = u;
                }
                if (!p.isEmpty()) {
                    password = p;
                }
            }
        }

        return new Builder()
                .mode(mode)
                .hosts(hosts)
                .sentinelMasterId(sentinelMasterId)
                .username(username)
                .password(password)
                .sslEnabled(ssl)
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

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public String getTlsCaFile() {
        return tlsCaFile;
    }

    public boolean isTlsVerifyHostname() {
        return tlsVerifyHostname;
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
     * Render this configuration with the password replaced by {@code ***}.
     * Use this in every log statement that names this object.
     */
    public String toRedactedString() {
        return "RedisConnectionConfig{" +
                "mode=" + mode +
                ", hosts=" + hosts +
                ", sentinelMasterId=" + sentinelMasterId +
                ", username=" + username +
                ", password=" + (password == null ? null : "***") +
                ", database=" + database +
                ", sslEnabled=" + sslEnabled +
                ", tlsCaFile=" + tlsCaFile +
                ", tlsVerifyHostname=" + tlsVerifyHostname +
                ", poolMinSize=" + poolMinSize +
                ", poolMaxSize=" + poolMaxSize +
                ", timeout=" + timeout +
                ", retryAttempts=" + retryAttempts +
                ", retryDelay=" + retryDelay +
                '}';
    }

    @Override
    public String toString() {
        // toString() must never reveal a password. Delegate to the redacted form
        // so that any accidental `%s` in a log statement is safe by construction.
        return toRedactedString();
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
        private String username;
        private String password;
        private int database = 0;
        private boolean sslEnabled = false;
        private String tlsCaFile;
        private boolean tlsVerifyHostname = true;
        private int poolMinSize = 16;
        private int poolMaxSize = 64;
        private Duration timeout = Duration.ofMillis(1000);
        private int retryAttempts = 3;
        private Duration retryDelay = Duration.ofMillis(100);
        private int topologyRefreshSeconds = 30;

        public Builder topologyRefreshSeconds(int topologyRefreshSeconds) {
            this.topologyRefreshSeconds = topologyRefreshSeconds;
            return this;
        }

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

        public Builder username(String username) {
            this.username = username;
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

        public Builder sslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }

        public Builder tlsCaFile(String tlsCaFile) {
            this.tlsCaFile = tlsCaFile;
            return this;
        }

        public Builder tlsVerifyHostname(boolean tlsVerifyHostname) {
            this.tlsVerifyHostname = tlsVerifyHostname;
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
