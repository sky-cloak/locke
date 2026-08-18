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

package org.keycloak.marshalling.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.junit.Test;

/**
 * Locke ships the Redis and Infinispan cache backends in one binary, so both protostream
 * schemas sit on the same classpath. They deliberately reuse the same schema package and
 * the same numeric type ids, which is only safe while each backend registers its own
 * schema into its own {@link SerializationContext}.
 *
 * <p>Infinispan does not do that. Its {@code SerializationContextRegistry} builds one
 * global context from every {@link SerializationContextInitializer} the {@link ServiceLoader}
 * can find. If the Redis schema is published as a service it lands in that context beside
 * the Infinispan one and boot fails with "Duplicate definition of keycloak.ResourceUpdatedEvent"
 * — for every deployment that is <em>not</em> using {@code KC_CACHE=redis}, i.e. the default.
 */
public class RedisSchemaClasspathIsolationTest {

    /**
     * Reproduces Infinispan's boot-time registry build. Must not throw.
     */
    @Test
    public void serviceLoadedSchemasBuildOneValidContext() {
        SerializationContext ctx = ProtobufUtil.newSerializationContext();
        List<String> registered = new ArrayList<>();

        for (SerializationContextInitializer initializer : ServiceLoader.load(SerializationContextInitializer.class)) {
            initializer.registerSchema(ctx);
            initializer.registerMarshallers(ctx);
            registered.add(initializer.getClass().getName());
        }

        // Guards the test itself: if the Infinispan schema ever stops being on this
        // classpath the clash becomes unreachable and the test would pass vacuously.
        assertThat(registered, hasItem("org.keycloak.marshalling.KeycloakModelSchemaImpl"));
    }
}
