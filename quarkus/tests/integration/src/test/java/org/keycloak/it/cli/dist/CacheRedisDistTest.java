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

package org.keycloak.it.cli.dist;

import org.junit.jupiter.api.Test;
import org.keycloak.it.junit5.extension.CLIResult;
import org.keycloak.it.junit5.extension.DistributionTest;
import org.keycloak.it.junit5.extension.RawDistOnly;

import io.quarkus.test.junit.main.Launch;

/**
 * Integration tests for Redis cache configuration and startup.
 * Tests validate configuration options, error handling, and successful Redis cache activation.
 */
@DistributionTest(reInstall = DistributionTest.ReInstall.BEFORE_TEST)
@RawDistOnly(reason = "Containers are immutable")
public class CacheRedisDistTest {

    @Test
    @Launch({ "build", "--cache=redis" })
    void testRedisCacheMissingUrl(CLIResult result) {
        result.assertError("Redis cache is enabled (--cache=redis) but no Redis URL is configured");
        result.assertError("Please specify --cache-redis-url=redis://host:port");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379" })
    void testRedisCacheBuildSuccess(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected, indexing keycloak-model-redis");
    }

    @Test
    @Launch({ "start-dev", "--cache=redis", "--cache-redis-url=redis://localhost:6379" })
    void testRedisCacheStartDev(CLIResult result) {
        // Will fail to connect if Redis not running, but build should succeed
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379", "--cache-redis-database=5" })
    void testRedisCacheDatabaseOption(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379", "--cache-redis-username=testuser", "--cache-redis-password=testpass" })
    void testRedisCacheAuthentication(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis-sentinel://host1:26379,host2:26379/0?sentinelMasterId=mymaster" })
    void testRedisSentinelUrl(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379", "--cache-redis-timeout=5000" })
    void testRedisTimeout(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379", "--cache-redis-max-pool-size=128", "--cache-redis-min-idle=16" })
    void testRedisConnectionPooling(CLIResult result) {
        result.assertBuild();
        result.assertMessage("Redis cache mechanism detected");
    }

    @Test
    @Launch({ "build", "--cache=redis", "--cache-redis-url=redis://localhost:6379" })
    void testRedisDisablesClusterHealthCheck(CLIResult result) {
        result.assertBuild();
        // Cluster health check should not be registered when using Redis
        result.assertNoMessage("KeycloakClusterReadyHealthCheck");
    }

    @Test
    @Launch({ "build", "--cache=local", "--cache-redis-url=redis://localhost:6379" })
    void testRedisOptionsIgnoredWhenNotEnabled(CLIResult result) {
        // Redis options should be rejected when cache != redis
        result.assertError("Disabled option: '--cache-redis-url'");
    }
}
