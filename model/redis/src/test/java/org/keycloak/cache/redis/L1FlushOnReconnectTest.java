/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package org.keycloak.cache.redis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

/** The first subscription is startup; a later one is a reconnect and must flush the local L1. */
public class L1FlushOnReconnectTest {

    @Test
    public void firstSubscribeDoesNotFlush_reconnectDoes() {
        L1InvalidationBus bus = L1InvalidationBus.forTest();
        List<String> realmEvictions = new ArrayList<>();
        List<String> userEvictions = new ArrayList<>();
        bus.register("realms", realmEvictions::add);
        bus.register("users", userEvictions::add);

        // Startup subscribe: no flush.
        bus.onSubscribed();
        assertThat(realmEvictions, hasSize(0));
        assertThat(userEvictions, hasSize(0));

        // Reconnect subscribe: every registered cache gets a flush.
        bus.onSubscribed();
        assertThat(realmEvictions, contains(L1InvalidationBus.FLUSH_KEY));
        assertThat(userEvictions, contains(L1InvalidationBus.FLUSH_KEY));

        // A second reconnect flushes again.
        bus.onSubscribed();
        assertThat(realmEvictions, contains(L1InvalidationBus.FLUSH_KEY, L1InvalidationBus.FLUSH_KEY));
    }

    @Test
    public void flushLocalEvictsAllRegisteredCaches() {
        L1InvalidationBus bus = L1InvalidationBus.forTest();
        List<String> evictions = new ArrayList<>();
        bus.register("realms", evictions::add);

        bus.flushLocal();

        assertThat(evictions, contains(L1InvalidationBus.FLUSH_KEY));
    }
}
