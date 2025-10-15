# Redis Cache - Environment Variables and Testing Guide

## Environment Variables for Redis Configuration

Keycloak supports both CLI arguments and environment variables for Redis configuration. Environment variables follow the pattern: `KC_<OPTION_NAME_WITH_UNDERSCORES>`

### Required Environment Variables

```bash
# Cache mechanism selection
export KC_CACHE=redis

# Redis connection URL (REQUIRED)
export KC_CACHE_REDIS_URL=redis://localhost:6379
```

### Optional Environment Variables

```bash
# Authentication
export KC_CACHE_REDIS_USERNAME=keycloak
export KC_CACHE_REDIS_PASSWORD=your_password

# Database selection (0-15, default: 0)
export KC_CACHE_REDIS_DATABASE=0

# Connection timeout in milliseconds (default: 10000)
export KC_CACHE_REDIS_TIMEOUT=10000

# Connection pool settings
export KC_CACHE_REDIS_MAX_POOL_SIZE=64    # default: 64
export KC_CACHE_REDIS_MIN_IDLE=8          # default: 8
```

### Redis Sentinel Configuration

For Redis Sentinel (high availability):

```bash
export KC_CACHE=redis
export KC_CACHE_REDIS_URL="redis-sentinel://host1:26379,host2:26379,host3:26379/0?sentinelMasterId=mymaster"
export KC_CACHE_REDIS_USERNAME=keycloak
export KC_CACHE_REDIS_PASSWORD=your_password
```

## Running Keycloak with Redis

### 1. Start Redis Server

Using Docker:
```bash
docker run -d --name keycloak-redis \
  -p 6379:6379 \
  redis:7-alpine redis-server --requirepass your_password
```

Using Docker Compose:
```yaml
services:
  redis:
    image: redis:7-alpine
    command: redis-server --requirepass your_password
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

volumes:
  redis-data:
```

### 2. Build Keycloak with Redis

Using CLI:
```bash
cd keycloak-dist-dir

# Build with Redis cache
bin/kc.sh build --cache=redis --cache-redis-url=redis://localhost:6379
```

Using Environment Variables:
```bash
export KC_CACHE=redis
export KC_CACHE_REDIS_URL=redis://localhost:6379
export KC_CACHE_REDIS_PASSWORD=your_password

bin/kc.sh build
```

### 3. Start Keycloak

Development mode:
```bash
bin/kc.sh start-dev
```

Production mode:
```bash
bin/kc.sh start --optimized \
  --hostname=localhost \
  --http-enabled=true
```

## Testing Redis Integration

### Running Integration Tests

The integration test suite includes Redis-specific tests in `CacheRedisDistTest.java`:

```bash
# Run Redis distribution tests
./mvnw -f quarkus/tests/integration/pom.xml test -Dtest=CacheRedisDistTest

# Run all cache-related tests
./mvnw -f quarkus/tests/integration/pom.xml test -Dtest="*Cache*Test"
```

### Test Coverage

The `CacheRedisDistTest` validates:

1. **Configuration Validation**:
   - Missing Redis URL error handling
   - Valid configuration acceptance

2. **Build-Time Activation**:
   - Redis cache mechanism detection
   - Proper module indexing

3. **Configuration Options**:
   - Database selection
   - Authentication credentials
   - Connection timeout
   - Connection pooling

4. **Sentinel Support**:
   - Sentinel URL format parsing

5. **Health Check Behavior**:
   - Cluster health check disabled with Redis

6. **Conditional Activation**:
   - Redis options ignored when `--cache=local`

### Manual Testing Checklist

#### 1. Basic Connection Test
```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Build and start Keycloak
bin/kc.sh build --cache=redis --cache-redis-url=redis://localhost:6379
bin/kc.sh start-dev

# Verify connection in logs
tail -f data/log/keycloak.log | grep -i redis
```

Expected log messages:
- "Redis cache mechanism detected, indexing keycloak-model-redis"
- "Redis connection provider initialized"

#### 2. Authentication Test
```bash
# Start Redis with password
docker run -d -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword

# Build with authentication
bin/kc.sh build \
  --cache=redis \
  --cache-redis-url=redis://localhost:6379 \
  --cache-redis-password=mypassword

bin/kc.sh start-dev
```

#### 3. Database Selection Test
```bash
# Use database 5 instead of default 0
bin/kc.sh build \
  --cache=redis \
  --cache-redis-url=redis://localhost:6379 \
  --cache-redis-database=5

bin/kc.sh start-dev

# Verify in Redis CLI
docker exec -it keycloak-redis redis-cli
SELECT 5
KEYS *
```

#### 4. Performance Test
```bash
# Configure larger connection pool
bin/kc.sh build \
  --cache=redis \
  --cache-redis-url=redis://localhost:6379 \
  --cache-redis-max-pool-size=128 \
  --cache-redis-min-idle=16

bin/kc.sh start-dev
```

#### 5. High Availability Test (Sentinel)
```bash
# Requires Redis Sentinel setup
bin/kc.sh build \
  --cache=redis \
  --cache-redis-url="redis-sentinel://sentinel1:26379,sentinel2:26379/0?sentinelMasterId=mymaster"

bin/kc.sh start --optimized --hostname=localhost --http-enabled=true
```

### Functional Tests

Test user sessions and realm caching:

```bash
# 1. Start Keycloak with Redis
bin/kc.sh start-dev

# 2. Login to admin console
# http://localhost:8080

# 3. Create test realm and users
# Verify data is stored in Redis:
docker exec -it keycloak-redis redis-cli
KEYS *realm*
KEYS *user*
KEYS *session*

# 4. Verify cache invalidation
# Modify realm settings in admin console
# Check that old cache keys are removed
```

