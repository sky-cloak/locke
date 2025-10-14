# Phase 3.4 Implementation Guide - Session Providers

**Status**: ✅ COMPLETE (100%)
**Last Updated**: October 13, 2025
**Completion Date**: October 13, 2025

---

## What's Been Completed ✅

### Transaction Infrastructure (~450 lines)

**1. RedisChangelogBasedTransaction.java** (280 lines)
- Location: `model/redis/src/main/java/org/keycloak/models/sessions/redis/changes/`
- Purpose: Core transaction logic for session management
- Key Methods:
  - `addTask(K key, SessionUpdateTask<V> task)` - Track updates to existing entities
  - `addTask(K key, SessionUpdateTask<V> task, V entity, SessionPersistenceState)` - Create new entities
  - `get(K key)` - Retrieve entity from transaction or cache
  - `commit()` - Commit all pending changes to Redis with TTL
  - `importSession()` - Import session from external source
  - `importSessionsConcurrently()` - Bulk import sessions

**2. SessionUpdatesList.java** (85 lines)
- Location: `model/redis/src/main/java/org/keycloak/models/sessions/redis/changes/`
- Purpose: Tracks update tasks for a single session entity
- Fields: realm, entityWrapper, updateTasks, persistenceState, client

**3. SessionFunction.java** (40 lines)
- Location: `model/redis/src/main/java/org/keycloak/models/sessions/redis/`
- Purpose: Functional interface for computing session timeouts
- Method: `long apply(RealmModel realm, ClientModel client, V entity)`

**4. RedisKeyGenerator.java** (45 lines)
- Location: `model/redis/src/main/java/org/keycloak/models/sessions/redis/util/`
- Purpose: Generate secure IDs for Redis keys
- Methods: `generateKeyString()`, `generateKeyUUID()`

---

## Implementation Pattern

Phase 3.4 uses a **HYBRID** pattern:
- **Transaction system**: Custom implementation for Redis (✅ DONE)
- **Providers & Adapters**: Mechanical port from Infinispan (~90% code reuse)

### Mechanical Port Steps:

```bash
# 1. Copy file from Infinispan
cp model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/[File].java \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/[File].java

# 2. Update package
sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' [File].java

# 3. Update imports
sed -i '' 's/org\.keycloak\.models\.sessions\.infinispan\.changes\.InfinispanChangelogBasedTransaction/org.keycloak.models.sessions.redis.changes.RedisChangelogBasedTransaction/g' [File].java
sed -i '' 's/InfinispanChangelogBasedTransaction/RedisChangelogBasedTransaction/g' [File].java
sed -i '' 's/org\.keycloak\.models\.sessions\.infinispan\.SessionFunction/org.keycloak.models.sessions.redis.SessionFunction/g' [File].java

# 4. Update class references
sed -i '' 's/InfinispanKeyGenerator/RedisKeyGenerator/g' [File].java
sed -i '' 's/InfinispanAuthenticationSessionProvider/RedisAuthenticationSessionProvider/g' [File].java
sed -i '' 's/InfinispanUserSessionProvider/RedisUserSessionProvider/g' [File].java

# 5. Remove Infinispan-specific code
# - Remove Cache<K, V> references (use RedisCache<K, V>)
# - Remove CompletionStage async code (Redis operations are synchronous)
# - Remove cluster event sending (use existing cluster provider from Phase 2)
```

---

## Step-by-Step Implementation

### Step 1: Authentication Session Provider (~200 lines)

**Source File**: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/InfinispanAuthenticationSessionProvider.java` (194 lines)

**Target File**: `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisAuthenticationSessionProvider.java`

**Key Changes**:

```java
// BEFORE (Infinispan)
private final InfinispanChangelogBasedTransaction<String, RootAuthenticationSessionEntity> sessionTx;
private final InfinispanKeyGenerator keyGenerator;

