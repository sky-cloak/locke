# Security policy

## Reporting a vulnerability

Please report security vulnerabilities **privately** through GitHub's private
vulnerability reporting: open the repository's **Security** tab and choose
**"Report a vulnerability"**
(https://github.com/sky-cloak/locke/security/advisories/new). Do not open a
public issue for a suspected vulnerability.

Include, where possible: the affected Locke version, the cache backend in use
(`infinispan` or `redis`), a description of the issue, and steps to reproduce.

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
We monitor upstream advisories and rebase fixes into Locke releases. Issues
**specific to the Locke distribution** (the Redis cache modules under
`model/redis/`, our build and release tooling, or our default configuration)
should go through the private report above.

## Supported versions

Locke follows a rolling support policy: security fixes are issued against the
**current release line**. Because Locke rebases onto upstream Keycloak
continuously, the fastest path to a fix is to move to the latest Locke release.
Older lines are best-effort. The current line and its Keycloak base are listed in
[COMPATIBILITY.md](./COMPATIBILITY.md).
