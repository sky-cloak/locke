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
import org.keycloak.connections.redis.RedisClientManager;
import org.keycloak.connections.redis.RedisConnectionConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Under-load validation of the GETDEL fix (docs/adr/0003 / #40) on the real engine classic
 * Azure Cache for Redis runs (Redis 6.0). Hammers Locke's actual single-use hot path
 * ({@code LettuceCacheAdapter.remove} → Lua {@code GET}+{@code DEL} via the connection pool)
 * from many threads and asserts every get-and-delete is atomic and correct under contention:
 * each {@code remove} returns exactly the value that was {@code put}, the key is gone after,
 * and there are zero errors.
 *
 * <p>Connects to a manually-run Redis (default {@code redis://localhost:6399}; override with
 * {@code REDIS_TEST_URL}) rather than Testcontainers, so it works where the Testcontainers
 * Docker auto-detection doesn't (macOS Docker Desktop). Excluded from the default build — run
 * with a 6.0 server up:
 * <pre>
 *   docker run -d --name r60 -p 6399:6379 redis:6.0
 *   ./mvnw -pl model/redis test -Dtest=RedisGetDelConcurrencyTest
 * </pre>
 */
public class RedisGetDelConcurrencyTest {

    private static final int THREADS = 24;
    private static final int PER_THREAD = 500;

    @Test
    public void concurrentGetAndDelete_isAtomicAndCorrect_onRedis60() throws Exception {
        String url = System.getenv().getOrDefault("REDIS_TEST_URL", "redis://localhost:6399");
        RedisClientManager cm = new RedisClientManager(RedisConnectionConfig.parse(url));
        cm.init();
        try {
            LuaScripts lua = new LuaScripts(cm);
            lua.loadAll();
            LettuceCacheAdapter<String, String> adapter = new LettuceCacheAdapter<>("conc", cm, null, lua);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            AtomicInteger errors = new AtomicInteger();
            AtomicInteger mismatches = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(THREADS);

            long t0 = System.nanoTime();
            for (int t = 0; t < THREADS; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < PER_THREAD; i++) {
                            String k = "conc:" + tid + ":" + i;
                            String v = "val-" + tid + "-" + i;
                            adapter.put(k, v, 60, TimeUnit.SECONDS);
                            String got = adapter.remove(k);          // Lua GET+DEL
                            if (!v.equals(got)) mismatches.incrementAndGet();
                            if (adapter.containsKey(k)) mismatches.incrementAndGet();   // must be deleted
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean finished = done.await(2, TimeUnit.MINUTES);
            pool.shutdownNow();

            long ms = (System.nanoTime() - t0) / 1_000_000;
            int total = THREADS * PER_THREAD;
            System.out.printf("[CONC] %d get-and-deletes / %d threads in %d ms (%.0f ops/s); errors=%d mismatches=%d%n",
                    total, THREADS, ms, total * 1000.0 / Math.max(ms, 1), errors.get(), mismatches.get());

            assertThat("all threads finished", finished, is(true));
            assertThat("no exceptions under concurrent load", errors.get(), is(0));
            assertThat("every get-and-delete returned the right value and deleted the key", mismatches.get(), is(0));
        } finally {
            cm.close();
        }
    }
}
