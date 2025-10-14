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

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterListener;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;

/**
 * Redis-based cluster provider implementation.
 * Uses Redis Pub/Sub for event distribution and Redisson for distributed locks.
 *
 * @author Claude Code
 */
public class RedisClusterProvider implements ClusterProvider {

    protected static final Logger logger = Logger.getLogger(RedisClusterProvider.class);

    public static final String CLUSTER_STARTUP_TIME_KEY = "cluster-start-time";
    public static final String TASK_KEY_PREFIX = "task::";

    private final int clusterStartupTime;
    private final String myAddress;
    private final String mySite;
    private final RedisDistributedLockManager lockManager;
    private final RedisPubSubEventManager pubSubManager;
    private final ConcurrentMap<String, TaskCallback> taskCallbacks = new ConcurrentHashMap<>();
    private final ExecutorService localExecutor;

    public RedisClusterProvider(
            int clusterStartupTime,
            String myAddress,
            String mySite,
            RedisDistributedLockManager lockManager,
            RedisPubSubEventManager pubSubManager,
            ExecutorService localExecutor) {
        this.clusterStartupTime = clusterStartupTime;
        this.myAddress = myAddress;
        this.mySite = mySite;
        this.lockManager = lockManager;
        this.pubSubManager = pubSubManager;
        this.localExecutor = localExecutor;
    }

    @Override
    public int getClusterStartupTime() {
        return clusterStartupTime;
    }

    @Override
    public void close() {
        // Resources are managed by the factory
    }

    @Override
    public <T> ExecutionResult<T> executeIfNotExecuted(String taskKey, int taskTimeoutInSeconds, Callable<T> task) {
        String lockKey = TASK_KEY_PREFIX + taskKey;
        boolean locked = lockManager.tryLock(lockKey, taskTimeoutInSeconds, taskTimeoutInSeconds);

        if (locked) {
            try {
                try {
                    T result = task.call();
                    return ExecutionResult.executed(result);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException("Unexpected exception when executed task " + taskKey, e);
                }
            } finally {
                lockManager.unlock(lockKey);
                if (logger.isTraceEnabled()) {
                    logger.tracef("Task %s removed from cache (lock released)", lockKey);
                }
            }
        } else {
            return ExecutionResult.notExecuted();
        }
    }

    @Override
    public Future<Boolean> executeIfNotExecutedAsync(String taskKey, int taskTimeoutInSeconds, Callable task) {
        TaskCallback newCallback = new TaskCallback();
        TaskCallback callback = registerTaskCallback(TASK_KEY_PREFIX + taskKey, newCallback);

        // We successfully submitted our task
        if (newCallback == callback) {
            Callable<Boolean> wrappedTask = () -> {
                boolean executed = executeIfNotExecuted(taskKey, taskTimeoutInSeconds, task).isExecuted();

                if (!executed) {
                    logger.infof("Task already in progress on other cluster node. Will wait until it's finished");
                }

                callback.getTaskCompletedLatch().await(taskTimeoutInSeconds, TimeUnit.SECONDS);
                return callback.isSuccess();
            };

            Future<Boolean> future = localExecutor.submit(wrappedTask);
            callback.setFuture(future);
        } else {
            logger.infof("Task already in progress on this cluster node. Will wait until it's finished");
        }

        return callback.getFuture();
    }

    TaskCallback registerTaskCallback(String taskKey, TaskCallback callback) {
        TaskCallback existing = taskCallbacks.putIfAbsent(taskKey, callback);
        return existing == null ? callback : existing;
    }

    @Override
    public void registerListener(String taskKey, ClusterListener task) {
        pubSubManager.registerListener(taskKey, task);
    }

    @Override
    public void notify(String taskKey, ClusterEvent event, boolean ignoreSender, DCNotify dcNotify) {
        notify(taskKey, Collections.singleton(event), ignoreSender, dcNotify);
    }

    @Override
    public void notify(String taskKey, Collection<? extends ClusterEvent> events, boolean ignoreSender, DCNotify dcNotify) {
        if (events == null || events.isEmpty()) {
            return;
        }

        if (logger.isTraceEnabled()) {
            logger.tracef("Sending %d events for task key %s", events.size(), taskKey);
        }

        pubSubManager.publish(taskKey, events, ignoreSender, dcNotify);
    }

    /**
     * Called when a task is finished (lock released).
     * Used for async task completion notification.
     *
     * @param taskKey the task key
     */
    void taskFinished(String taskKey) {
        TaskCallback callback = taskCallbacks.remove(taskKey);

        if (callback != null) {
            if (logger.isDebugEnabled()) {
                logger.debugf("Finished task '%s' with '%b'", taskKey, true);
            }
            callback.setSuccess(true);
            callback.getTaskCompletedLatch().countDown();
        }
    }
}