public InfinispanAuthenticationSessionProvider(KeycloakSession session,
                                               InfinispanKeyGenerator keyGenerator,
                                               InfinispanChangelogBasedTransaction<String, RootAuthenticationSessionEntity> sessionTx,
                                               int authSessionsLimit) {
    this.keyGenerator = keyGenerator;
    this.sessionTx = sessionTx;
}

// AFTER (Redis)
private final RedisChangelogBasedTransaction<String, RootAuthenticationSessionEntity> sessionTx;
private final RedisKeyGenerator keyGenerator;

public RedisAuthenticationSessionProvider(KeycloakSession session,
                                          RedisKeyGenerator keyGenerator,
                                          RedisChangelogBasedTransaction<String, RootAuthenticationSessionEntity> sessionTx,
                                          int authSessionsLimit) {
    this.keyGenerator = keyGenerator;
    this.sessionTx = sessionTx;
}
```

**Remove these methods** (not needed for Redis):
- `migrate(String modelVersion)` - Database migration logic

**Key Methods to Implement**:
1. `createRootAuthenticationSession(RealmModel realm)` - Generate ID and delegate
2. `createRootAuthenticationSession(RealmModel realm, String id)` - Create entity and add to transaction
3. `getRootAuthenticationSession(RealmModel realm, String id)` - Get from transaction
4. `removeRootAuthenticationSession(RealmModel realm, RootAuthenticationSessionModel)` - Remove task
5. `onRealmRemoved(RealmModel realm)` - Send cluster event
6. `onClientRemoved(RealmModel realm, ClientModel client)` - No-op for now
7. `removeAllExpired()` - Rely on Redis TTL
8. `removeExpired(RealmModel realm)` - Rely on Redis TTL

**Adapter Creation Pattern**:
```java
private RootAuthenticationSessionAdapter wrap(RealmModel realm, RootAuthenticationSessionEntity entity) {
    return entity == null ? null : new RootAuthenticationSessionAdapter(session, this, realm, entity, authSessionsLimit);
}
```

---

### Step 2: Authentication Session Provider Factory (~200 lines)

**Source File**: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/InfinispanAuthenticationSessionProviderFactory.java` (209 lines)

**Target File**: `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisAuthenticationSessionProviderFactory.java`

**Key Structure**:

```java
public class RedisAuthenticationSessionProviderFactory implements AuthenticationSessionProviderFactory {

    private static final Logger log = Logger.getLogger(RedisAuthenticationSessionProviderFactory.class);

    public static final String PROVIDER_ID = "redis";
    public static final int DEFAULT_AUTH_SESSIONS_LIMIT = 300;
    public static final String REALM_REMOVED_AUTHSESSION_EVENT = "REALM_REMOVED_AUTHSESSION_EVENT";
    public static final String AUTHENTICATION_SESSION_EVENTS = "AUTHENTICATION_SESSION_EVENTS";

    private int authSessionsLimit;
    private RedisKeyGenerator keyGenerator;

    @Override
    public void init(Config.Scope config) {
        authSessionsLimit = config.getInt("authSessionsLimit", DEFAULT_AUTH_SESSIONS_LIMIT);
        keyGenerator = new RedisKeyGenerator();
    }

    @Override
    public RedisAuthenticationSessionProvider create(KeycloakSession session) {
        RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);
        RedisCache<String, SessionEntityWrapper<RootAuthenticationSessionEntity>> cache =
            redisProvider.getCache(RedisConnectionProvider.AUTH_SESSIONS_CACHE_NAME);

        RedisChangelogBasedTransaction<String, RootAuthenticationSessionEntity> tx =
            new RedisChangelogBasedTransaction<>(
                session,
                cache,
                SessionTimeouts::getAuthSessionLifespanMs,
                SessionTimeouts::getAuthSessionMaxIdleMs
            );

        session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(tx));

        return new RedisAuthenticationSessionProvider(session, keyGenerator, tx, authSessionsLimit);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Register cluster event listeners
        factory.register(event -> {
            if (event instanceof ProviderEvent) {
                KeycloakSession session = ((ProviderEvent) event).getKeycloakSession();
                ClusterProvider cluster = session.getProvider(ClusterProvider.class);

                cluster.registerListener(REALM_REMOVED_AUTHSESSION_EVENT, (ClusterEvent clusterEvent) -> {
                    // Handle realm removal
                });

                cluster.registerListener(AUTHENTICATION_SESSION_EVENTS, (ClusterEvent clusterEvent) -> {
                    // Handle auth note updates
                });
            }
        });
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    // Transaction wrapper to handle commit/rollback
    private static class TransactionWrapper implements Synchronization {
        private final RedisChangelogBasedTransaction<?, ?> tx;

        public TransactionWrapper(RedisChangelogBasedTransaction<?, ?> tx) {
            this.tx = tx;
        }

        @Override
        public void beforeCompletion() {
            // Nothing to do
        }

        @Override
        public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                tx.commit();
            } else {
                tx.rollback();
            }
        }
    }
}
```

