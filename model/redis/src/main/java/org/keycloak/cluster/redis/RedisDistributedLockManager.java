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

package org.keycloak.cluster.redis;

import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * Manages distributed locks using Redisson.
 * Provides TTL-based lock expiration to match Infinispan putIfAbsent behavior.
 *
 * @author Claude Code
 */
public class RedisDistributedLockManager {

    private static final Logger logger = Logger.getLogger(RedisDistributedLockManager.class);

    private final RedissonClient redisson;

    public RedisDistributedLockManager(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * Attempts to acquire a distributed lock with the given key and TTL.
     *
     * @param lockKey the lock key
     * @param timeoutSeconds how long to wait for the lock
     * @param leaseSeconds how long to hold the lock (TTL)
     * @return true if lock acquired, false otherwise
     */
    public boolean tryLock(String lockKey, long timeoutSeconds, long leaseSeconds) {
        RLock lock = redisson.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(timeoutSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (logger.isTraceEnabled()) {
                if (acquired) {
                    logger.tracef("Successfully acquired lock for key: %s", lockKey);
                } else {
                    logger.tracef("Failed to acquire lock for key: %s", lockKey);
                }
            }
            return acquired;
        } catch (InterruptedException e) {
            logger.warnf(e, "Interrupted while trying to acquire lock: %s", lockKey);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Releases a distributed lock.
     *
     * @param lockKey the lock key to release
     */
    public void unlock(String lockKey) {
        RLock lock = redisson.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            if (logger.isTraceEnabled()) {
                logger.tracef("Released lock for key: %s", lockKey);
            }
        }
    }

    /**
     * Gets a lock instance for the given key.
     *
     * @param lockKey the lock key
     * @return the RLock instance
     */
    public RLock getLock(String lockKey) {
        return redisson.getLock(lockKey);
    }

    /**
     * Checks if a lock is currently held.
     *
     * @param lockKey the lock key
     * @return true if locked, false otherwise
     */
    public boolean isLocked(String lockKey) {
        return redisson.getLock(lockKey).isLocked();
    }

    /**
     * Checks if the current thread holds the lock.
     *
     * @param lockKey the lock key
     * @return true if current thread holds the lock
     */
    public boolean isHeldByCurrentThread(String lockKey) {
        return redisson.getLock(lockKey).isHeldByCurrentThread();
    }
}
