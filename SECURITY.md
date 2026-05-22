# Security policy

## Reporting a vulnerability

Please report security vulnerabilities **privately** to **security@skycloak.io**.
Do not open a public issue for a suspected vulnerability.

Include, where possible: affected Locke version (e.g. `26.3.5-1`), the cache
backend in use (`infinispan` or `redis`), a description of the issue, and steps to
reproduce.

We aim to acknowledge a report within **3 business days** and to provide an
initial assessment within **7 business days**.

## Disclosure window

We follow a **90-day coordinated disclosure** window from the date a report is
acknowledged. We will work with you on timing and credit, and will request a CVE
where appropriate.

## Upstream Keycloak vulnerabilities

Locke tracks upstream Keycloak. A vulnerability in Keycloak code that is not
specific to the Locke distribution should be reported to the
[Keycloak security process](https://github.com/keycloak/keycloak/security/policy).
We monitor upstream advisories and rebase fixes into Locke releases. Issues that
are **specific to the Locke distribution** (the Redis cache modules under
`model/redis/`, our build/release tooling, or our default configuration) should
come to security@skycloak.io.

## Supported versions

Security fixes are provided for the current Locke release line. See
[COMPATIBILITY.md](./COMPATIBILITY.md) for the support window.

| Locke | Built from Keycloak | Security fixes |
|---|---|---|
| `26.3.5-x` | 26.3.5 | yes (current) |