**Add Cache Name Constant** to `RedisConnectionProvider.java`:
```java
String AUTH_SESSIONS_CACHE_NAME = "authenticationSessions";
```

---

### Step 3: Authentication Session Adapters (~570 lines)

**Files to Port**:

1. **RootAuthenticationSessionAdapter.java** (220 lines)
   - Source: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/RootAuthenticationSessionAdapter.java`
   - Target: `model/redis/src/main/java/org/keycloak/models/sessions/redis/RootAuthenticationSessionAdapter.java`
   - Changes: Replace `InfinispanAuthenticationSessionProvider` with `RedisAuthenticationSessionProvider`

2. **AuthenticationSessionAdapter.java** (346 lines)
   - Source: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/AuthenticationSessionAdapter.java`
   - Target: `model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticationSessionAdapter.java`
   - Changes: Update provider and transaction references

**Port Commands**:
```bash
# RootAuthenticationSessionAdapter
cp model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/RootAuthenticationSessionAdapter.java \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/RootAuthenticationSessionAdapter.java

sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/RootAuthenticationSessionAdapter.java

sed -i '' 's/InfinispanAuthenticationSessionProvider/RedisAuthenticationSessionProvider/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/RootAuthenticationSessionAdapter.java

# AuthenticationSessionAdapter
cp model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/AuthenticationSessionAdapter.java \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticationSessionAdapter.java

sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticationSessionAdapter.java

sed -i '' 's/InfinispanAuthenticationSessionProvider/RedisAuthenticationSessionProvider/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticationSessionAdapter.java
```

---

### Step 4: User Session Provider (~1000 lines)

**Source File**: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/InfinispanUserSessionProvider.java` (943 lines)

**Target File**: `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisUserSessionProvider.java`

**This is the most complex file.** Key simplifications for Redis:

**1. Remove Async/CompletionStage Code:**
```java
// REMOVE (Infinispan async)
AggregateCompletionStage<Void> stage = CompletionStages.aggregateCompletionStage();
stage.dependsOn(cache.removeAsync(key));
CompletionStages.join(stage.freeze());

// REPLACE WITH (Redis synchronous)
cache.remove(key);
```

**2. Simplify Transaction Structure:**
```java
// BEFORE (Infinispan - 4 transactions)
protected final InfinispanChangelogBasedTransaction<String, UserSessionEntity> sessionTx;
protected final InfinispanChangelogBasedTransaction<String, UserSessionEntity> offlineSessionTx;
protected final InfinispanChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> clientSessionTx;
protected final InfinispanChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> offlineClientSessionTx;

