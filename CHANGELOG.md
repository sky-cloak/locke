# Changelog

All notable changes to Locke are documented here.

Locke uses **composite versioning**: the version is the upstream Keycloak version it was
built from, plus a build number. `26.6.2-3` means "Keycloak 26.6.2, Locke build 3." This is
the Percona Server / Amazon Corretto convention.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [26.6.2-3] - 2026-06-03

### Security
- Redis connections can now use TLS via the `rediss://`, `rediss-sentinel://`, and
  `rediss-cluster://` URL schemes. Required for connecting to any of the major
  managed-Redis services (AWS ElastiCache with in-transit encryption, Azure Cache for
  Redis, Upstash, Redis Cloud) without a stunnel sidecar. Closes
  [sky-cloak/locke#22](https://github.com/sky-cloak/locke/issues/22).
- Server certificate chain validation is mandatory when TLS is in use. Hostname
  verification (CN/SAN match) is on by default; opt out via
  `KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME=false`. The fully-insecure "trust any cert" mode is
  not exposed.
- TLS options on a plain `redis://` URL refuse to boot, instead of silently sending
  plaintext. Catches the common "forgot the second `s`" misconfiguration.

### Added
- `KC_CACHE_REDIS_TLS_CA_FILE`: path to a PEM file holding the CA chain that signed the
  Redis server cert. For private CAs and on-prem Redis. Managed services with public CA
  chains do not need this knob.
- `KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME` (default `true`): hostname verification toggle.
- New documentation page: [docs/redis-security.md](./docs/redis-security.md) with a
  managed-Redis matrix and operational guidance.

### Fixed
- `KC_CACHE_REDIS_PASSWORD` and `KC_CACHE_REDIS_USERNAME` are now honored at runtime.
  The Quarkus mappers exposed these options in a prior release, but the factory never
  read them; only URL-embedded userinfo (`redis://user:pass@host`) worked. Connection
  authentication via env var now functions as documented. When both URL userinfo and the
  env var are set with different values, the env var wins and a WARN line is logged so
  the override is auditable.

### Notes
- Phase 2 hardening (mTLS, AWS IAM auth tokens, AWS Secrets Manager credential
  rotation, TLS protocol/cipher controls, cert reload without restart) is a planned
  follow-up release.
- This release ships on the `26.6.2-3` line only. The 26.6.1 and 26.3.5 maintenance
  lines stay on plaintext Redis for now; happy to cherry-pick on request. Open an issue
  with the line you need.

## [26.6.2-2] - earlier
## [26.6.1-2] - earlier
## [26.3.5-3] - earlier

See the [GitHub Releases](https://github.com/sky-cloak/locke/releases) page for earlier
notes.
