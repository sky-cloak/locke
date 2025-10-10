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
import org.keycloak.cluster.infinispan.LockEntry;

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
        byte[] invalidBytes = new byte[]{1, 2, 3, 4, 5}; // Invalid protobuf data

        // When/Then - should throw SerializationException
        serializer.deserialize(invalidBytes);
    }
}