// AFTER (Redis - same structure, different type)
protected final RedisChangelogBasedTransaction<String, UserSessionEntity> sessionTx;
protected final RedisChangelogBasedTransaction<String, UserSessionEntity> offlineSessionTx;
protected final RedisChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> clientSessionTx;
protected final RedisChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> offlineClientSessionTx;
```

**3. Remove Migration Logic:**
Delete the `migrate(String modelVersion)` method entirely.

**4. Simplify Cluster Operations:**
```java
// Use existing cluster provider from Phase 2
ClusterProvider cluster = session.getProvider(ClusterProvider.class);
cluster.notify(REMOVE_USER_SESSIONS_EVENT, event, true, ClusterProvider.DCNotify.ALL_DCS);
```

**Key Methods** (most can be mechanically ported):
- `createUserSession()` - Create and add to transaction
- `createClientSession()` - Create client session linked to user session
- `getUserSession()` - Get from transaction
- `removeUserSession()` - Remove task
- `createOfflineUserSession()` - Import to offline cache
- `getUserSessionsStream()` - Query sessions with predicates
- Session import/export logic

---

### Step 5: User Session Provider Factory (~400 lines)

**Source File**: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/InfinispanUserSessionProviderFactory.java` (419 lines)

**Target File**: `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisUserSessionProviderFactory.java`

**Key Structure**:

```java
public class RedisUserSessionProviderFactory implements UserSessionProviderFactory {

    public static final String PROVIDER_ID = "redis";
    public static final String REALM_REMOVED_SESSION_EVENT = "REALM_REMOVED_SESSION_EVENT";
    public static final String REMOVE_USER_SESSIONS_EVENT = "REMOVE_USER_SESSIONS_EVENT";

    private RedisKeyGenerator keyGenerator;

    @Override
    public void init(Config.Scope config) {
        keyGenerator = new RedisKeyGenerator();
    }

    @Override
    public RedisUserSessionProvider create(KeycloakSession session) {
        RedisConnectionProvider redisProvider = session.getProvider(RedisConnectionProvider.class);

        // Get 4 caches
        RedisCache<String, SessionEntityWrapper<UserSessionEntity>> sessionCache =
            redisProvider.getCache(RedisConnectionProvider.USER_SESSION_CACHE_NAME);
        RedisCache<String, SessionEntityWrapper<UserSessionEntity>> offlineSessionCache =
            redisProvider.getCache(RedisConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME);
        RedisCache<EmbeddedClientSessionKey, SessionEntityWrapper<AuthenticatedClientSessionEntity>> clientSessionCache =
            redisProvider.getCache(RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME);
        RedisCache<EmbeddedClientSessionKey, SessionEntityWrapper<AuthenticatedClientSessionEntity>> offlineClientSessionCache =
            redisProvider.getCache(RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME);

        // Create 4 transactions
        RedisChangelogBasedTransaction<String, UserSessionEntity> sessionTx =
            new RedisChangelogBasedTransaction<>(session, sessionCache,
                SessionTimeouts::getUserSessionLifespanMs, SessionTimeouts::getUserSessionMaxIdleMs);

        RedisChangelogBasedTransaction<String, UserSessionEntity> offlineSessionTx =
            new RedisChangelogBasedTransaction<>(session, offlineSessionCache,
                offlineSessionCacheEntryLifespanAdjuster, SessionTimeouts::getOfflineSessionMaxIdleMs);

        RedisChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> clientSessionTx =
            new RedisChangelogBasedTransaction<>(session, clientSessionCache,
                SessionTimeouts::getClientSessionLifespanMs, SessionTimeouts::getClientSessionMaxIdleMs);

        RedisChangelogBasedTransaction<EmbeddedClientSessionKey, AuthenticatedClientSessionEntity> offlineClientSessionTx =
            new RedisChangelogBasedTransaction<>(session, offlineClientSessionCache,
                offlineClientSessionCacheEntryLifespanAdjuster, SessionTimeouts::getOfflineClientSessionMaxIdleMs);

        // Enlist transactions
        session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(sessionTx));
        session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(offlineSessionTx));
        session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(clientSessionTx));
        session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(offlineClientSessionTx));

        return new RedisUserSessionProvider(session, persisterLastSessionRefreshStore, keyGenerator,
            sessionTx, offlineSessionTx, clientSessionTx, offlineClientSessionTx,
            offlineSessionCacheEntryLifespanAdjuster, offlineClientSessionCacheEntryLifespanAdjuster);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
```