### Performance Benchmarking

```bash
# 1. Start with Redis cache
bin/kc.sh build --cache=redis --cache-redis-url=redis://localhost:6379
bin/kc.sh start --optimized --http-enabled=true --hostname=localhost

# 2. Run load test (requires Apache Bench or similar)
ab -n 1000 -c 10 http://localhost:8080/realms/master/.well-known/openid-configuration

# 3. Monitor Redis performance
docker exec -it keycloak-redis redis-cli INFO stats
docker exec -it keycloak-redis redis-cli SLOWLOG GET 10

# 4. Monitor connection pool
# Check logs for connection pool statistics
```

## Troubleshooting

### Redis Connection Failures

**Error**: "Unable to connect to Redis"

**Solution**:
```bash
# Check Redis is running
docker ps | grep redis

# Test Redis connectivity
docker exec -it keycloak-redis redis-cli PING
# Should return: PONG

# Check Redis logs
docker logs keycloak-redis
```

### Authentication Failures

**Error**: "NOAUTH Authentication required"

**Solution**:
```bash
# Verify Redis password configuration
docker exec -it keycloak-redis redis-cli
AUTH your_password
PING
```

### Build-Time Validation Errors

**Error**: "Redis cache is enabled but no Redis URL is configured"

**Solution**:
```bash
# Always provide Redis URL when using --cache=redis
bin/kc.sh build --cache=redis --cache-redis-url=redis://localhost:6379
```

### Sentinel Connection Issues

**Error**: "Cannot connect to Redis Sentinel"

**Solution**:
```bash
# Verify sentinel URL format
export KC_CACHE_REDIS_URL="redis-sentinel://host1:26379,host2:26379/0?sentinelMasterId=mymaster"

# Test sentinel connectivity
redis-cli -h host1 -p 26379 SENTINEL get-master-addr-by-name mymaster
```

## Verifying Redis Cache is Active

### Check Configuration
```bash
bin/kc.sh show-config | grep cache
```

Expected output:
```
kc.cache = redis
kc.cache-redis-url = redis://localhost:6379
kc.spi-connections-redis--default--url = redis://localhost:6379
```

### Check Logs
```bash
grep -i redis data/log/keycloak.log
```

Expected messages:
- "Redis cache mechanism detected, indexing keycloak-model-redis"
- "Redis connection provider initialized successfully"
- No JGroups or Infinispan cluster messages

### Check Redis Keys
```bash
docker exec -it keycloak-redis redis-cli
KEYS *
DBSIZE
```

Expected: Keys for realms, users, clients, sessions after using Keycloak

## Environment Variable Reference

| Environment Variable | CLI Equivalent | Default | Description |
|---------------------|----------------|---------|-------------|
| `KC_CACHE` | `--cache` | `ispn` | Cache mechanism: `ispn`, `local`, or `redis` |
| `KC_CACHE_REDIS_URL` | `--cache-redis-url` | none | Redis connection URL (required) |
| `KC_CACHE_REDIS_USERNAME` | `--cache-redis-username` | none | Redis username for authentication |
| `KC_CACHE_REDIS_PASSWORD` | `--cache-redis-password` | none | Redis password for authentication |
| `KC_CACHE_REDIS_DATABASE` | `--cache-redis-database` | `0` | Redis database number (0-15) |
| `KC_CACHE_REDIS_TIMEOUT` | `--cache-redis-timeout` | `10000` | Connection timeout in milliseconds |
| `KC_CACHE_REDIS_MAX_POOL_SIZE` | `--cache-redis-max-pool-size` | `64` | Maximum connection pool size |
| `KC_CACHE_REDIS_MIN_IDLE` | `--cache-redis-min-idle` | `8` | Minimum idle connections |

## Production Deployment Checklist

- [ ] Redis server is running and accessible
- [ ] Redis authentication is configured (`requirepass`)
- [ ] Redis persistence is enabled (AOF or RDB)
- [ ] Redis memory limits are set (`maxmemory`)
- [ ] Redis eviction policy is configured (`maxmemory-policy`)
- [ ] Network security is configured (firewall, Redis `bind` setting)
- [ ] Sentinel or Redis Cluster for high availability
- [ ] Monitoring is configured (Redis INFO, SLOWLOG)
- [ ] Backup strategy is in place
- [ ] Keycloak build includes `--cache=redis` and `--cache-redis-url`
- [ ] Connection pool is sized appropriately
- [ ] Keycloak can reconnect to Redis after temporary failures

## Example Production Configuration

```bash
# Redis Sentinel with authentication and optimized pool
export KC_CACHE=redis
export KC_CACHE_REDIS_URL="redis-sentinel://sentinel1.prod:26379,sentinel2.prod:26379,sentinel3.prod:26379/0?sentinelMasterId=keycloak-master"
export KC_CACHE_REDIS_USERNAME=keycloak
export KC_CACHE_REDIS_PASSWORD="${REDIS_PASSWORD}"  # From secrets management
export KC_CACHE_REDIS_DATABASE=0
export KC_CACHE_REDIS_TIMEOUT=5000
export KC_CACHE_REDIS_MAX_POOL_SIZE=128
export KC_CACHE_REDIS_MIN_IDLE=16

# Build
bin/kc.sh build

# Start in production mode
bin/kc.sh start \
  --optimized \
  --hostname=keycloak.prod.example.com \
  --https-certificate-file=/etc/certs/keycloak.crt \
  --https-certificate-key-file=/etc/certs/keycloak.key
```
