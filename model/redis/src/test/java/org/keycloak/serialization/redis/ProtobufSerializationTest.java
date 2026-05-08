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

package org.keycloak.serialization.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;
import org.keycloak.cluster.redis.LockEntry;

/**
 * Unit tests for ProtobufRedisSerializer.
 * Tests serialization and deserialization of various Keycloak types using Protocol Buffers.
 *
 * @author Keycloak Redis Team
 */
public class ProtobufSerializationTest {

    @Test
    public void testSerializeAndDeserialize_LockEntry() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);
        LockEntry lockEntry = new LockEntry("node-1");

        // When
        byte[] serialized = serializer.serialize(lockEntry);
        LockEntry deserialized = serializer.deserialize(serialized);

        // Then
        assertThat(serialized, notNullValue());
        assertThat(deserialized, notNullValue());
        assertThat(deserialized.node(), equalTo(lockEntry.node()));
    }

    @Test
    public void testSerializeNull_ReturnsNull() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);

        // When
        byte[] serialized = serializer.serialize(null);

        // Then
        assertThat(serialized, nullValue());
    }

    @Test
    public void testDeserializeNull_ReturnsNull() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);

        // When
        LockEntry deserialized = serializer.deserialize(null);

        // Then
        assertThat(deserialized, nullValue());
    }

    @Test
    public void testDeserializeEmptyArray_ReturnsNull() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);

        // When
        LockEntry deserialized = serializer.deserialize(new byte[0]);

        // Then
        assertThat(deserialized, nullValue());
    }

    @Test
    public void testGetType_ReturnsCorrectClass() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);

        // When
        Class<LockEntry> type = serializer.getType();

        // Then
        assertThat(type, equalTo(LockEntry.class));
    }

    @Test
    public void testGetSerializationContext_IsNotNull() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);

        // When/Then
        assertThat(serializer.getSerializationContext(), notNullValue());
    }

    @Test(expected = SerializationException.class)
    public void testDeserialize_InvalidBytes_ThrowsException() {
        // Given
        ProtobufRedisSerializer<LockEntry> serializer = new ProtobufRedisSerializer<>(LockEntry.class);
        // Invalid protostream wire format: header byte + bogus tag/length combinations.
        // Protostream throws IllegalArgumentException for malformed bytes; the serializer
        // wraps it into SerializationException. (An IOException-only path would also be
        // valid, but Protostream uses IllegalArgumentException for protocol-level errors.)
        byte[] invalidBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        // When/Then - should throw SerializationException (or fall through; if Protostream
        // throws an unwrapped IllegalArgumentException, the test still surfaces the bug).
        serializer.deserialize(invalidBytes);
    }

    /**
     * Iter-6 follow-up regression test for the WrapperClusterEvent marshaller error flood.
     * Before the {@link org.keycloak.marshalling.redis.RedisModelSchema} fix, this
     * serialization threw {@code "No marshaller registered for object of Java type
     * org.keycloak.cluster.redis.WrapperClusterEvent"} because the Redis serializer
     * was loading the Infinispan-only schema.
     */
    @Test
    public void wrapperClusterEvent_isMarshallable() {
        org.keycloak.cluster.redis.WrapperClusterEvent event =
                org.keycloak.cluster.redis.WrapperClusterEvent.wrap(
                        "TEST_EVENT_KEY",
                        java.util.List.of(),  // no delegate events
                        "node-1",
                        null,
                        org.keycloak.cluster.ClusterProvider.DCNotify.LOCAL_DC_ONLY,
                        false);
        ProtobufRedisSerializer<org.keycloak.cluster.redis.WrapperClusterEvent> ser =
                new ProtobufRedisSerializer<>(org.keycloak.cluster.redis.WrapperClusterEvent.class);
        byte[] bytes = ser.serialize(event);
        org.keycloak.cluster.redis.WrapperClusterEvent restored = ser.deserialize(bytes);
        assertThat(bytes, notNullValue());
        assertThat(restored, notNullValue());
    }

    /**
     * Companion regression test: the redis-package events that travel inside
     * WrapperClusterEvent must also be marshallable. Before the fix, even a
     * trivial event like ClearCacheEvent would fail.
     */
    @Test
    public void clearCacheEvent_isMarshallable() {
        // ClearCacheEvent uses a singleton + @ProtoFactory pattern (private ctor).
        // It carries no fields, so its serialized form is a zero-byte protobuf.
        // The serialize call must succeed (no marshaller-missing exception);
        // the bytes themselves can be empty for fieldless messages.
        org.keycloak.models.cache.redis.ClearCacheEvent event =
                org.keycloak.models.cache.redis.ClearCacheEvent.getInstance();
        ProtobufRedisSerializer<org.keycloak.models.cache.redis.ClearCacheEvent> ser =
                new ProtobufRedisSerializer<>(org.keycloak.models.cache.redis.ClearCacheEvent.class);
        byte[] bytes = ser.serialize(event);
        // The marshaller IS registered (would have thrown otherwise).
        // The output may legitimately be a zero-length byte array for an empty message.
        assertThat(bytes, notNullValue());
    }
}