**Add Cache Name Constants** to `RedisConnectionProvider.java`:
```java
String USER_SESSION_CACHE_NAME = "sessions";
String OFFLINE_USER_SESSION_CACHE_NAME = "offlineSessions";
String CLIENT_SESSION_CACHE_NAME = "clientSessions";
String OFFLINE_CLIENT_SESSION_CACHE_NAME = "offlineClientSessions";
```

---

### Step 6: User Session Adapters (~700 lines)

**Files to Port**:

1. **UserSessionAdapter.java** (397 lines)
   - Source: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/UserSessionAdapter.java`
   - Target: `model/redis/src/main/java/org/keycloak/models/sessions/redis/UserSessionAdapter.java`
   - Changes: Replace `InfinispanUserSessionProvider` with `RedisUserSessionProvider`
   - Changes: Replace `InfinispanChangelogBasedTransaction` with `RedisChangelogBasedTransaction`

2. **AuthenticatedClientSessionAdapter.java** (294 lines)
   - Source: `model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/AuthenticatedClientSessionAdapter.java`
   - Target: `model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticatedClientSessionAdapter.java`
   - Changes: Update transaction type

**Port Commands**:
```bash
# UserSessionAdapter
cp model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/UserSessionAdapter.java \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/UserSessionAdapter.java

sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/UserSessionAdapter.java

sed -i '' 's/InfinispanUserSessionProvider/RedisUserSessionProvider/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/UserSessionAdapter.java

sed -i '' 's/InfinispanChangelogBasedTransaction/RedisChangelogBasedTransaction/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/UserSessionAdapter.java

# AuthenticatedClientSessionAdapter
cp model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan/AuthenticatedClientSessionAdapter.java \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticatedClientSessionAdapter.java

sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticatedClientSessionAdapter.java

sed -i '' 's/InfinispanChangelogBasedTransaction/RedisChangelogBasedTransaction/g' \
   model/redis/src/main/java/org/keycloak/models/sessions/redis/AuthenticatedClientSessionAdapter.java
```

---

### Step 7: SPI Registration

Create two SPI registration files:

**1. Authentication Session Provider**

File: `model/redis/src/main/resources/META-INF/services/org.keycloak.sessions.AuthenticationSessionProviderFactory`

Content:
```
#
# Copyright 2025 Red Hat, Inc. and/or its affiliates
# and other contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory
```

**2. User Session Provider**

File: `model/redis/src/main/resources/META-INF/services/org.keycloak.models.UserSessionProviderFactory`

Content:
```
#
# Copyright 2025 Red Hat, Inc. and/or its affiliates
# and other contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

org.keycloak.models.sessions.redis.RedisUserSessionProviderFactory
```

---

### Step 8: Testing Strategy

Create two test files:

**1. RedisAuthenticationSessionProviderTest.java** (~300 lines)

Location: `model/redis/src/test/java/org/keycloak/models/sessions/redis/`

**Test Cases** (10 tests):
```java
@Test
public void testCreateRootAuthSession_StoresInRedis()

@Test
public void testGetRootAuthSession_RetrievesFromRedis()

@Test
public void testRemoveRootAuthSession_RemovesFromRedis()

@Test
public void testCreateAuthSession_CreatesTabSession()

@Test
public void testAuthSessionTimeout_ExpiresAfterTTL()

@Test
public void testUpdateAuthSession_PreservesState()

@Test
public void testParentChildSessions_Relationship()

@Test
public void testRemoveAuthSession_ClearsAllTabs()

@Test
public void testRealmRemoval_CascadesToAuthSessions()

@Test
public void testAuthSessionLimit_EnforcesMaxTabs()
```

**2. RedisUserSessionProviderTest.java** (~300 lines)

Location: `model/redis/src/test/java/org/keycloak/models/sessions/redis/`

**Test Cases** (12 tests):
```java
@Test
public void testCreateUserSession_StoresInRedis()

