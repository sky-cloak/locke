/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.keycloak.marshalling.redis;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;
import org.infinispan.protostream.annotations.ProtoSyntax;
import org.infinispan.protostream.types.java.CommonTypes;
import org.keycloak.cluster.redis.LockEntry;
import org.keycloak.cluster.redis.WrapperClusterEvent;
import org.keycloak.marshalling.Marshalling;
import org.keycloak.models.cache.redis.ClearCacheEvent;
import org.keycloak.models.cache.redis.authorization.events.PermissionTicketRemovedEvent;
import org.keycloak.models.cache.redis.authorization.events.PermissionTicketUpdatedEvent;
import org.keycloak.models.cache.redis.authorization.events.PolicyRemovedEvent;
import org.keycloak.models.cache.redis.authorization.events.PolicyUpdatedEvent;
import org.keycloak.models.cache.redis.authorization.events.ResourceRemovedEvent;
import org.keycloak.models.cache.redis.authorization.events.ResourceServerRemovedEvent;
import org.keycloak.models.cache.redis.authorization.events.ResourceServerUpdatedEvent;
import org.keycloak.models.cache.redis.authorization.events.ResourceUpdatedEvent;
import org.keycloak.models.cache.redis.authorization.events.ScopeRemovedEvent;
import org.keycloak.models.cache.redis.authorization.events.ScopeUpdatedEvent;
import org.keycloak.models.cache.redis.authorization.stream.InResourcePredicate;
import org.keycloak.models.cache.redis.authorization.stream.InResourceServerPredicate;
import org.keycloak.models.cache.redis.authorization.stream.InScopePredicate;
import org.keycloak.models.cache.redis.events.AuthenticationSessionAuthNoteUpdateEvent;
import org.keycloak.models.cache.redis.events.CacheKeyInvalidatedEvent;
import org.keycloak.models.cache.redis.events.ClientAddedEvent;
import org.keycloak.models.cache.redis.events.ClientRemovedEvent;
import org.keycloak.models.cache.redis.events.ClientScopeAddedEvent;
import org.keycloak.models.cache.redis.events.ClientScopeRemovedEvent;
import org.keycloak.models.cache.redis.events.ClientUpdatedEvent;
import org.keycloak.models.cache.redis.events.GroupAddedEvent;
import org.keycloak.models.cache.redis.events.GroupMovedEvent;
import org.keycloak.models.cache.redis.events.GroupRemovedEvent;
import org.keycloak.models.cache.redis.events.GroupUpdatedEvent;
import org.keycloak.models.cache.redis.events.RealmRemovedEvent;
import org.keycloak.models.cache.redis.events.RealmUpdatedEvent;
import org.keycloak.models.cache.redis.events.RoleAddedEvent;
import org.keycloak.models.cache.redis.events.RoleRemovedEvent;
import org.keycloak.models.cache.redis.events.RoleUpdatedEvent;
import org.keycloak.models.cache.redis.events.UserCacheRealmInvalidationEvent;
import org.keycloak.models.cache.redis.events.UserConsentsUpdatedEvent;
import org.keycloak.models.cache.redis.events.UserFederationLinkRemovedEvent;
import org.keycloak.models.cache.redis.events.UserFederationLinkUpdatedEvent;
import org.keycloak.models.cache.redis.events.UserFullInvalidationEvent;
import org.keycloak.models.cache.redis.events.UserUpdatedEvent;
import org.keycloak.models.cache.redis.stream.GroupListPredicate;
import org.keycloak.models.cache.redis.stream.HasRolePredicate;
import org.keycloak.models.cache.redis.stream.InClientPredicate;
import org.keycloak.models.cache.redis.stream.InGroupPredicate;
import org.keycloak.models.cache.redis.stream.InIdentityProviderPredicate;
import org.keycloak.models.cache.redis.stream.InRealmPredicate;

/**
 * Protostream schema initializer for the Redis cache backend.
 *
 * <p>Mirrors {@code KeycloakModelSchema} (Infinispan) but lists the Redis-package
 * versions of each event/predicate. Both modules' classes carry the same
 * {@code @ProtoTypeId} numbers — they are intentionally never registered into the
 * same {@code SerializationContext}: each backend's serializer registers only its
 * own schema.
 *
 * <p>This fixes the iter-6 error flood:
 * <pre>
 *   Failed to publish events to channel keycloak:events:REALM_INVALIDATION_EVENTS:
 *   No marshaller registered for object of Java type
 *   org.keycloak.cluster.redis.WrapperClusterEvent
 * </pre>
 *
 * <p>Before this schema, {@code ProtobufRedisSerializer} was using
 * {@code Marshalling.getSchemas()} which only ever returned the Infinispan-package
 * schema (because that's the only {@code @ProtoSchema} interface in the codebase).
 * The Redis-package classes had {@code @ProtoTypeId} annotations but no schema to
 * collect them, so {@code SerializationContext.canMarshall(WrapperClusterEvent)}
 * was always false.
 *
 * <p>The annotation processor generates a {@code RedisModelSchemaImpl} class at
 * compile time; {@link #INSTANCE} is the public handle.
 */
@ProtoSchema(
        syntax = ProtoSyntax.PROTO3,
        schemaPackageName = Marshalling.PROTO_SCHEMA_PACKAGE,
        schemaFilePath = "proto/generated-redis",
        allowNullFields = true,
        orderedMarshallers = true,
        dependsOn = CommonTypes.class,

        includeClasses = {
                // clustering.redis package
                LockEntry.class,
                WrapperClusterEvent.class,
                WrapperClusterEvent.SiteFilter.class,

                // models.cache.redis
                ClearCacheEvent.class,

                // models.cache.redis.authorization.events package
                PermissionTicketRemovedEvent.class,
                PermissionTicketUpdatedEvent.class,
                PolicyUpdatedEvent.class,
                PolicyRemovedEvent.class,
                ResourceUpdatedEvent.class,
                ResourceRemovedEvent.class,
                ResourceServerUpdatedEvent.class,
                ResourceServerRemovedEvent.class,
                ScopeUpdatedEvent.class,
                ScopeRemovedEvent.class,

                // models.cache.redis.authorization.stream package
                InResourcePredicate.class,
                InResourceServerPredicate.class,
                InScopePredicate.class,

                // models.cache.redis.stream package
                GroupListPredicate.class,
                HasRolePredicate.class,
                InClientPredicate.class,
                InGroupPredicate.class,
                InIdentityProviderPredicate.class,
                InRealmPredicate.class,

                // models.cache.redis.events package
                AuthenticationSessionAuthNoteUpdateEvent.class,
                CacheKeyInvalidatedEvent.class,
                ClientAddedEvent.class,
                ClientUpdatedEvent.class,
                ClientRemovedEvent.class,
                ClientScopeAddedEvent.class,
                ClientScopeRemovedEvent.class,
                GroupAddedEvent.class,
                GroupMovedEvent.class,
                GroupRemovedEvent.class,
                GroupUpdatedEvent.class,
                RealmUpdatedEvent.class,
                RealmRemovedEvent.class,
                RoleAddedEvent.class,
                RoleUpdatedEvent.class,
                RoleRemovedEvent.class,
                UserCacheRealmInvalidationEvent.class,
                UserConsentsUpdatedEvent.class,
                UserFederationLinkRemovedEvent.class,
                UserFederationLinkUpdatedEvent.class,
                UserFullInvalidationEvent.class,
                UserUpdatedEvent.class,
        }
)
public interface RedisModelSchema extends GeneratedSchema {
    RedisModelSchema INSTANCE = new RedisModelSchemaImpl();
}
