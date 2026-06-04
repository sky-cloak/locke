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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterListener;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.common.util.ConcurrentMultivaluedHashMap;
import org.keycloak.serialization.redis.ProtobufRedisSerializer;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

/**
 * Manages Pub/Sub event distribution for cluster coordination.
 * Replaces Infinispan cache listeners with Redis Pub/Sub channels.
 *
 * @author Claude Code
 */
public class RedisPubSubEventManager {

    private static final Logger logger = Logger.getLogger(RedisPubSubEventManager.class);

    private static final String CHANNEL_PREFIX = "keycloak:events:";

    private final RedissonClient redisson;
    private final String myNodeId;
    private final String myRegion;
    private final ConcurrentMultivaluedHashMap<String, ClusterListener> listeners = new ConcurrentMultivaluedHashMap<>();
    private final ConcurrentMap<String, Integer> subscriptionIds = new ConcurrentHashMap<>();
    private final ProtobufRedisSerializer<WrapperClusterEvent> serializer;

    public RedisPubSubEventManager(RedissonClient redisson, String myNodeId, String myRegion) {
        this.redisson = redisson;
        this.myNodeId = myNodeId;
        this.myRegion = myRegion;
        this.serializer = new ProtobufRedisSerializer<>(WrapperClusterEvent.class);
    }

    /**
     * Registers a listener for cluster events on the given task key.
     *
     * @param taskKey the event task key
     * @param listener the listener to register
     */
    public void registerListener(String taskKey, ClusterListener listener) {
        listeners.add(taskKey, listener);

        // Subscribe to channel if this is the first listener for this task key
        if (listeners.get(taskKey).size() == 1) {
            subscribeToChannel(taskKey);
        }

        if (logger.isDebugEnabled()) {
            logger.debugf("Registered listener for task key: %s (total listeners: %d)",
                    taskKey, listeners.get(taskKey).size());
        }
    }

    /**
     * Publishes a single event to the cluster.
     *
     * @param taskKey the event task key
     * @param event the event to publish
     * @param ignoreSender whether to ignore the sender
     * @param dcNotify which data centers to notify
     */
    public void publish(String taskKey, ClusterEvent event, boolean ignoreSender, ClusterProvider.DCNotify dcNotify) {
        publish(taskKey, List.of(event), ignoreSender, dcNotify);
    }

    /**
     * Publishes multiple events to the cluster.
     *
     * @param taskKey the event task key
     * @param events the events to publish
     * @param ignoreSender whether to ignore the sender
     * @param dcNotify which data centers to notify
     */
    public void publish(String taskKey, Collection<? extends ClusterEvent> events, boolean ignoreSender, ClusterProvider.DCNotify dcNotify) {
        if (events == null || events.isEmpty()) {
            return;
        }

        WrapperClusterEvent wrappedEvent = WrapperClusterEvent.wrap(
                taskKey, events, myNodeId, myRegion, dcNotify, ignoreSender);

        String channel = CHANNEL_PREFIX + taskKey;

        if (logger.isTraceEnabled()) {
            logger.tracef("Publishing %d events to channel %s: %s", events.size(), channel, wrappedEvent);
        }

        try {
            // Serialize the event using Protobuf
            byte[] serialized = serializer.serialize(wrappedEvent);

            // Publish to Redis channel
            RTopic topic = redisson.getTopic(channel);
            topic.publish(serialized);

            if (logger.isTraceEnabled()) {
                logger.tracef("Successfully published events to channel: %s", channel);
            }
        } catch (Exception e) {
            logger.errorf(e, "Failed to publish events to channel %s", channel);
        }
    }

    /**
     * Subscribes to a Redis Pub/Sub channel for the given task key.
     *
     * @param taskKey the task key to subscribe to
     */
    private void subscribeToChannel(String taskKey) {
        String channel = CHANNEL_PREFIX + taskKey;

        MessageListener<byte[]> messageListener = (ch, msg) -> {
            try {
                // Deserialize the event
                WrapperClusterEvent event = serializer.deserialize(msg);

                if (event.rejectEvent(myNodeId, myRegion)) {
                    if (logger.isTraceEnabled()) {
                        logger.tracef("Rejecting event from channel %s (sender: %s, site: %s)",
                                channel, event.senderAddress, event.senderSite);
                    }
                    return;
                }

                if (logger.isTraceEnabled()) {
                    logger.tracef("Received event on channel %s: %s", channel, event);
                }

                // Notify all registered listeners
                List<ClusterListener> taskListeners = listeners.get(event.getEventKey());
                if (taskListeners != null) {
                    for (ClusterEvent delegateEvent : event.getDelegateEvents()) {
                        for (ClusterListener clusterListener : taskListeners) {
                            try {
                                clusterListener.eventReceived(delegateEvent);
                            } catch (Exception e) {
                                logger.errorf(e, "Error invoking listener for event: %s", delegateEvent);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.errorf(e, "Failed to deserialize event from channel %s", channel);
            }
        };

        RTopic topic = redisson.getTopic(channel);
        int subscriptionId = topic.addListener(byte[].class, messageListener);
        subscriptionIds.put(taskKey, subscriptionId);

        if (logger.isDebugEnabled()) {
            logger.debugf("Subscribed to channel: %s (subscription ID: %d)", channel, subscriptionId);
        }
    }

    /**
     * Unsubscribes from a channel.
     *
     * @param taskKey the task key to unsubscribe from
     */
    public void unsubscribe(String taskKey) {
        Integer subscriptionId = subscriptionIds.remove(taskKey);
        if (subscriptionId != null) {
            String channel = CHANNEL_PREFIX + taskKey;
            RTopic topic = redisson.getTopic(channel);
            topic.removeListener(subscriptionId);

            if (logger.isDebugEnabled()) {
                logger.debugf("Unsubscribed from channel: %s", channel);
            }
        }
    }

    /**
     * Closes the Pub/Sub manager and unsubscribes from all channels.
     */
    public void close() {
        for (String taskKey : subscriptionIds.keySet()) {
            unsubscribe(taskKey);
        }
        listeners.clear();
        logger.debug("RedisPubSubEventManager closed");
    }
}