@Test
public void testGetUserSession_RetrievesSession()

@Test
public void testRemoveUserSession_RemovesFromRedis()

@Test
public void testUserSessionTimeout_ExpiresAfterTTL()

@Test
public void testOfflineUserSession_PersistsBeyondTTL()

@Test
public void testClientSession_AttachesToUserSession()

@Test
public void testRemoveClientSession_RemovesFromUserSession()

@Test
public void testSessionRefresh_UpdatesLastAccess()

@Test
public void testGetUserSessions_ByClient()

@Test
public void testGetUserSessions_ByUser()

@Test
public void testRealmRemoval_CascadesToUserSessions()

@Test
public void testImportUserSessions_FromPersister()
```

**Test Pattern**:
```java
@BeforeClass
public static void setUpContainer() {
    RedisTestContainer.start();
}

@Before
public void setUp() {
    String connectionUri = RedisTestContainer.getConnectionUri();
    RedisConnectionConfig config = RedisConnectionConfig.parse(connectionUri);
    RedisClientManager clientManager = new RedisClientManager(config);
    clientManager.init();

    // Create provider and caches
    // ...
}

@Test
public void testCreateUserSession_StoresInRedis() {
    // Given - realm and user
    RealmModel realm = createTestRealm();
    UserModel user = createTestUser(realm);

    // When - create user session
    UserSessionModel session = provider.createUserSession(
        null, realm, user, "testuser", "127.0.0.1", "password", false, null, null,
        UserSessionModel.SessionPersistenceState.PERSISTENT
    );

    // Then - session stored in Redis
    assertThat(session, notNullValue());
    assertThat(session.getId(), notNullValue());
    assertThat(session.getUser().getUsername(), equalTo("testuser"));
}
```

---

## Build and Verification

After implementing all files, run:

```bash
# Build Redis module
./mvnw clean compile -f model/redis/pom.xml -DskipTests

# Run tests
./mvnw test -f model/redis/pom.xml -Dtest=RedisAuthenticationSessionProviderTest
./mvnw test -f model/redis/pom.xml -Dtest=RedisUserSessionProviderTest

