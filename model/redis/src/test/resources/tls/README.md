# TLS test fixtures

These PEM files are used by the Redis TLS integration test
(`RedisClientManagerTest#initStandalone_TlsConnection_Healthy`). They are
deliberately checked in so the test does not depend on `openssl` being present
at test time.

| File         | Purpose                                  |
| ------------ | ---------------------------------------- |
| `ca.crt`     | Self-signed CA used as the trust anchor  |
| `server.crt` | Redis server cert, signed by `ca.crt`    |
| `server.key` | Private key for `server.crt`             |

- Validity: ~10 years (issued 2026-06-03, expires 2036-05-31)
- Server SAN: `DNS:localhost, IP:127.0.0.1, IP:0.0.0.0`
- Algorithm: RSA-2048

## Regenerating

When the certs are close to expiry (or if the algorithm needs to change), regenerate
in place with:

```bash
cd model/redis/src/test/resources/tls

# 1. Self-signed CA (10 years)
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days 3650 \
  -subj "/CN=Locke Test CA" -addext "basicConstraints=CA:TRUE,pathlen:0"

# 2. Server CSR + key, signed by the CA (10 years)
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0"

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 3650 -copy_extensions copy

# 3. Remove intermediates (we don't issue more certs from this CA, so the CA private
#    key is unnecessary and safer to delete than to check in).
rm -f server.csr ca.srl ca.key
```
