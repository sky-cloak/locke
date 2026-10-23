/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.models.cache.redis.entities;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test for iter-6 fix: {@link NonExistentItem} must implement
 * {@link java.io.Serializable} so the {@code LettuceCacheAdapter} can write it
 * to Redis as a Java-serialized blob (the cache adapter's write path uses
 * {@code ObjectOutputStream}).
 *
 * <p>Before the fix, KC failed to start in {@code cache=redis} mode with
 * {@code "Failed to serialize object: org.keycloak.models.cache.redis.entities.NonExistentItem"}.
 * This bug was masked because the realm cache provider was being silently disabled
 * (see {@link org.keycloak.cache.redis.IsSupportedGuardsTest}).
 */
public class NonExistentItemSerializableTest {

    @Test
    public void implementsSerializable() {
        assertThat(java.io.Serializable.class.isAssignableFrom(NonExistentItem.class), equalTo(true));
    }

    @Test
    public void roundTrip_preservesIdAndRevision() throws Exception {
        NonExistentItem original = new NonExistentItem("test-id", 42L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        NonExistentItem decoded;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            decoded = (NonExistentItem) ois.readObject();
        }

        assertThat(decoded, notNullValue());
        assertThat(decoded.getId(), equalTo("test-id"));
        assertThat(decoded.getRevision(), equalTo(42L));
    }

    @Test
    public void roundTrip_handlesNullRevision() throws Exception {
        NonExistentItem original = new NonExistentItem("test-id-2");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        NonExistentItem decoded;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            decoded = (NonExistentItem) ois.readObject();
        }

        assertThat(decoded.getId(), equalTo("test-id-2"));
        assertThat(decoded.getRevision(), equalTo(null));
    }
}