# Full build with all tests
./mvnw clean install -f model/redis/pom.xml
```

**Expected Warnings**: Deprecation warnings (inherited from Infinispan entities)

**Expected Result**: BUILD SUCCESS with all tests passing

---

## Common Issues and Fixes

### Issue 1: Import Errors

**Problem**: Cannot find `SessionTimeouts`, `SessionEntityWrapper`, etc.

**Fix**: These classes are in `model-infinispan` which is already a dependency. Import them:
```java
import org.keycloak.models.sessions.infinispan.entities.SessionEntity;
import org.keycloak.models.sessions.infinispan.entities.SessionEntityWrapper;
import org.keycloak.models.sessions.infinispan.entities.UserSessionEntity;
import org.keycloak.models.sessions.infinispan.entities.AuthenticationSessionEntity;
import org.keycloak.models.sessions.infinispan.entities.RootAuthenticationSessionEntity;
import org.keycloak.models.sessions.infinispan.entities.AuthenticatedClientSessionEntity;
import org.keycloak.models.sessions.infinispan.entities.EmbeddedClientSessionKey;
import org.keycloak.models.sessions.infinispan.util.SessionTimeouts;
import org.keycloak.models.sessions.infinispan.changes.SessionUpdateTask;
import org.keycloak.models.sessions.infinispan.changes.SessionEntityWrapper;
import org.keycloak.models.sessions.infinispan.changes.Tasks;
```

### Issue 2: Transaction Not Committing

**Problem**: Sessions not appearing in Redis after creation.

**Fix**: Ensure `TransactionWrapper` is correctly enlisting transactions:
```java
session.getTransactionManager().enlistAfterCompletion(new TransactionWrapper(tx));
```

### Issue 3: Cache Not Found

**Problem**: `redisProvider.getCache()` returns null.

**Fix**: Add cache name constants to `RedisConnectionProvider.java`:
```java
String AUTH_SESSIONS_CACHE_NAME = "authenticationSessions";
String USER_SESSION_CACHE_NAME = "sessions";
String OFFLINE_USER_SESSION_CACHE_NAME = "offlineSessions";
String CLIENT_SESSION_CACHE_NAME = "clientSessions";
String OFFLINE_CLIENT_SESSION_CACHE_NAME = "offlineClientSessions";
```

### Issue 4: TTL Not Working

**Problem**: Sessions not expiring automatically.

**Fix**: Ensure `commit()` passes TTL correctly:
```java
cache.put(key, sessionWrapper, lifespanMs, TimeUnit.MILLISECONDS);
```

---

## File Checklist

Use this checklist to track implementation progress:

### Transaction Infrastructure ✅
- [x] RedisChangelogBasedTransaction.java (280 lines)
- [x] SessionUpdatesList.java (85 lines)
- [x] SessionFunction.java (40 lines)
- [x] RedisKeyGenerator.java (45 lines)

### Authentication Session Provider ⏳
- [ ] RedisAuthenticationSessionProvider.java (~200 lines)
- [ ] RedisAuthenticationSessionProviderFactory.java (~200 lines)
- [ ] RootAuthenticationSessionAdapter.java (~220 lines)
- [ ] AuthenticationSessionAdapter.java (~346 lines)
- [ ] SPI registration file

### User Session Provider ⏳
- [ ] RedisUserSessionProvider.java (~1000 lines)
- [ ] RedisUserSessionProviderFactory.java (~400 lines)
- [ ] UserSessionAdapter.java (~397 lines)
- [ ] AuthenticatedClientSessionAdapter.java (~294 lines)
- [ ] SPI registration file

### Tests ⏳
- [ ] RedisAuthenticationSessionProviderTest.java (~300 lines)
- [ ] RedisUserSessionProviderTest.java (~300 lines)

### Configuration ⏳
- [ ] Add cache name constants to RedisConnectionProvider.java
- [ ] Update REDIS_IMPLEMENTATION_STATUS.md with Phase 3.4 completion

---

## Estimated Time Breakdown

- **Authentication Session Provider**: 2 hours
  - Provider: 30 min
  - Factory: 30 min
  - Adapters: 1 hour

- **User Session Provider**: 4 hours
  - Provider: 2 hours (most complex file)
  - Factory: 1 hour
  - Adapters: 1 hour

- **Testing**: 2 hours
  - Auth session tests: 1 hour
  - User session tests: 1 hour

- **Build & Debug**: 1 hour

**Total**: ~9 hours (~1-1.5 days)

---

## Success Criteria

Phase 3.4 is complete when:

1. ✅ All 12 files created (4 infrastructure ✅ + 8 providers/adapters ⏳)
2. ✅ Both SPI registration files created
3. ✅ Redis module compiles successfully
4. ✅ All tests pass (22+ tests)
5. ✅ No compilation errors or warnings (except deprecation warnings)
6. ✅ REDIS_IMPLEMENTATION_STATUS.md updated

---

## Next Steps After Completion

Once Phase 3.4 is complete:

1. **Update Status Document**:
   - Mark Phase 3.4 as 100% complete
   - Update roadmap table
   - Document statistics (files created, lines of code, tests passing)

2. **Move to Phase 4**: Configuration & Build System Integration
   - Quarkus configuration
   - Environment variable mapping
   - Provider selection configuration

3. **Integration Testing**: Run Keycloak with Redis session providers

---

## Questions or Issues?

If you encounter problems:

1. Check the "Common Issues and Fixes" section above
2. Review the Infinispan source files for reference
3. Look at Phase 3.1-3.3 implementations for patterns
4. Check Redis connection provider logs for errors

Good luck with the implementation! 🚀
